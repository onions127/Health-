package com.jcwear.coach.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class MetricInsightsTest {
    private val today = LocalDate.of(2026, 11, 12)

    /** 20 prior days of [prior] values (cycled), then [todayValue] on today. */
    private fun hrvDays(prior: List<Double>, todayValue: Double?) =
        (20 downTo 1).map { DayMetrics(today.minusDays(it.toLong()), hrvMs = prior[it % prior.size]) } +
            DayMetrics(today, hrvMs = todayValue)

    @Test
    fun highOutlierIsCalledHighestNotLowest() {
        val days = hrvDays(listOf(55.0, 58.0, 60.0, 57.0), 80.0)
        assertEquals("Highest on record", MetricInsights.extremeLabel(Metrics.hrv, days, today))
        assertEquals(Status.GOOD, MetricInsights.status(Metrics.hrv, 80.0, MetricInsights.baseline(Metrics.hrv, days, today)!!))
    }

    @Test
    fun lowOutlierCountsBackToTheLastLowerDay() {
        val days = (30 downTo 1).map { d ->
            DayMetrics(today.minusDays(d.toLong()), hrvMs = if (d == 21) 30.0 else 58.0 + d % 3)
        } + DayMetrics(today, hrvMs = 40.0)
        assertEquals("Lowest in 3 weeks", MetricInsights.extremeLabel(Metrics.hrv, days, today))
    }

    @Test
    fun lowerRestingHrIsGood() {
        val days = (20 downTo 1).map { DayMetrics(today.minusDays(it.toLong()), rhrBpm = 55.0 + it % 2) } +
            DayMetrics(today, rhrBpm = 48.0)
        val base = MetricInsights.baseline(Metrics.rhr, days, today)!!
        assertEquals(Status.GOOD, MetricInsights.status(Metrics.rhr, 48.0, base))
        assertEquals(Status.BAD, MetricInsights.status(Metrics.rhr, 62.0, base))
    }

    @Test
    fun flatSeriesStaysNormalThanksToTheFloor() {
        val days = hrvDays(listOf(60.0), 62.0) // SD is 0; the 3 ms floor keeps 62 normal
        assertEquals("Within normal" to Status.NORMAL, MetricInsights.chip(Metrics.hrv, days, today))
        assertNull(MetricInsights.extremeLabel(Metrics.hrv, days, today))
    }

    @Test
    fun skinTempIsJudgedByDistanceFromZero() {
        val days = (20 downTo 1).map { DayMetrics(today.minusDays(it.toLong()), skinTempDeltaC = if (it % 2 == 0) 0.1 else -0.1) } +
            DayMetrics(today, skinTempDeltaC = -0.9)
        val base = MetricInsights.baseline(Metrics.skinTemp, days, today)!!
        assertEquals(Status.BAD, MetricInsights.status(Metrics.skinTemp, -0.9, base))
        assertEquals(Status.NORMAL, MetricInsights.status(Metrics.skinTemp, 0.05, base))
    }

    @Test
    fun strainIsNeverGoodOrBad() {
        val days = (20 downTo 1).map { DayMetrics(today.minusDays(it.toLong()), strain = 10.0) } + DayMetrics(today, strain = 20.0)
        val base = MetricInsights.baseline(Metrics.strain, days, today)!!
        assertEquals(Status.ABOVE, MetricInsights.status(Metrics.strain, 20.0 / 21 * 100, base))
    }

    @Test
    fun fewerThanSevenDaysShowsBaselineProgress() {
        val days = (4 downTo 1).map { DayMetrics(today.minusDays(it.toLong()), hrvMs = 60.0) } + DayMetrics(today, hrvMs = 70.0)
        assertEquals("Building your baseline: 4 of 7 days", MetricInsights.chip(Metrics.hrv, days, today).first)
    }

    @Test
    fun missingDaysAreNotCountedAsZero() {
        val days = (20 downTo 1).map { d -> DayMetrics(today.minusDays(d.toLong()), hrvMs = if (d % 2 == 0) 60.0 else null) } +
            DayMetrics(today, hrvMs = 60.0)
        assertEquals(60.0, MetricInsights.baseline(Metrics.hrv, days, today)!!.mean, 1e-9)
    }

    @Test
    fun greetingFollowsTheClock() {
        assertEquals("Good morning", greetingFor(LocalTime.of(5, 0)))
        assertEquals("Good morning", greetingFor(LocalTime.of(11, 59)))
        assertEquals("Good afternoon", greetingFor(LocalTime.of(12, 0)))
        assertEquals("Good evening", greetingFor(LocalTime.of(17, 0)))
        assertEquals("Hi", greetingFor(LocalTime.of(22, 0)))
        assertEquals("Hi", greetingFor(LocalTime.of(4, 59)))
        assertTrue(greetingFor(LocalTime.of(9, 0), "Stav").endsWith(", Stav"))
    }
}
