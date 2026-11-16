package com.jcwear.coach

import com.jcwear.coach.ble.J2208Protocol
import com.jcwear.coach.domain.SleepAndReadinessEngine
import com.jcwear.coach.domain.buildDaySeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun sessionEndingNearNoonIsNotExtended() {
        // Raw 0x53 block 06:00 -> 11:59; steps and low HR after noon used to invent extra sleep.
        val stages = List(360) { if (it % 60 < 20) J2208Protocol.STAGE_DEEP else J2208Protocol.STAGE_LIGHT }
        val sleepBlock = J2208Protocol.SleepBlockRecord("2026-11-12T06:00:00", 1, 360, 120, 240, 0, 0, stages, "53")
        val steps = listOf(J2208Protocol.StepEpochRecord("2026-11-12T12:10:00", 0, 0.0, 0.0, List(10) { 0 }, ""))
        val hrs = listOf(
            J2208Protocol.HrRecord("2026-11-12T08:00:00", 55, ""),
            J2208Protocol.HrRecord("2026-11-12T12:15:00", 54, "")
        )
        val session = SleepAndReadinessEngine.stitchSleepSessions(listOf(sleepBlock)).single()
        assertEquals(360, session.totalSleepMin)
        assertEquals("2026-11-12T11:59", session.endTime.toString())

        val day = buildDaySeries(
            sleepBlocks = listOf(sleepBlock),
            hrvRecords = listOf(J2208Protocol.HrvRecord("2026-11-12T06:30:00", 68, 1, 54, 25, 115, 75, "")),
            hrRecords = hrs,
            spo2Records = listOf(J2208Protocol.Spo2Record("2026-11-12T06:00:00", 98, "")),
            tempRecords = listOf(J2208Protocol.TempRecord("2026-11-12T06:00:00", 36.3, "")),
            stepRecords = steps,
            today = LocalDate.of(2026, 11, 12)
        ).last()
        assertEquals(360, day.sleep?.totalSleepMin)
        assertEquals(68.0, day.hrvMs!!, 0.01)
        assertNull("No history yet, so no readiness", day.readiness)
    }
}
