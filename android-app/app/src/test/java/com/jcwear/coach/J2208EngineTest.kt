package com.jcwear.coach

import com.jcwear.coach.ble.J2208Protocol
import com.jcwear.coach.domain.SleepAndReadinessEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class J2208EngineTest {

    @Test
    fun testProtocolChecksumAndAutoFrame() {
        val pkt = J2208Protocol.buildSetAutoIntervalFrame(
            metricCode = J2208Protocol.METRIC_HRV,
            intervalMinutes = 10,
            enabled = true
        )
        assertEquals(16, pkt.size)
        assertEquals(0x2A, pkt[0].toInt() and 0xFF)
        val expectedChecksum = (0 until 15).sumOf { pkt[it].toInt() and 0xFF } and 0xFF
        assertEquals(expectedChecksum, pkt[15].toInt() and 0xFF)
    }

    @Test
    fun testPost1200NoonSleepStitcherExtendsPastNoon() {
        // Raw 0x53 sleep block stops at 12:00 military time (06:00 -> 12:00 = 360 mins)
        val stages = List(360) { if (it % 60 < 20) J2208Protocol.STAGE_DEEP else J2208Protocol.STAGE_LIGHT }
        val sleepBlock = J2208Protocol.SleepBlockRecord(
            deviceTime = "2026-11-12T06:00:00",
            unitMinutes = 1,
            count = 360,
            deepMin = 120,
            lightMin = 240,
            remMin = 0,
            awakeMin = 0,
            stages = stages,
            rawHex = "53"
        )

        // User stays asleep past 12:00 noon until 13:10 (0 steps and low HR 54 bpm)
        val steps = listOf(
            J2208Protocol.StepEpochRecord("2026-11-12T12:10:00", 0, 0.0, 0.0, List(10) { 0 }, ""),
            J2208Protocol.StepEpochRecord("2026-11-12T12:30:00", 0, 0.0, 0.0, List(10) { 0 }, ""),
            J2208Protocol.StepEpochRecord("2026-11-12T12:50:00", 0, 0.0, 0.0, List(10) { 0 }, ""),
            J2208Protocol.StepEpochRecord("2026-11-12T13:10:00", 210, 9.0, 0.15, List(10) { 21 }, "")
        )
        val hrs = listOf(
            J2208Protocol.HrRecord("2026-11-12T08:00:00", 55, ""),
            J2208Protocol.HrRecord("2026-11-12T12:15:00", 54, ""),
            J2208Protocol.HrRecord("2026-11-12T12:45:00", 55, ""),
            J2208Protocol.HrRecord("2026-11-12T13:10:00", 85, "")
        )

        val stitched = SleepAndReadinessEngine.stitchSleepSessions(
            sleepBlocks = listOf(sleepBlock),
            stepRecords = steps,
            hrRecords = hrs
        )
        assertEquals(1, stitched.size)
        val session = stitched.first()
        assertTrue("Expected post-12:00 noon sleep extension > 45m, got ${session.post1200NoonExtendedMin}", session.post1200NoonExtendedMin >= 45)
        assertTrue(session.totalSleepMin >= 405)

        val report = SleepAndReadinessEngine.buildDailyReadinessReport(
            targetDate = LocalDate.of(2026, 11, 12),
            sleepSessions = stitched,
            hrvRecords = listOf(J2208Protocol.HrvRecord("2026-11-12T06:30:00", 68, 1, 54, 25, 115, 75, "")),
            hrRecords = hrs,
            spo2Records = listOf(J2208Protocol.Spo2Record("2026-11-12T06:00:00", 98, "")),
            tempRecords = listOf(J2208Protocol.TempRecord("2026-11-12T06:00:00", 36.3, "")),
            stepRecords = steps
        )
        assertTrue(report.readinessScore in 70..100)
        assertEquals(session.post1200NoonExtendedMin, report.post1200NoonExtendedMin)
    }
}
