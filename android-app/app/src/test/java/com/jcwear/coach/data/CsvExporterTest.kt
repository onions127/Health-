package com.jcwear.coach.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jcwear.coach.ble.J2208Protocol.HrRecord
import com.jcwear.coach.domain.DayMetrics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CsvExporterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = LocalHealthDatabase(context)
    private val dir = File(context.cacheDir, "exports")
    private val date = LocalDate.of(2026, 11, 16)

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("jcwear_local_health.db")
        dir.deleteRecursively()
    }

    private fun entries(zip: File) = ZipFile(zip).use { z ->
        z.entries().toList().associate { e -> e.name to z.getInputStream(e).readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun emptyDbStillExportsHeaders() {
        val files = entries(CsvExporter(db, dir).export(emptyList(), date))
        assertEquals((CsvExporter.TABLES.map { "$it.csv" } + "daily_summary.csv").toSet(), files.keys)
        assertEquals("device_time,bpm,raw_hex,uploaded\r\n", files["hr_logs.csv"])
        assertTrue(files["sleep_labels.csv"]!!.startsWith("session_key,onset,wake,kind,"))
        assertEquals(CsvExporter.SUMMARY_HEADER.joinToString(",") + "\r\n", files["daily_summary.csv"])
    }

    @Test
    fun rowsAndQuoting() {
        db.upsertHrRecords(listOf(HrRecord("2026-11-16T04:00:00", 50, "a,b \"c\"\nd"), HrRecord("2026-11-16T05:00:00", 52, "")))
        val days = listOf(DayMetrics(date, steps = 1200), DayMetrics(date.minusDays(1)))
        val files = entries(CsvExporter(db, dir).export(days, date))
        val hr = files["hr_logs.csv"]!!.split("\r\n").filter { it.isNotEmpty() }
        assertEquals(3, hr.size) // header + 2 (the quoted newline is \n, not \r\n)
        assertEquals("2026-11-16T04:00:00,50,\"a,b \"\"c\"\"\nd\",0", hr[1])
        assertEquals("2026-11-16T05:00:00,52,,0", hr[2])
        val summary = files["daily_summary.csv"]!!.split("\r\n").filter { it.isNotEmpty() }
        assertEquals(2, summary.size) // days without data are skipped
        assertTrue(summary[1].startsWith("2026-11-16,,,,"))
    }

    @Test
    fun failedExportKeepsThePreviousZip() {
        val good = CsvExporter(db, dir).export(emptyList(), date)
        val before = good.readBytes()
        db.close()
        context.deleteDatabase("jcwear_local_health.db")
        val broken = LocalHealthDatabase(context).apply { writableDatabase.execSQL("DROP TABLE hr_logs") }
        try {
            CsvExporter(broken, dir).export(emptyList(), date)
            fail("expected the missing table to fail the export")
        } catch (_: Exception) {
        }
        broken.close()
        assertTrue(before.contentEquals(good.readBytes()))
        assertFalse(dir.listFiles()!!.any { it.name.contains(".tmp-") })
    }

    @Test
    fun csvField() {
        assertEquals("", CsvExporter.csvField(null))
        assertEquals("plain", CsvExporter.csvField("plain"))
        assertEquals("\"x\r\ny\"", CsvExporter.csvField("x\r\ny"))
    }
}
