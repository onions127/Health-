package com.jcwear.coach.data

import android.database.sqlite.SQLiteException
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One-time removal of the fake rows the old seeder wrote (plan 1.1, D9/D15).
 * Fakes are exactly the rows with raw_hex = 'seed'; nothing else is ever deleted.
 *
 * Local steps run at startup: BACKUP_COPY -> LOCAL_DELETE -> RESTORE_PENDING. The server steps run after
 * a sync: RESTORE_PENDING -> SERVER_DELETE_PENDING -> SUMMARIES_PENDING -> DONE. Restoring after the local
 * delete is safe because the server's restore never returns seed rows.
 */
class CleanupJob(
    private val db: LocalHealthDatabase,
    private val settings: AppSettings,
    private val backupFile: File
) {
    enum class State { BACKUP_COPY, LOCAL_DELETE, RESTORE_PENDING, SERVER_DELETE_PENDING, SUMMARIES_PENDING, DONE }

    private val state: State
        get() = settings.cleanupState?.let { runCatching { State.valueOf(it) }.getOrNull() } ?: State.BACKUP_COPY

    /** True while server steps remain (Settings shows "Finishing data cleanup…"). */
    val serverStepsPending: Boolean get() = state != State.DONE

    /**
     * Server steps (1c): restore real rows missing locally, purge server seed rows, then fix summaries
     * ([days] recomputed from real data: upload them, delete dates with no data). Each step stays pending
     * on failure and retries after the next sync. Throws the failure so the caller can log it.
     */
    fun runServerSteps(homelab: HomelabClient, days: () -> List<com.jcwear.coach.domain.DayMetrics>) {
        if (!homelab.isConfigured || state.ordinal < State.RESTORE_PENDING.ordinal) return
        if (state == State.RESTORE_PENDING) {
            Log.i(TAG, "Restored ${homelab.restoreAll()} rows from the server")
            settings.cleanupState = State.SERVER_DELETE_PENDING.name
        }
        if (state == State.SERVER_DELETE_PENDING) {
            Log.i(TAG, "Server purge: ${homelab.purgeSeeded()}")
            settings.cleanupState = State.SUMMARIES_PENDING.name
        }
        if (state == State.SUMMARIES_PENDING) {
            val (real, empty) = days().partition { it.hasData }
            homelab.upload(real)
            homelab.deleteSummaries(empty.map { it.date.toString() })
            settings.cleanupState = State.DONE.name
        }
    }

    /** Returns rows deleted per table, or null if nothing ran (already done, or the backup failed). */
    fun runLocalSteps(): Map<String, Int>? {
        deleteOldBackup()
        if (state != State.BACKUP_COPY && state != State.LOCAL_DELETE) return null
        if (state == State.BACKUP_COPY) {
            try {
                db.backupTo(backupFile)
            } catch (e: IOException) {
                Log.e(TAG, "Pre-cleanup backup failed; nothing deleted", e)
                return null
            } catch (e: SQLiteException) {
                Log.e(TAG, "Pre-cleanup backup failed; nothing deleted", e)
                return null
            }
            settings.cleanupState = State.LOCAL_DELETE.name
        }
        val deleted = db.deleteSeedRows()
        Log.i(TAG, "Removed seed rows: $deleted")
        settings.cleanupState = State.RESTORE_PENDING.name
        return deleted
    }

    /** The backup is kept 30 days (plan 1.1). */
    private fun deleteOldBackup() {
        if (backupFile.isFile && System.currentTimeMillis() - backupFile.lastModified() > TimeUnit.DAYS.toMillis(30)) {
            backupFile.delete()
        }
    }

    companion object {
        private const val TAG = "CleanupJob"
        const val BACKUP_NAME = "jcwear_local_health.pre-cleanup.db"
    }
}
