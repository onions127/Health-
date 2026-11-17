package com.jcwear.coach.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Phase 0 regression guard: importing the bundled band history must only ADD rows.
 * The old seeder overwrote the whole DB file and wrote fake "seed" rows on every launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryImportTest {

    private lateinit var context: Context
    private lateinit var db: LocalHealthDatabase
    private lateinit var source: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = LocalHealthDatabase(context)
        source = File(context.cacheDir, "source_history.db").also { it.delete() }
        // Build a source DB with the same schema as the bundled asset by reusing onCreate.
        SQLiteDatabase.openOrCreateDatabase(source, null).use { src ->
            db.onCreate(src)
            src.insert("hrv_logs", null, hrv("2026-11-10T06:00:00", hrvMs = 40, raw = "src-conflict"))
            src.insert("hrv_logs", null, hrv("2026-11-11T06:00:00", hrvMs = 55, raw = "src-new"))
            src.insert("spo2_logs", null, ContentValues().apply {
                put("device_time", "2026-11-11T03:00:00"); put("spo2_pct", 96); put("raw_hex", "src-new")
            })
        }
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("jcwear_local_health.db")
        source.delete()
    }

    @Test
    fun importAddsMissingRowsAndLocalRowWins() {
        db.writableDatabase.insert("hrv_logs", null, hrv("2026-11-10T06:00:00", hrvMs = 62, raw = "local-real"))

        val result = db.importHistoryFrom(source)

        assertEquals(1, result.insertedByTable["hrv_logs"])
        assertEquals(1, result.insertedByTable["spo2_logs"])
        assertEquals(2, result.totalInserted)
        val records = db.getAllHrvRecords().associateBy { it.deviceTime }
        assertEquals("local row must not be overwritten", 62, records.getValue("2026-11-10T06:00:00").hrvMs)
        assertEquals(55, records.getValue("2026-11-11T06:00:00").hrvMs)
    }

    @Test
    fun secondImportAddsNothing() {
        db.importHistoryFrom(source)

        val second = db.importHistoryFrom(source)

        assertEquals(0, second.totalInserted)
        assertEquals(2, db.getAllHrvRecords().size)
    }

    @Test
    fun importNeverCreatesSeedRows() {
        db.importHistoryFrom(source)

        val seedRows = db.readableDatabase
            .rawQuery("SELECT COUNT(*) FROM hrv_logs WHERE raw_hex = 'seed'", null)
            .use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(0, seedRows)
    }

    private fun hrv(deviceTime: String, hrvMs: Int, raw: String) = ContentValues().apply {
        put("device_time", deviceTime)
        put("hrv_ms", hrvMs)
        put("vascular_aging", 0)
        put("hr_bpm", 55)
        put("stress", 30)
        put("bp_sys", 0)
        put("bp_dia", 0)
        put("raw_hex", raw)
    }
}
