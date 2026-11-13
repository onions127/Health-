package com.jcwear.coach.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.jcwear.coach.data.AppSettings
import com.jcwear.coach.data.LocalHealthDatabase
import com.jcwear.coach.domain.SleepAndReadinessEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.LocalDateTime

/** Why a sync or check couldn't run; each maps to one user-facing message (plan 1.5). */
enum class SyncFailureReason { BLUETOOTH_OFF, PERMISSION_MISSING, BAND_NOT_FOUND, GATT_ERROR, HISTORY_TIMEOUT }

class SyncFailure(val reason: SyncFailureReason, val code: Int? = null) :
    Exception("$reason${code?.let { " ($it)" } ?: ""}")

/** Result of one history pull: rows parsed, whether any history fetch timed out, band clock drift. */
data class HistoryResult(val rows: Int, val incomplete: Boolean, val clockDriftSec: Long?)

/**
 * Low-level J2208 GATT client. It never syncs on its own: [SyncCoordinator] calls [connect],
 * [syncHistory] / [morningCheck60s] and [disconnect] under one lock.
 */
@SuppressLint("MissingPermission")
class J2208BleManager(
    private val context: Context,
    private val database: LocalHealthDatabase,
    private val settings: AppSettings
) : BandLink {
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager?.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var ready: CompletableDeferred<Unit>? = null
    @Volatile private var scanning = false

    private val _connectionState = MutableStateFlow("Not connected")
    val connectionState: StateFlow<String> = _connectionState

    private val _batteryPct = MutableStateFlow<Int?>(settings.lastBatteryPct.takeIf { it >= 0 })
    val batteryPct: StateFlow<Int?> = _batteryPct

    private val _firmwareVersion = MutableStateFlow<String?>(null)
    val firmwareVersion: StateFlow<String?> = _firmwareVersion

    private val _liveStatus = MutableStateFlow("Idle")
    val liveStatus: StateFlow<String> = _liveStatus

    private val _liveFrames = MutableStateFlow<List<J2208Protocol.LiveSensorFrame>>(emptyList())
    val liveFrames: StateFlow<List<J2208Protocol.LiveSensorFrame>> = _liveFrames

    private val _morningRmssdMs = MutableStateFlow<Double?>(null)
    val morningRmssdMs: StateFlow<Double?> = _morningRmssdMs

    @Volatile private var bandTime: LocalDateTime? = null

    @Volatile private var activeHistoryOp: Int? = null
    private val historyBuffer = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
    @Volatile private var lastHistoryRxMs: Long = 0L
    private var historyDoneDeferred: CompletableDeferred<Unit>? = null

    var onDataUpdated: (() -> Unit)? = null

    private fun hasPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return perms.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    /**
     * Connects and waits until notifications are on. Throws [SyncFailure]; on any failure the GATT is closed.
     * With no saved band address it scans for any J2208 and remembers the first one found.
     */
    override suspend fun connect(timeoutMs: Long) {
        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) throw SyncFailure(SyncFailureReason.BLUETOOTH_OFF)
        if (!hasPermissions()) throw SyncFailure(SyncFailureReason.PERMISSION_MISSING)
        if (gatt != null && writeChar != null) return

        val d = CompletableDeferred<Unit>()
        ready = d
        try {
            val address = settings.bandAddress
            if (address.isNotBlank()) {
                _connectionState.value = "Connecting…"
                connectDevice(btAdapter.getRemoteDevice(address))
            } else {
                _connectionState.value = "Searching for your band…"
                scanning = true
                btAdapter.bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
                    ?: throw SyncFailure(SyncFailureReason.BLUETOOTH_OFF)
            }
            // getRemoteDevice never fails for a known MAC, so "band not found" is this timeout.
            withTimeout(timeoutMs) { d.await() }
            _connectionState.value = "Connected"
        } catch (e: TimeoutCancellationException) {
            disconnect()
            throw SyncFailure(SyncFailureReason.BAND_NOT_FOUND)
        } catch (e: SecurityException) {
            disconnect()
            throw SyncFailure(SyncFailureReason.PERMISSION_MISSING)
        } catch (e: SyncFailure) {
            disconnect()
            throw e
        }
    }

    /** Always closes the GATT (an unclosed one leaks and causes status 133 later). */
    override fun disconnect() {
        try {
            if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan without permission", e)
        }
        scanning = false
        gatt?.let { g ->
            try {
                g.disconnect()
                g.close()
            } catch (e: SecurityException) {
                Log.w(TAG, "GATT close without permission", e)
            }
        }
        gatt = null
        writeChar = null
        _connectionState.value = "Not connected"
    }

    /** "Forget band": the next sync scans for any J2208 and remembers it. */
    fun forgetBand() {
        disconnect()
        settings.bandAddress = ""
        settings.lastBatteryPct = -1
        _batteryPct.value = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = result.scanRecord?.deviceName ?: ""
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            if (!scanning || !(name.contains("2208") || J2208Protocol.SERVICE_UUID in uuids)) return
            scanning = false
            adapter?.bluetoothLeScanner?.stopScan(this)
            settings.bandAddress = dev.address
            _connectionState.value = "Found ${name.ifBlank { "J2208" }} — connecting…"
            connectDevice(dev)
        }

        override fun onScanFailed(errorCode: Int) {
            ready?.completeExceptionally(SyncFailure(SyncFailureReason.GATT_ERROR, errorCode))
        }
    }

    private fun connectDevice(device: BluetoothDevice) {
        gatt?.close()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT connection status $status")
                ready?.completeExceptionally(SyncFailure(SyncFailureReason.GATT_ERROR, status))
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(153)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                writeChar = null
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(J2208Protocol.SERVICE_UUID)
            if (svc == null) {
                ready?.completeExceptionally(SyncFailure(SyncFailureReason.GATT_ERROR, status))
                return
            }
            writeChar = svc.getCharacteristic(J2208Protocol.CHAR_WRITE_UUID)
            val notifyChar = svc.getCharacteristic(J2208Protocol.CHAR_NOTIFY_UUID)
            val desc = notifyChar?.getDescriptor(J2208Protocol.CCCD_UUID)
            if (notifyChar != null && desc != null) {
                g.setCharacteristicNotification(notifyChar, true)
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(desc)
                return
            }
            ready?.complete(Unit)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            ready?.complete(Unit)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val raw = characteristic.value ?: return
            handleNotification(raw)
        }
    }

    private fun handleNotification(raw: ByteArray) {
        if (raw.isEmpty()) return
        val op = raw[0].toInt() and 0xFF

        when (op) {
            J2208Protocol.OP_GET_BATTERY -> if (raw.size >= 2) {
                val pct = raw[1].toInt() and 0xFF
                _batteryPct.value = pct
                settings.lastBatteryPct = pct
            }
            J2208Protocol.OP_GET_VERSION -> if (raw.size >= 5) {
                _firmwareVersion.value = "v${raw[1].toInt() and 0xFF}.${raw[2].toInt() and 0xFF}.${raw[3].toInt() and 0xFF}.${raw[4].toInt() and 0xFF}"
            }
            J2208Protocol.OP_GET_TIME -> {
                // PROTOCOL.md 0x41: BCD yy mm dd HH MM SS from byte 1
                bandTime = J2208Protocol.parseRecordDateTime(raw, 1)?.let { SleepAndReadinessEngine.parseDateTime(it) }
            }
            J2208Protocol.OP_MEASUREMENT -> {
                J2208Protocol.parseLiveSensorFrame(raw)?.let { frame ->
                    _liveFrames.value = (_liveFrames.value + frame).takeLast(120)
                }
            }
            activeHistoryOp -> {
                lastHistoryRxMs = System.currentTimeMillis()
                historyBuffer.add(raw.copyOf())
                if (J2208Protocol.isEndOfHistory(raw, op)) {
                    historyDoneDeferred?.complete(Unit)
                } else if (historyBuffer.size % 50 == 0) {
                    sendPacket(J2208Protocol.buildHistoryRequestFrame(op, continuePage = true))
                }
            }
        }
    }

    private fun sendPacket(packet: ByteArray) {
        val g = gatt ?: return
        val ch = writeChar ?: return
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = packet
        g.writeCharacteristic(ch)
    }

    /** Pulls all history into the DB. Requires [connect]. A timed-out fetch keeps its partial rows. */
    override suspend fun syncHistory(intervalMin: Int, onStep: (String) -> Unit): HistoryResult {
        onStep("Reading band")
        bandTime = null
        sendPacket(J2208Protocol.buildFrame(J2208Protocol.OP_GET_VERSION))
        delay(300)
        sendPacket(J2208Protocol.buildFrame(J2208Protocol.OP_GET_BATTERY))
        delay(300)
        val askedAt = LocalDateTime.now()
        sendPacket(J2208Protocol.buildFrame(J2208Protocol.OP_GET_TIME))
        delay(300)
        val drift = bandTime?.let { Duration.between(askedAt, it).seconds }

        for (metric in listOf(J2208Protocol.METRIC_HR, J2208Protocol.METRIC_HRV, J2208Protocol.METRIC_SPO2, J2208Protocol.METRIC_TEMP)) {
            sendPacket(J2208Protocol.buildSetAutoIntervalFrame(metric, intervalMin, true))
            delay(300)
        }

        var rows = 0
        var incomplete = false
        suspend fun <T> pull(step: String, op: Int, parse: (ByteArray) -> List<T>, store: (List<T>) -> Unit) {
            onStep(step)
            val (pkts, done) = fetchHistoryPackets(op)
            if (!done) incomplete = true
            val recs = pkts.flatMap(parse)
            store(recs)
            rows += recs.size
        }
        pull("Sleep", J2208Protocol.OP_HISTORY_SLEEP, J2208Protocol::parseSleepPacket, database::upsertSleepBlocks)
        pull("HRV", J2208Protocol.OP_HISTORY_HRV, J2208Protocol::parseHrvPacket, database::upsertHrvRecords)
        pull("Heart rate", J2208Protocol.OP_HISTORY_HR, J2208Protocol::parseHrPacket, database::upsertHrRecords)
        pull("Blood oxygen", J2208Protocol.OP_HISTORY_SPO2, J2208Protocol::parseSpo2Packet, database::upsertSpo2Records)
        pull("Skin temperature", J2208Protocol.OP_HISTORY_TEMP, J2208Protocol::parseTempPacket, database::upsertTempRecords)
        pull("Steps", J2208Protocol.OP_HISTORY_STEPS, J2208Protocol::parseStepsPacket, database::upsertStepRecords)
        return HistoryResult(rows, incomplete, drift)
    }

    /** Returns packets and whether the band sent its end marker before [maxTimeoutMs]. */
    private suspend fun fetchHistoryPackets(opcode: Int, maxTimeoutMs: Long = 25_000L): Pair<List<ByteArray>, Boolean> {
        historyBuffer.clear()
        activeHistoryOp = opcode
        val deferred = CompletableDeferred<Unit>()
        historyDoneDeferred = deferred
        lastHistoryRxMs = System.currentTimeMillis()
        sendPacket(J2208Protocol.buildHistoryRequestFrame(opcode, continuePage = false))

        val startMs = System.currentTimeMillis()
        while (!deferred.isCompleted && (System.currentTimeMillis() - startMs) < maxTimeoutMs) {
            delay(250)
            if (!deferred.isCompleted && (System.currentTimeMillis() - lastHistoryRxMs) > 1200L) {
                lastHistoryRxMs = System.currentTimeMillis()
                sendPacket(J2208Protocol.buildHistoryRequestFrame(opcode, continuePage = true))
            }
        }
        activeHistoryOp = null
        if (!deferred.isCompleted) Log.w(TAG, "History 0x${opcode.toString(16)} timed out after ${historyBuffer.size} packets")
        return historyBuffer.toList() to deferred.isCompleted
    }

    /** 60 s live measurement. Requires [connect]. Returns RMSSD, or null when no usable HRV was measured. */
    override suspend fun morningCheck60s(): Double? {
        _liveFrames.value = emptyList()
        sendPacket(J2208Protocol.buildStartLiveMeasurementFrame(type = 2, start = true, openPpg = false, openPpi = true))
        for (sec in 60 downTo 1) {
            _liveStatus.value = "Live Morning Check: ${sec}s remaining..."
            delay(1000)
        }
        sendPacket(J2208Protocol.buildStartLiveMeasurementFrame(type = 2, start = false, openPpg = false, openPpi = false))
        val frames = _liveFrames.value
        val pseudoPpi = frames.mapNotNull { if (it.hrBpm in 40..180) (60000 / it.hrBpm) else null }
        val directHrv = frames.mapNotNull { it.hrvMs.takeIf { v -> v > 0 } }.average().takeIf { !it.isNaN() }
        val rmssd = directHrv ?: SleepAndReadinessEngine.calculateRmssdFromPpi(pseudoPpi).takeIf { it > 0.0 }
        if (rmssd == null) {
            _liveStatus.value = "Check failed, try again"
            return null
        }
        _morningRmssdMs.value = rmssd
        _liveStatus.value = "Morning Check Complete: HRV ${String.format("%.1f", rmssd)} ms"
        return rmssd
    }

    companion object {
        private const val TAG = "J2208BleManager"
    }
}
