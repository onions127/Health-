package com.jcwear.coach.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jcwear.coach.ble.J2208Protocol.HrRecord
import com.jcwear.coach.ble.J2208Protocol.HrvRecord
import com.jcwear.coach.ble.J2208Protocol.SleepBlockRecord
import com.jcwear.coach.ble.J2208Protocol.Spo2Record
import com.jcwear.coach.ble.J2208Protocol.StepEpochRecord
import com.jcwear.coach.ble.J2208Protocol.TempRecord
import com.jcwear.coach.data.LocalHealthDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Phase 1a regression guard (plan D23): the values the dashboard derives from a fixed DB and a
 * fixed date must not change during the refactor. 1b changes them on purpose: delete the golden
 * file, re-run to re-record, and review the diff.
 *
 * The fixture is synthetic on purpose: the real band dump is personal data and stays out of git.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardStateGoldenTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = LocalHealthDatabase(context)
    private val golden = File("src/test/resources/golden/dashboard_state_pre1a.txt")

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("jcwear_local_health.db")
    }

    @Test
    fun dashboardStateMatchesGolden() {
        seedFixture(today = LocalDate.of(2026, 11, 12))

        val (reports, series) = buildDashboardState(db, morningRmssd = null, today = LocalDate.of(2026, 11, 12))
        val actual = (reports.map { it.toString() } + series.map { it.toString() }).joinToString("\n") + "\n"

        if (!golden.exists()) {
            golden.parentFile?.mkdirs()
            golden.writeText(actual)
            fail("Golden recorded at ${golden.path}; review it and re-run.")
        }
        assertEquals(golden.readText(), actual)
    }

    private fun seedFixture(today: LocalDate) {
        val hrv = mutableListOf<HrvRecord>()
        val hr = mutableListOf<HrRecord>()
        val spo2 = mutableListOf<Spo2Record>()
        val temp = mutableListOf<TempRecord>()
        val steps = mutableListOf<StepEpochRecord>()
        val sleep = mutableListOf<SleepBlockRecord>()
        for (d in 20 downTo 0) {
            val date = today.minusDays(d.toLong())
            val day = date.toString()
            hrv += HrvRecord("${day}T06:30:00", 55 + d % 7, 1, 52 + d % 3, 30, 0, 0, "fixture")
            hr += HrRecord("${day}T04:00:00", 50 + d % 4, "fixture")
            hr += HrRecord("${day}T14:00:00", 80 + d % 9, "fixture")
            spo2 += Spo2Record("${day}T03:00:00", 95 + d % 4, "fixture")
            temp += TempRecord("${day}T03:00:00", 36.0 + (d % 5) / 10.0, "fixture")
            steps += StepEpochRecord("${day}T18:00:00", 6000 + d * 150, 250.0, 4.5, List(10) { 60 }, "fixture")
            val stages = List(420) { i -> when (i % 90) { in 15..35 -> 1; in 65..85 -> 3; else -> 2 } }
            sleep += SleepBlockRecord(
                deviceTime = date.minusDays(1).atTime(23, (d % 6) * 10).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                unitMinutes = 1,
                count = stages.size,
                deepMin = stages.count { it == 1 },
                lightMin = stages.count { it == 2 },
                remMin = stages.count { it == 3 },
                awakeMin = 0,
                stages = stages,
                rawHex = "fixture"
            )
        }
        db.upsertHrvRecords(hrv)
        db.upsertHrRecords(hr)
        db.upsertSpo2Records(spo2)
        db.upsertTempRecords(temp)
        db.upsertStepRecords(steps)
        db.upsertSleepBlocks(sleep)
    }
}
