package com.jcwear.coach.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.jcwear.coach.ble.J2208Protocol.HrRecord
import com.jcwear.coach.ble.J2208Protocol.HrvRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Plan 1.1: only raw_hex = 'seed' rows go, and only after a backup copy exists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CleanupJobTest {
    private lateinit var context: Context
    private lateinit var db: LocalHealthDatabase
    private lateinit var settings: AppSettings

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = LocalHealthDatabase(context)
        settings = AppSettings(context)
        db.upsertHrvRecords(listOf(
            HrvRecord("2026-11-10T06:00:00", 60, 1, 55, 20, 0, 0, "real"),
            HrvRecord("2026-11-11T06:00:00", 66, 1, 52, 20, 0, 0, "seed")
        ))
        db.upsertHrRecords(listOf(HrRecord("2026-11-11T04:00:00", 50, "seed"), HrRecord("2026-11-11T05:00:00", 51, "real")))
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("jcwear_local_health.db")
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun deletesOnlySeedRowsAfterBackingUp() {
        val backup = File(context.cacheDir, "pre-cleanup.db").also { it.delete() }
        val deleted = CleanupJob(db, settings, backup).runLocalSteps()

        assertEquals(1, deleted!!["hrv_logs"])
        assertEquals(1, deleted["hr_logs"])
        assertEquals(listOf("real"), db.getAllHrvRecords().map { it.rawHex })
        assertEquals(listOf("real"), db.getAllHrRecords().map { it.rawHex })
        // The backup still holds the pre-cleanup rows, fakes included.
        SQLiteDatabase.openDatabase(backup.path, null, SQLiteDatabase.OPEN_READONLY).use { b ->
            b.rawQuery("SELECT count(*) FROM hrv_logs", null).use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
        }
        assertEquals(CleanupJob.State.RESTORE_PENDING.name, settings.cleanupState)
        assertNull("Runs once", CleanupJob(db, settings, backup).runLocalSteps())
    }

    @Test
    fun failedBackupDeletesNothing() {
        val notADir = File(context.cacheDir, "plain-file").apply { writeText("x") }
        val result = CleanupJob(db, settings, File(notADir, "backup.db")).runLocalSteps()

        assertNull(result)
        assertEquals(2, db.getAllHrvRecords().size)
        assertTrue(settings.cleanupState == null) // retried next launch
    }
}
