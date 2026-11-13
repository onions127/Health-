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
        return SleepAndReadinessEngine.stitchSleepSessions(listOf(block), emptyList(), emptyList()).single()
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
        val s = SleepAndReadinessEngine.stitchSleepSessions(listOf(a, b), emptyList(), emptyList()).single()
        assertEquals(179, s.totalSleepMin) // 02:24-05:22, one shared minute
        assertEquals(LocalDateTime.of(2026, 11, 12, 2, 24), s.startTime)
    }
}
