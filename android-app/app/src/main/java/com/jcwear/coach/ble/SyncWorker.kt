package com.jcwear.coach.ble

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jcwear.coach.HealthApp
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Phase 1d (D3/D21): background band sync every 30 min through the shared [SyncCoordinator]. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val coordinator = (applicationContext as HealthApp).container.syncCoordinator
        // A sync already running (app open, pull) is joined, never doubled.
        val finished = withTimeoutOrNull(BUDGET_MS) { coordinator.sync("BACKGROUND").join() }
        if (finished == null) Log.w(TAG, "Background sync exceeded ${BUDGET_MS / 1000} s")
        return Result.success() // the next 30-min run is the retry
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
