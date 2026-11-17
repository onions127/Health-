package com.jcwear.coach.domain

import com.jcwear.coach.ble.J2208Protocol.HrRecord
import com.jcwear.coach.ble.J2208Protocol.HrvRecord
import com.jcwear.coach.ble.J2208Protocol.TempRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** U and R3: one unit setting for °F and lb; skin temp shows the real number against a 3-day-minimum average. */
class SkinTempAndUnitsTest {
    private val today = LocalDate.of(2026, 11, 17)

    /** HRV 60 and resting HR 50 for 10 days (so Readiness exists); [temps] maps days-ago to that day's readings. */
    private fun series(temps: Map<Int, List<Double>>): List<DayMetrics> {
        val days = (9 downTo 0).map { today.minusDays(it.toLong()) }
        return buildDaySeries(
            sleepBlocks = emptyList(),
            hrvRecords = days.map { HrvRecord("${it}T04:00:00", 60, 1, 50, 30, 0, 0, "t") },
            hrRecords = days.map { HrRecord("${it}T04:00:00", 50, "t") },
            spo2Records = emptyList(),
            tempRecords = temps.flatMap { (ago, v) -> v.mapIndexed { i, t -> TempRecord("${today.minusDays(ago.toLong())}T0$i:00:00", t, "t") } },
            stepRecords = emptyList(), today = today
        )
    }

    @Test
    fun unitConversions() {
        assertEquals("94.3 °F", Units.temp(34.6, UnitSystem.IMPERIAL))
        assertEquals("34.6 °C", Units.temp(34.6, UnitSystem.SI))
        assertEquals("+0.4", Units.tempDiff(0.2, UnitSystem.IMPERIAL))
        assertEquals("+0.0", Units.tempDiff(-0.04, UnitSystem.SI))
        assertEquals("176.4 lb", Units.weight(80.0, UnitSystem.IMPERIAL))
    }

    @Test
    fun averageBaselineAndDifference() {
        // Baseline is the mean of day averages (34.0, 34.4, 34.8), not of all readings.
        val day = series(mapOf(3 to listOf(34.0), 2 to listOf(34.4), 1 to listOf(34.6, 34.8, 35.0), 0 to listOf(34.5, 34.7))).last()
        assertEquals(34.6, day.skinTempC!!, 1e-9)
        assertEquals(34.4, day.skinTempBaseC!!, 1e-9)
        assertEquals(3, day.skinTempBaseDays)
        assertEquals(0.2, day.skinTempDeltaC!!, 1e-9)
        assertEquals("+0.2 vs your average 34.4", skinTempLine(day, UnitSystem.SI))
        assertEquals("34.6 °C", Metrics.skinTemp.valueText(day, UnitSystem.SI))
    }

    @Test
    fun twoBaselineDaysGiveNoDifferenceAndNoReadinessPenalty() {
        // Today is 2 °C above the two earlier nights: with a baseline that would cost Readiness 25 vitals points.
        val hot = series(mapOf(2 to listOf(34.0), 1 to listOf(34.0), 0 to listOf(36.0))).last()
        val none = series(emptyMap()).last()
        assertEquals(36.0, hot.skinTempC!!, 1e-9)
        assertNull(hot.skinTempBaseC)
        assertNull(hot.skinTempDeltaC)
        assertEquals("Building your average · 2 of 3 nights", skinTempLine(hot, UnitSystem.SI))
        assertNotNull(none.readiness)
        assertEquals(none.readiness, hot.readiness)

        val penalised = series(mapOf(3 to listOf(34.0), 2 to listOf(34.0), 1 to listOf(34.0), 0 to listOf(36.0))).last()
        assertEquals(2.0, penalised.skinTempDeltaC!!, 1e-9)
        assertEquals(true, penalised.readiness!! < none.readiness!!)
    }

    @Test
    fun noReadingAndChip() {
        val days = series(mapOf(3 to listOf(34.0), 2 to listOf(34.4), 1 to listOf(34.8)))
        assertEquals("No reading", skinTempLine(days.last(), UnitSystem.SI))
        assertNull(Metrics.skinTemp.valueText(days.last(), UnitSystem.SI))
        assertEquals("No reading" to Status.NORMAL, MetricInsights.chip(Metrics.skinTemp, days, today, UnitSystem.SI))
    }

    @Test
    fun imperialShowsNoCelsiusOrKilograms() {
        val days = series(mapOf(3 to listOf(34.0), 2 to listOf(34.4), 1 to listOf(34.8), 0 to listOf(34.6)))
        val day = days.last()
        val u = UnitSystem.IMPERIAL
        val shown = listOf(
            Metrics.skinTemp.valueText(day, u)!!, Metrics.skinTemp.format(day.skinTempDeltaC!!, u), skinTempLine(day, u),
            MetricInsights.chip(Metrics.skinTemp, days, today, u).first, Units.temp(day.skinTempBaseC!!, u), Units.weight(80.0, u)
        )
        shown.forEach { assertFalse(it, it.contains("°C") || it.contains("kg")) }
        assertEquals("94.3 °F", shown[0])
        assertEquals("+0.4 vs your average 93.9", shown[2])
    }

    @Test
    fun differenceLineMatchesTheTwoNumbersShown() {
        // 36.36 against 36.32 is a 0.04 difference, but the screen shows 36.4 and 36.3.
        val day = DayMetrics(today, skinTempC = 36.36, skinTempBaseC = 36.32, skinTempBaseDays = 16, skinTempDeltaC = 0.04)
        assertEquals("+0.1 vs your average 36.3", skinTempLine(day, UnitSystem.SI))
    }
}
