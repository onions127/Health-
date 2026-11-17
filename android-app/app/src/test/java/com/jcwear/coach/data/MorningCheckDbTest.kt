package com.jcwear.coach.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.jcwear.coach.ble.J2208Protocol.HrRecord
import com.jcwear.coach.domain.DailySeriesRepository
import com.jcwear.coach.domain.MorningCheck
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** R1: morning checks are stored per local date, and the v5 upgrade keeps every older row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MorningCheckDbTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = LocalDate.of(2026, 11, 17)
    private val check = MorningCheck(62.4, 1_794_900_000_000L, MorningCheck.SOURCE_HRV)
    private var db: LocalHealthDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase(DB_NAME)
    }

    private fun open() = LocalHealthDatabase(context).also { db?.close(); db = it }

    private fun tables(d: LocalHealthDatabase) = d.readableDatabase
        .rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null)
        .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    @Test
    fun writeReadAndSurviveReopen() {
        assertTrue(open().putMorningCheck(day, check))
        assertEquals(mapOf(day to check), open().getAllMorningChecks())
    }

    @Test
    fun sameDayRedoReplacesAndOtherDaysStay() {
        val d = open()
        d.putMorningCheck(day.minusDays(1), check)
        d.putMorningCheck(day, check)
        val redo = MorningCheck(48.0, check.measuredAtMs + 60_000, MorningCheck.SOURCE_HR_ESTIMATE)
        assertTrue(d.putMorningCheck(day, redo))
        assertEquals(mapOf(day.minusDays(1) to check, day to redo), d.getAllMorningChecks())
    }

    @Test
    fun failedWriteReturnsFalseAndLeavesNoRow() {
        val d = open()
        d.writableDatabase.execSQL("DROP TABLE morning_checks")
        assertFalse(d.putMorningCheck(day, check))
    }

    @Test
    fun failedReadThrowsSoTheDashboardShowsItsLoadError() {
        val d = open()
        d.writableDatabase.execSQL("DROP TABLE morning_checks")
        try {
            DailySeriesRepository(d).load(day)
            fail("a failed read must not look like 'no check'")
        } catch (_: SQLiteException) {
        }
    }

    @Test
    fun freshInstallHasBothV5Tables() {
        val d = open()
        assertTrue(tables(d).containsAll(listOf("morning_checks", "weight_logs", "sleep_labels")))
        assertEquals(LocalHealthDatabase.DB_VERSION, d.readableDatabase.version)
    }

    @Test
    fun v4UpgradesToV5AndKeepsOldRows() {
        createOldDb(version = 4)
        val d = open()
        assertTrue(tables(d).containsAll(listOf("morning_checks", "weight_logs")))
        assertEquals(listOf(HrRecord("2026-11-10T04:00:00", 51, "old")), d.getAllHrRecords())
        assertEquals(1, d.readableDatabase.rawQuery("SELECT COUNT(*) FROM sleep_labels", null).use { it.moveToFirst(); it.getInt(0) })
        assertTrue(d.putMorningCheck(day, check))
    }

    @Test
    fun v3UpgradesToV5() {
        createOldDb(version = 3)
        val d = open()
        assertTrue(tables(d).containsAll(listOf("sleep_labels", "morning_checks", "weight_logs")))
        assertEquals(1, d.getAllHrRecords().size)
        assertTrue(d.getAllMorningChecks().isEmpty())
    }

    @Test
    fun rollbackToV4AndForwardAgainKeepsTheChecks() {
        open().putMorningCheck(day, check)
        db!!.close()
        // What the v4 build does to a v5 file: its no-op onDowngrade leaves the tables and stamps version 4.
        SQLiteDatabase.openDatabase(context.getDatabasePath(DB_NAME).path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 4 }
        assertEquals(mapOf(day to check), open().getAllMorningChecks())
        assertEquals(LocalHealthDatabase.DB_VERSION, db!!.readableDatabase.version)
    }

    /** A database file as the v3 / v4 builds wrote it, with one heart-rate row (and one sleep label on v4). */
    private fun createOldDb(version: Int) {
        val path = context.getDatabasePath(DB_NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { raw ->
            raw.execSQL("CREATE TABLE sleep_blocks (device_time TEXT PRIMARY KEY, unit_minutes INTEGER, count INTEGER, deep_min INTEGER, light_min INTEGER, rem_min INTEGER, awake_min INTEGER, stages_csv TEXT, raw_hex TEXT, uploaded INTEGER NOT NULL DEFAULT 0)")
            raw.execSQL("CREATE TABLE hrv_logs (device_time TEXT PRIMARY KEY, hrv_ms INTEGER, vascular_aging INTEGER, hr_bpm INTEGER, stress INTEGER, bp_sys INTEGER, bp_dia INTEGER, raw_hex TEXT, uploaded INTEGER NOT NULL DEFAULT 0)")
            raw.execSQL("CREATE TABLE hr_logs (device_time TEXT PRIMARY KEY, bpm INTEGER, raw_hex TEXT, uploaded INTEGER NOT NULL DEFAULT 0)")
            raw.execSQL("CREATE TABLE spo2_logs (device_time TEXT PRIMARY KEY, spo2_pct INTEGER, raw_hex TEXT, uploaded INTEGER NOT NULL DEFAULT 0)")
            raw.execSQL("CREATE TABLE temp_logs (device_time TEXT PRIMARY KEY, temp_c REAL, raw_hex TEXT, uploaded INTEGER NOT NULL DEFAULT 0)")
            raw.execSQL("CREATE TABLE step_logs (device_time TEXT PRIMARY KEY, steps INTEGER, calories_kcal REAL, distance_km REAL, sub_steps_csv TEXT, raw_hex TEXT, uploaded INTEGER NOT NULL DEFAULT 0)")
            raw.insert("hr_logs", null, ContentValues().apply { put("device_time", "2026-11-10T04:00:00"); put("bpm", 51); put("raw_hex", "old") })
            if (version >= 4) {
                raw.execSQL("CREATE TABLE sleep_labels (session_key TEXT PRIMARY KEY, onset TEXT NOT NULL, wake TEXT NOT NULL, kind TEXT NOT NULL, band_onset TEXT NOT NULL, band_wake TEXT NOT NULL, shown_source TEXT NOT NULL, created_at INTEGER NOT NULL)")
                raw.execSQL("INSERT INTO sleep_labels VALUES ('k', '2026-11-09T23:00+02:00', '2026-11-10T07:00+02:00', 'CONFIRMED', '2026-11-09T23:00+02:00', '2026-11-10T07:00+02:00', 'BAND', 1)")
            }
            raw.version = version
        }
    }

    private companion object {
        const val DB_NAME = "jcwear_local_health.db"
    }
}
