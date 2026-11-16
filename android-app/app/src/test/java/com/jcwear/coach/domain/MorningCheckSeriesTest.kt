package com.jcwear.coach.domain

import com.jcwear.coach.ble.J2208Protocol.HrRecord
import com.jcwear.coach.ble.J2208Protocol.HrvRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** R1 (gate decision T1a): a morning check feeds only its own day's scores; trend and baselines stay overnight. */
class MorningCheckSeriesTest {
    private val today = LocalDate.of(2026, 11, 17)
    private val zone = ZoneId.of("Europe/Sofia")

    private fun check(ms: Double, source: String = MorningCheck.SOURCE_HRV) = MorningCheck(ms, 0L, source)

    /** 10 days of overnight HRV 60 and resting HR 50; [skipHrvOn] leaves that day without overnight HRV. */
    private fun series(checks: Map<LocalDate, MorningCheck>, skipHrvOn: LocalDate? = null): List<DayMetrics> {
        val days = (9 downTo 0).map { today.minusDays(it.toLong()) }
        return buildDaySeries(
            sleepBlocks = emptyList(),
            hrvRecords = days.filter { it != skipHrvOn }.map { HrvRecord("${it}T04:00:00", 60, 1, 50, 30, 0, 0, "t") },
            hrRecords = days.map { HrRecord("${it}T04:00:00", 50, "t") },
            spo2Records = emptyList(), tempRecords = emptyList(), stepRecords = emptyList(),
            today = today, morningChecks = checks
        )
    }

    @Test
    fun checkChangesThatDaysScoresAndNoOtherDays() {
        val plain = series(emptyMap())
        val past = today.minusDays(3)
        val withCheck = series(mapOf(past to check(90.0)))
        plain.zip(withCheck).forEach { (a, b) ->
            if (a.date == past) {
                assertNotEquals(a.recovery, b.recovery)
                assertNotEquals(a.readiness, b.readiness)
                assertEquals(90.0, b.scoreHrvMs!!, 0.0)
            } else {
                assertEquals("day ${a.date} must not move", a, b)
            }
        }
    }

    @Test
    fun trendValueAndBaselinesStayOvernight() {
        val past = today.minusDays(3)
        val days = series(mapOf(past to check(90.0)))
        val day = days.single { it.date == past }
        assertEquals(60.0, day.hrvMs!!, 0.0)
        assertEquals(60.0, Metrics.hrv.extract(day)!!, 0.0)
        assertEquals(60.0, MetricInsights.baseline(Metrics.hrv, days, today)!!.mean, 0.0)
    }

    @Test
    fun checkWithoutOvernightHrvStillScoresTheDay() {
        val day = series(mapOf(today to check(64.0)), skipHrvOn = today).last()
        assertNull(day.hrvMs)
        assertNull("no point on the HRV trend", Metrics.hrv.extract(day))
        assertNotNull(day.recovery)
        assertNotNull(day.readiness)
        assertTrue(day.hasData)
    }

    @Test
    fun checkAloneCountsAsData() {
        assertTrue(DayMetrics(today, morningCheck = check(64.0)).hasData)
    }

    @Test
    fun outOfRangeStoredValueIsIgnored() {
        val day = series(mapOf(today to check(250.0))).last()
        assertNull(day.morningCheck)
        assertEquals(60.0, day.scoreHrvMs!!, 0.0)
    }

    @Test
    fun sameInputsGiveSameScoresAfterARestart() {
        val checks = mapOf(today to check(71.0))
        assertEquals(series(checks), series(checks))
    }

    @Test
    fun checkIsKeyedToTheLocalDayItFinishedOn() {
        fun at(h: Int, m: Int, d: LocalDate) = ZonedDateTime.of(d.atTime(h, m), zone).toInstant().toEpochMilli()
        assertEquals(today, localDateOf(at(23, 59, today), zone))
        assertEquals(today.plusDays(1), localDateOf(at(0, 10, today.plusDays(1)), zone))
        // The same instant is still "yesterday" further west.
        assertEquals(today, localDateOf(at(0, 10, today.plusDays(1)), ZoneId.of("America/Los_Angeles")))
    }

    @Test
    fun doneLineAndHeaderText() {
        val at = ZonedDateTime.of(today.atTime(7, 12), zone).toInstant().toEpochMilli()
        assertEquals("Morning check · 07:12 · HRV 62 ms", morningCheckDoneLine(MorningCheck(62.4, at, MorningCheck.SOURCE_HRV), zone))
        assertEquals(
            "Morning check · 07:12 · HRV 48 ms · estimated",
            morningCheckDoneLine(MorningCheck(48.0, at, MorningCheck.SOURCE_HR_ESTIMATE), zone)
        )
        assertEquals(
            "Overnight 55 ms · Morning check 62 ms (used for that day's scores)",
            hrvCheckHeader(DayMetrics(today, hrvMs = 55.0, morningCheck = check(62.0)))
        )
        assertEquals("Morning check 62 ms (used for that day's scores)", hrvCheckHeader(DayMetrics(today, morningCheck = check(62.0))))
        assertNull(hrvCheckHeader(DayMetrics(today, hrvMs = 55.0)))
    }
}
