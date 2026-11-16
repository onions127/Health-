package com.jcwear.coach.domain

import com.jcwear.coach.ble.J2208Protocol
import com.jcwear.coach.ble.J2208Protocol.SleepBlockRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/** Plan 1.4 goldens: realistic stages (~20% deep, ~22% REM, ~92% efficiency). */
class SleepScoreTest {

    private fun night(start: String, minutes: Int): SleepAndReadinessEngine.StitchedSleepSession {
        val stages = List(minutes) { i ->
            when (i % 100) {
                in 0..19 -> J2208Protocol.STAGE_DEEP
                in 20..41 -> J2208Protocol.STAGE_REM
                in 42..49 -> J2208Protocol.STAGE_AWAKE
                else -> J2208Protocol.STAGE_LIGHT
            }
        }
        val block = SleepBlockRecord(start, 1, minutes, 0, 0, 0, 0, stages, "test")
        return SleepAndReadinessEngine.stitchSleepSessions(listOf(block)).single()
    }

    private fun mfn(h: Int, m: Int) = SleepAndReadinessEngine.minutesFromNoon(LocalTime.of(h, m))

    @Test
    fun regularNightScoresHigh() {
        val s = night("2026-11-11T23:00:00", 450)
        val history = List(14) { mfn(2, 45) } // 23:00-06:30 midpoint
        assertTrue(SleepAndReadinessEngine.sleepScore(s, history) >= 85)
    }

    @Test
    fun threeAmBedtimeIsCappedAt70() {
        val s = night("2026-11-12T03:15:00", 450)
        assertTrue(SleepAndReadinessEngine.sleepScore(s, emptyList()) <= 70)
    }

    @Test
    fun shortNightScoresLowerThanFullNight() {
        val full = SleepAndReadinessEngine.sleepScore(night("2026-11-11T23:00:00", 450), emptyList())
        val short = SleepAndReadinessEngine.sleepScore(night("2026-11-11T23:00:00", 300), emptyList())
        assertTrue(short < full)
    }

    @Test
    fun irregularNightLosesRegularityPoints() {
        val s = night("2026-11-11T23:00:00", 450)
        val regular = SleepAndReadinessEngine.sleepScore(s, List(14) { mfn(2, 45) })
        val shifted = SleepAndReadinessEngine.sleepScore(s, List(14) { mfn(7, 0) })
        assertTrue(shifted < regular)
    }

    @Test
    fun midnightMidpointsSitNextToEachOther() {
        assertEquals(710, mfn(23, 50))
        assertEquals(730, mfn(0, 10))
    }

    @Test
    fun napsAreNotMainSleep() {
        assertTrue(!SleepAndReadinessEngine.isMainSleepCandidate(night("2026-11-12T14:00:00", 60)))
        assertTrue(SleepAndReadinessEngine.isMainSleepCandidate(night("2026-11-12T01:30:00", 300)))
    }

    @Test
    fun overlappingBlocksWithDifferentSecondsAreNotCountedTwice() {
        val a = SleepBlockRecord("2026-11-12T02:24:01", 1, 120, 0, 120, 0, 0, List(120) { 2 }, "a")
        val b = SleepBlockRecord("2026-11-12T04:23:01", 1, 60, 0, 60, 0, 0, List(60) { 2 }, "b")
        val s = SleepAndReadinessEngine.stitchSleepSessions(listOf(a, b)).single()
        assertEquals(179, s.totalSleepMin) // 02:24-05:22, one shared minute
        assertEquals(LocalDateTime.of(2026, 11, 12, 2, 24), s.startTime)
    }

    @Test
    fun cappedLateNightBreakdownMatchesScore() {
        val s = night("2026-11-12T03:15:00", 450)
        val b = SleepAndReadinessEngine.sleepScoreParts(s, emptyList())
        assertEquals(70, b.cap)
        assertTrue(b.parts.sumOf { it.points } > 70) // the cap is what limits it
        assertEquals(SleepAndReadinessEngine.sleepScore(s, emptyList()), b.score)
        assertEquals(5, b.parts.size)
        assertEquals(1.0, b.parts.sumOf { it.weight }, 1e-9)
        assertEquals("Last night + 0 nights for your routine", b.basis)
    }

    @Test
    fun lightOnlyBreakdownDropsRestorative() {
        val light = SleepAndReadinessEngine.stitchSleepSessions(
            listOf(SleepBlockRecord("2026-11-11T23:00:00", 1, 450, 0, 0, 0, 0, List(450) { J2208Protocol.STAGE_LIGHT }, "t"))
        ).single()
        val b = SleepAndReadinessEngine.sleepScoreParts(light, listOf(mfn(2, 45)))
        assertEquals(listOf("Duration", "Efficiency", "Regular timing", "Asleep by 02:00"), b.parts.map { it.label })
        assertEquals(1.0, b.parts.sumOf { it.weight }, 1e-9)
        assertEquals("Last night + 1 nights for your routine", b.basis)
        assertEquals(listOf("No sleep stages this night"), com.jcwear.coach.ui.sleepNotes(light))
        assertEquals(listOf("Confirmed by you"), com.jcwear.coach.ui.sleepNotes(night("2026-11-11T23:00:00", 450).copy(confirmed = true)))
    }

    @Test
    fun recoveryAndDailyParts() {
        assertEquals(85, recoveryParts(63.0, 52.0).score) // 57.96 + 27.36
        assertEquals(76, dailyParts(80, 70, 78).score)
    }
}
