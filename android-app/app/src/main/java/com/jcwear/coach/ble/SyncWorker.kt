package com.jcwear.coach.ble

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import android.database.sqlite.SQLiteException
import com.jcwear.coach.AppContainer
import com.jcwear.coach.HealthApp
import com.jcwear.coach.domain.DailySeriesRepository
import com.jcwear.coach.domain.SleepAndReadinessEngine
import com.jcwear.coach.notify.BedtimeReminder
import com.jcwear.coach.notify.MorningNotifier
import com.jcwear.coach.notify.Notifications
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Phase 1d (D3/D21): background band sync every 30 min through the shared [SyncCoordinator]. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as HealthApp).container
        // A sync already running (app open, pull) is joined, never doubled; its outcome is ours.
        val state = withTimeoutOrNull(BUDGET_MS) { runCatching { c.syncCoordinator.sync("BACKGROUND").await() }.getOrNull() }
        if (state == null) Log.w(TAG, "Background sync failed or exceeded ${BUDGET_MS / 1000} s")
        try {
            c.syncCoordinator.annotateLatest("morning", morningNotify(c, state is SyncState.Synced))
            BedtimeReminder.plan(applicationContext) // also restores the alarm after a reboot
        } catch (e: SQLiteException) {
            Log.w(TAG, "Notifiers couldn't read the DB", e)
        }
        return Result.success() // the next 30-min run is the retry
    }

    /** Posts the morning summary when due; returns "notified" or "skipped: <code>" for Recent syncs. */
    private fun morningNotify(c: AppContainer, syncOk: Boolean): String {
        val now = LocalDateTime.now()
        val today = if (syncOk) {
            DailySeriesRepository(c.database).load(now.toLocalDate(), c.settings.heightCm, c.settings.weightKg).lastOrNull()
        } else null
        val bandEnd = today?.sleep?.bandEndTime
        val lastBlockEpochs = bandEnd?.let { end ->
            c.database.getAllSleepBlocks().filter { it.unitMinutes == 1 }.lastOrNull { b ->
                SleepAndReadinessEngine.parseDateTime(b.deviceTime)?.truncatedTo(ChronoUnit.MINUTES)
                    ?.plusMinutes(b.stages.size - 1L) == end
            }?.stages?.size
        }
        MorningNotifier.skipReason(syncOk, c.settings.morningNotifyEnabled, now, c.settings.lastMorningNotifyDate, today, lastBlockEpochs)
            ?.let { return "skipped: $it" }
        val night = today!!.sleep!!
        if (!Notifications.postMorning(applicationContext, MorningNotifier.text(today), night)) return "skipped: no_permission"
        c.settings.lastMorningNotifyDate = now.toLocalDate().toString()
        return "notified"
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "band-sync"

        // shortcut: worst case today is ~170 s (20 s connect + 6 x 25 s history); 4 min until Recent syncs
        // gives a measured p95, then set p95 + 30 s (keep under WorkManager's 10 min).
        private const val BUDGET_MS = 4 * 60_000L

        /** Idempotent: an existing schedule is kept. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES).build()
            )
        }
    }
}
