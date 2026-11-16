package com.jcwear.coach.ble

import android.util.Log
import com.jcwear.coach.data.AppSettings
import com.jcwear.coach.data.CleanupJob
import com.jcwear.coach.data.HomelabClient
import com.jcwear.coach.domain.DayMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

sealed interface SyncState {
    data object Idle : SyncState
    data object Connecting : SyncState
    data class Syncing(val step: String) : SyncState
    data class Synced(val at: Long) : SyncState
    data class Failed(val reason: SyncFailureReason, val at: Long) : SyncState
}

/** The band operations the coordinator needs; [J2208BleManager] in the app, a fake in tests. */
interface BandLink {
    suspend fun connect(timeoutMs: Long = 20_000)
    suspend fun syncHistory(intervalMin: Int = 10, onStep: (String) -> Unit): HistoryResult
    suspend fun morningCheck60s(): Double?
    fun disconnect()
}

/**
 * Single-flight sync (plan 1.5): one band session at a time for every trigger and the Morning Check.
 * A trigger during a running sync joins it. After the band, backs up to the home server and runs the
 * cleanup's server steps; backup failures never turn the band status red.
 */
class SyncCoordinator(
    private val band: BandLink,
    private val settings: AppSettings,
    private val homelab: HomelabClient,
    private val cleanup: CleanupJob,
    private val loadDays: () -> List<DayMetrics>,
    private val onDataChanged: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: Long = 3_000,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val mutex = Mutex()
    private var current: Deferred<SyncState>? = null

    private val _state = MutableStateFlow<SyncState>(settings.lastSyncAt.takeIf { it > 0 }?.let { SyncState.Synced(it) } ?: SyncState.Idle)
    val state: StateFlow<SyncState> = _state

    private val _recent = MutableStateFlow(settings.recentSyncsJson)
    /** JSON array of the last 20 events, newest first. */
    val recentSyncsJson: StateFlow<String> = _recent

    /** Every joiner of the same run gets that run's final state (Synced or Failed). */
    @Synchronized
    fun sync(trigger: String): Deferred<SyncState> =
        current?.takeIf { it.isActive } ?: scope.async { mutex.withLock { run(trigger); _state.value } }.also { current = it }

    /** Adds [key] to the newest Recent syncs event (e.g. the morning notifier's outcome). */
    @Synchronized
    fun annotateLatest(key: String, value: String) {
        val all = JSONArray(settings.recentSyncsJson)
        if (all.length() == 0) return
        all.getJSONObject(0).put(key, value)
        settings.recentSyncsJson = all.toString()
        _recent.value = settings.recentSyncsJson
    }

    /** Backup only (Settings → Back up now), under the same lock. */
    fun backupNow(): Job = scope.launch {
        mutex.withLock {
            val event = JSONObject().put("at", now()).put("trigger", "BACKUP")
            backup(event)
            record(event)
        }
    }

    /** Morning Check under the same lock, so it never overlaps a sync. Null = check failed. */
    suspend fun morningCheck(): Double? = mutex.withLock {
        try {
            connectWithRetry()
            band.morningCheck60s()
        } catch (e: SyncFailure) {
            Log.w(TAG, "Morning check failed: $e")
            null
        } finally {
            band.disconnect()
        }
    }

    private suspend fun connectWithRetry() {
        try {
            band.connect()
        } catch (e: SyncFailure) {
            if (e.reason != SyncFailureReason.GATT_ERROR) throw e
            Log.w(TAG, "GATT error ${e.code}; one retry in ${retryDelayMs}ms")
            delay(retryDelayMs)
            band.connect()
        }
    }

    private suspend fun run(trigger: String) {
        val started = now()
        val event = JSONObject().put("at", started).put("trigger", trigger)
        _state.value = SyncState.Connecting
        try {
            connectWithRetry()
            val r = band.syncHistory { _state.value = SyncState.Syncing(it) }
            event.put("rows", r.rows)
            r.clockDriftSec?.let { settings.bandClockDriftSec = it; event.put("drift_s", it) }
            settings.lastSyncAt = now()
            _state.value = if (r.incomplete) SyncState.Failed(SyncFailureReason.HISTORY_TIMEOUT, now()) else SyncState.Synced(now())
            event.put("result", if (r.incomplete) "INCOMPLETE" else "OK")
        } catch (e: SyncFailure) {
            Log.w(TAG, "Sync failed: $e")
            _state.value = SyncState.Failed(e.reason, now())
            event.put("result", "FAILED").put("reason", e.reason.name).put("code", e.code ?: JSONObject.NULL)
        } finally {
            band.disconnect()
            event.put("ms", now() - started)
        }
        onDataChanged()
        if ((_state.value as? SyncState.Failed)?.reason.let { it == null || it == SyncFailureReason.HISTORY_TIMEOUT }) {
            backup(event)
        }
        record(event)
    }

    private fun backup(event: JSONObject) {
        if (!homelab.isConfigured) return
        try {
            val days = loadDays()
            event.put("uploaded", homelab.upload(days.takeLast(30).filter { it.hasData }))
            settings.lastBackupAt = now()
            settings.lastBackupError = ""
            event.put("backup", "OK")
            try {
                cleanup.runServerSteps(homelab) { days }
            } catch (e: Exception) {
                if (e !is IOException && e !is org.json.JSONException) throw e
                Log.w(TAG, "Cleanup server step still pending", e) // e.g. server not updated yet
                event.put("cleanup", "pending: ${e.message}")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Backup failed", e)
            settings.lastBackupError = when ((e as? HomelabClient.HttpError)?.code) {
                401 -> "token rejected"
                503 -> "server not secured"
                null -> e.message ?: "network error"
                else -> "server error ${e.code}"
            }
            event.put("backup", "FAILED: ${settings.lastBackupError}")
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "Backup got a bad reply", e)
            settings.lastBackupError = "bad server reply"
            event.put("backup", "FAILED: bad reply")
        }
    }

    @Synchronized
    private fun record(event: JSONObject) {
        val old = JSONArray(settings.recentSyncsJson)
        val out = JSONArray().put(event)
        for (i in 0 until minOf(old.length(), 19)) out.put(old.get(i))
        settings.recentSyncsJson = out.toString()
        _recent.value = settings.recentSyncsJson
    }

    companion object {
        private const val TAG = "SyncCoordinator"
    }
}
