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
 * 1b runs the local steps: BACKUP_COPY -> LOCAL_DELETE, then parks at RESTORE_PENDING.
 * The server steps (restore, purge, summaries) continue from RESTORE_PENDING in 1c; that order is
 * safe because the server's restore never returns seed rows.
 */
class CleanupJob(
    private val db: LocalHealthDatabase,
    private val settings: AppSettings,
    private val backupFile: File
) {
    enum class State { BACKUP_COPY, LOCAL_DELETE, RESTORE_PENDING }

    /** Returns rows deleted per table, or null if nothing ran (already done, or the backup failed). */
    fun runLocalSteps(): Map<String, Int>? {
        deleteOldBackup()
        val state = settings.cleanupState?.let { runCatching { State.valueOf(it) }.getOrNull() } ?: State.BACKUP_COPY
        if (state == State.RESTORE_PENDING) return null
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
