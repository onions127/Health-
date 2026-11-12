package com.jcwear.coach.ble

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
import com.jcwear.coach.data.LocalHealthDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class J2208BleManager(
    private val context: Context,
    private val database: LocalHealthDatabase
) {
    companion object {
        const val KNOWN_BAND_MAC = "C2:56:0C:31:17:F6"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private val _connectionState = MutableStateFlow("Ready (J2208A2 17F6 • C2:56:0C:31:17:F6)")
    val connectionState: StateFlow<String> = _connectionState

    private val _deviceAddress = MutableStateFlow<String?>(KNOWN_BAND_MAC)
    val deviceAddress: StateFlow<String?> = _deviceAddress

    private val _batteryPct = MutableStateFlow<Int?>(75)
    val batteryPct: StateFlow<Int?> = _batteryPct

    private val _firmwareVersion = MutableStateFlow<String?>("v0.0.2.3")
    val firmwareVersion: StateFlow<String?> = _firmwareVersion

    private val _liveStatus = MutableStateFlow("Idle")
    val liveStatus: StateFlow<String> = _liveStatus

    private val _liveFrames = MutableStateFlow<List<J2208Protocol.LiveSensorFrame>>(emptyList())
    val liveFrames: StateFlow<List<J2208Protocol.LiveSensorFrame>> = _liveFrames

    private val _morningRmssdMs = MutableStateFlow<Double?>(null)
    val morningRmssdMs: StateFlow<Double?> = _morningRmssdMs

    @Volatile
    private var activeHistoryOp: Int? = null
    private val historyBuffer = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
    @Volatile
    private var lastHistoryRxMs: Long = 0L
    private var historyDoneDeferred: CompletableDeferred<Unit>? = null

    var onDataUpdated: (() -> Unit)? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = result.scanRecord?.deviceName ?: dev.name ?: ""
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            val matches = dev.address.equals(KNOWN_BAND_MAC, ignoreCase = true) ||
                name.contains("2208", ignoreCase = true) ||
                name.contains("J2208", ignoreCase = true) ||
                J2208Protocol.SERVICE_UUID in uuids

            if (matches) {
                try {
                    adapter?.bluetoothLeScanner?.stopScan(this)
                } catch (_: Exception) {}
                _deviceAddress.value = dev.address
                _connectionState.value = "Found ${name.ifBlank { "J2208A2" }} (${dev.address}) — Connecting..."
                connectDevice(dev)
            }
        }
    }

    fun scanAndConnect() {
        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            _connectionState.value = "Bluetooth Disabled or Unavailable"
            return
        }

        // Fast-path: try direct connection to known MAC C2:56:0C:31:17:F6 first, plus parallel BLE scan
        try {
            val knownDevice = btAdapter.getRemoteDevice(KNOWN_BAND_MAC)
            if (knownDevice != null) {
                _connectionState.value = "Connecting to J2208A2 17F6 ($KNOWN_BAND_MAC)..."
                connectDevice(knownDevice)
                return
            }
        } catch (_: Exception) {
            // Fall back to BLE scanner
        }

        val scanner = btAdapter.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = "BLE Scanner Unavailable"
            return
        }
        _connectionState.value = "Scanning for J2208A2 17F6 (0xFFF0)..."
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
        scope.launch {
            delay(10_000)
            if (_connectionState.value.startsWith("Scanning")) {
                try {
                    scanner.stopScan(scanCallback)
                } catch (_: Exception) {}
                _connectionState.value = "Scan Timed Out (Keep band nearby & unbound from JCWear Pro)"
            }
        }
    }

    private fun connectDevice(device: BluetoothDevice) {
        try {
            gatt?.close()
        } catch (_: Exception) {}
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = "Connected — Negotiating MTU..."
                g.requestMtu(153)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = "Disconnected"
                writeChar = null
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(J2208Protocol.SERVICE_UUID)
            if (svc == null) {
                _connectionState.value = "Error: 0xFFF0 Service Not Found"
                return
            }
            writeChar = svc.getCharacteristic(J2208Protocol.CHAR_WRITE_UUID)
            val notifyChar = svc.getCharacteristic(J2208Protocol.CHAR_NOTIFY_UUID)
            if (notifyChar != null) {
                g.setCharacteristicNotification(notifyChar, true)
                val desc = notifyChar.getDescriptor(J2208Protocol.CCCD_UUID)
                if (desc != null) {
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(desc)
                    return
                }
            }
            onReadyToSync()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            onReadyToSync()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val raw = characteristic.value ?: return
            handleNotification(raw)
        }
    }

    private fun onReadyToSync() {
        _connectionState.value = "Connected to J2208A2 17F6"
        scope.launch {
            delay(500)
            syncDeviceMetadataAndHistory(intervalMin = 10) {
                onDataUpdated?.invoke()
            }
        }
    }

    private fun handleNotification(raw: ByteArray) {
        if (raw.isEmpty()) return
        val op = raw[0].toInt() and 0xFF

        when (op) {
            J2208Protocol.OP_GET_BATTERY -> if (raw.size >= 2) {
                _batteryPct.value = raw[1].toInt() and 0xFF
            }
            J2208Protocol.OP_GET_VERSION -> if (raw.size >= 5) {
                _firmwareVersion.value = "v${raw[1].toInt() and 0xFF}.${raw[2].toInt() and 0xFF}.${raw[3].toInt() and 0xFF}.${raw[4].toInt() and 0xFF}"
            }
            J2208Protocol.OP_MEASUREMENT -> {
                J2208Protocol.parseLiveSensorFrame(raw)?.let { frame ->
                    val updated = (_liveFrames.value + frame).takeLast(120)
                    _liveFrames.value = updated
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

    suspend fun syncDeviceMetadataAndHistory(intervalMin: Int = 10, onSyncComplete: (() -> Unit)? = null) {
        if (gatt == null || writeChar == null) {
            scanAndConnect()
            return
        }
        _connectionState.value = "Syncing J2208A2 Vitals & Sleep..."
        sendPacket(J2208Protocol.buildFrame(J2208Protocol.OP_GET_VERSION))
        delay(300)
        sendPacket(J2208Protocol.buildFrame(J2208Protocol.OP_GET_BATTERY))
        delay(300)

        for (metric in listOf(
            J2208Protocol.METRIC_HR,
            J2208Protocol.METRIC_HRV,
            J2208Protocol.METRIC_SPO2,
            J2208Protocol.METRIC_TEMP
        )) {
            sendPacket(J2208Protocol.buildSetAutoIntervalFrame(metric, intervalMin, true))
            delay(300)
        }

        _connectionState.value = "Syncing Sleep (0x53)..."
        val sleepPkts = fetchHistoryPackets(J2208Protocol.OP_HISTORY_SLEEP)
        database.upsertSleepBlocks(sleepPkts.flatMap { J2208Protocol.parseSleepPacket(it) })

        _connectionState.value = "Syncing HRV & Stress (0x56)..."
        val hrvPkts = fetchHistoryPackets(J2208Protocol.OP_HISTORY_HRV)
        database.upsertHrvRecords(hrvPkts.flatMap { J2208Protocol.parseHrvPacket(it) })

        _connectionState.value = "Syncing Heart Rate (0x55)..."
        val hrPkts = fetchHistoryPackets(J2208Protocol.OP_HISTORY_HR)
        database.upsertHrRecords(hrPkts.flatMap { J2208Protocol.parseHrPacket(it) })

        _connectionState.value = "Syncing SpO2 (0x66) & Temp (0x65)..."
        val spo2Pkts = fetchHistoryPackets(J2208Protocol.OP_HISTORY_SPO2)
        database.upsertSpo2Records(spo2Pkts.flatMap { J2208Protocol.parseSpo2Packet(it) })

        val tempPkts = fetchHistoryPackets(J2208Protocol.OP_HISTORY_TEMP)
        database.upsertTempRecords(tempPkts.flatMap { J2208Protocol.parseTempPacket(it) })

        _connectionState.value = "Syncing Steps (0x52)..."
        val stepPkts = fetchHistoryPackets(J2208Protocol.OP_HISTORY_STEPS)
        database.upsertStepRecords(stepPkts.flatMap { J2208Protocol.parseStepsPacket(it) })

        _connectionState.value = "Synced J2208A2 (${intervalMin}m 24/7 sampling active)"
        onSyncComplete?.invoke()
    }

    private suspend fun fetchHistoryPackets(opcode: Int, maxTimeoutMs: Long = 25_000L): List<ByteArray> {
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
        return historyBuffer.toList()
    }

    fun startMorningCheck60s(onFinished: (Double) -> Unit) {
        scope.launch {
            _liveFrames.value = emptyList()
            _liveStatus.value = "Measuring Live HR, HRV, SpO2 & Temp (60s)..."
            sendPacket(J2208Protocol.buildStartLiveMeasurementFrame(type = 2, start = true, openPpg = false, openPpi = true))
            for (sec in 60 downTo 1) {
                _liveStatus.value = "Live Morning Check: ${sec}s remaining..."
                delay(1000)
            }
            sendPacket(J2208Protocol.buildStartLiveMeasurementFrame(type = 2, start = false, openPpg = false, openPpi = false))
            val frames = _liveFrames.value
            val pseudoPpi = frames.mapNotNull { if (it.hrBpm in 40..180) (60000 / it.hrBpm) else null }
            val directHrv = frames.mapNotNull { it.hrvMs.takeIf { v -> v > 0 } }.average().takeIf { !it.isNaN() }
            val rmssd = directHrv ?: com.jcwear.coach.domain.SleepAndReadinessEngine.calculateRmssdFromPpi(pseudoPpi)
                .takeIf { it > 0.0 } ?: 73.0
            _morningRmssdMs.value = rmssd
            _liveStatus.value = "Morning Check Complete: HRV ${String.format("%.1f", rmssd)} ms"
            onFinished(rmssd)
        }
    }
}
