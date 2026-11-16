package com.jcwear.coach.notify

import com.jcwear.coach.ble.J2208Protocol
import com.jcwear.coach.ble.J2208Protocol.SleepBlockRecord
import com.jcwear.coach.domain.DayMetrics
import com.jcwear.coach.domain.SleepAndReadinessEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class MorningNotifierTest {
    // Night 00:00 -> 06:39 on 16 Nov (400 epochs).
    private val night = SleepAndReadinessEngine.stitchSleepSessions(
        listOf(SleepBlockRecord("2026-11-16T00:00:00", 1, 400, 0, 0, 0, 0, List(400) { J2208Protocol.STAGE_LIGHT }, "t"))
    ).single()
    private val today = DayMetrics(LocalDate.of(2026, 11, 16), sleep = night, sleepScore = 74)
    private val at0800 = LocalDateTime.parse("2026-11-16T08:00")

    private fun reason(
        syncOk: Boolean = true, enabled: Boolean = true, now: LocalDateTime = at0800,
        last: String = "", day: DayMetrics? = today, lastBlock: Int? = 40
    ) = MorningNotifier.skipReason(syncOk, enabled, now, last, day, lastBlock)

    @Test
    fun decisionTable() {
        assertEquals(null, reason())
        assertEquals("sync_failed", reason(syncOk = false))
        assertEquals("toggle_off", reason(enabled = false))
        assertEquals("outside_window", reason(now = LocalDateTime.parse("2026-11-16T12:30")))
        assertEquals("outside_window", reason(now = LocalDateTime.parse("2026-11-16T04:59")))
        assertEquals("already_sent", reason(last = "2026-11-16"))
        assertEquals(null, reason(last = "2026-11-15"))
        assertEquals("no_sleep", reason(day = DayMetrics(LocalDate.of(2026, 11, 16))))
        assertEquals("no_sleep", reason(day = null))
        // Last epoch 06:39: 07:20 is only 41 min later.
        assertEquals("night_open", reason(now = LocalDateTime.parse("2026-11-16T07:20")))
        // A full 120-epoch last block may still be recording.
        assertEquals("night_open", reason(lastBlock = 120))
        assertEquals(null, reason(lastBlock = null))
    }

    @Test
    fun text() {
        assertEquals("Sleep 6h 40m · Score 74", MorningNotifier.text(today))
        assertEquals(
            "Daily 82 · Well recovered. Good day for hard training.",
            MorningNotifier.text(today.copy(daily = 82, recovery = 80))
        )
    }
}
