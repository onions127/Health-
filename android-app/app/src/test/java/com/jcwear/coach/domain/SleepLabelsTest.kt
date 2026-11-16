package com.jcwear.coach.domain

import com.jcwear.coach.ble.J2208Protocol
import com.jcwear.coach.ble.J2208Protocol.SleepBlockRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class SleepLabelsTest {
    private val utc = ZoneOffset.UTC

    private fun night(start: String, stages: List<Int>) =
        SleepAndReadinessEngine.stitchSleepSessions(listOf(SleepBlockRecord(start, 1, stages.size, 0, 0, 0, 0, stages, "t"))).single()

    private fun t(s: String) = LocalDateTime.parse(s)

    private fun label(s: SleepAndReadinessEngine.StitchedSleepSession, kind: LabelKind, onset: String, wake: String) = SleepLabel(
        s.sessionKey, t(onset).atOffset(utc), t(wake).atOffset(utc), kind,
        s.bandStartTime.atOffset(utc), s.bandEndTime.atOffset(utc), Provenance.BAND, 0
    )

    @Test
    fun anchorWakeNextDay() {
        val (on, wake) = anchorEdit(t("2026-11-13T00:05"), LocalTime.of(23, 30), LocalTime.of(6, 0))
        assertEquals(t("2026-11-12T23:30"), on)
        assertEquals(t("2026-11-13T06:00"), wake)
    }

    @Test
    fun anchorSameDay() {
        val (on, wake) = anchorEdit(t("2026-11-13T00:05"), LocalTime.of(1, 30), LocalTime.of(5, 50))
        assertEquals(t("2026-11-13T01:30"), on)
        assertEquals(t("2026-11-13T05:50"), wake)
    }

    @Test
    fun editChecks() {
        assertEquals(EditCheck.Ok, checkEdit(t("2026-11-13T01:30"), t("2026-11-13T05:50")))
        assertTrue(checkEdit(t("2026-11-13T17:00"), t("2026-11-14T01:00")) is EditCheck.Confirm)
        assertEquals(
            EditCheck.Invalid("Night is 17h 10m; maximum is 16h."),
            checkEdit(t("2026-11-12T20:00"), t("2026-11-13T13:10"))
        )
        assertTrue(checkEdit(t("2026-11-13T05:00"), t("2026-11-13T05:00")) is EditCheck.Invalid)
    }

    @Test
    fun editedNightUsesUserWindowWithoutInventingStages() {
        // Band: 00:05 + 60 min light, 60 min deep. User: 23:30 -> 01:00.
        val s = night("2026-11-13T00:05:00", List(60) { J2208Protocol.STAGE_LIGHT } + List(60) { J2208Protocol.STAGE_DEEP })
        val edited = applyLabels(listOf(s), listOf(label(s, LabelKind.EDITED, "2026-11-12T23:30", "2026-11-13T01:00")), utc).single()
        assertEquals(Provenance.EDITED, edited.provenance)
        assertEquals(90, edited.totalSleepMin) // matches what the user entered
        assertEquals(35, edited.unstagedMin)
        assertEquals(55, edited.lightMin)
        assertEquals(0, edited.deepMin)
        assertEquals(s.sessionKey, edited.sessionKey) // key stays the band's
        assertEquals(120, edited.bandTotalSleepMin)
    }

    @Test
    fun confirmedKeepsTimes() {
        val s = night("2026-11-13T00:05:00", List(60) { J2208Protocol.STAGE_LIGHT })
        val c = applyLabels(listOf(s), listOf(label(s, LabelKind.CONFIRMED, "2026-11-13T00:05", "2026-11-13T01:04")), utc).single()
        assertTrue(c.confirmed)
        assertEquals(Provenance.BAND, c.provenance)
        assertEquals(s.totalSleepMin, c.totalSleepMin)
    }

    @Test
    fun labelWithMovedKeyMatchesByOverlap() {
        val s = night("2026-11-13T00:05:00", List(120) { J2208Protocol.STAGE_LIGHT })
        val l = label(s, LabelKind.EDITED, "2026-11-13T00:30", "2026-11-13T01:30").copy(sessionKey = "2026-11-12T23:40")
        assertEquals(Provenance.EDITED, applyLabels(listOf(s), listOf(l), utc).single().provenance)
    }

    @Test
    fun dstFallBackLabelRoundTrips() {
        val zone = ZoneId.of("Europe/Berlin") // 2026-10-25 03:00 -> 02:00
        val onset = LocalDateTime.parse("2026-10-25T02:30").atZone(zone).withEarlierOffsetAtOverlap().toOffsetDateTime()
        val later = LocalDateTime.parse("2026-10-25T02:30").atZone(zone).withLaterOffsetAtOverlap().toOffsetDateTime()
        assertFalse(onset.isEqual(later))
        assertEquals(onset, java.time.OffsetDateTime.parse(onset.toString()))
    }

    @Test
    fun lightOnlyNightDropsRestorativeAndReweights() {
        val light = night("2026-11-11T23:00:00", List(450) { J2208Protocol.STAGE_LIGHT })
        assertFalse(light.stagesAvailable)
        // duration 100, efficiency 100, regularity 100 (no history), timing 100 -> 100, not 80.
        assertEquals(100, SleepAndReadinessEngine.sleepScore(light, emptyList()))
        val late = night("2026-11-12T03:15:00", List(450) { J2208Protocol.STAGE_LIGHT })
        assertTrue(SleepAndReadinessEngine.sleepScore(late, emptyList()) <= 70)
    }

    @Test
    fun fiveMinuteRecordsAreNotSleep() {
        // Real 12 Nov: a 5-min record at 00:59 padded the night that really starts 02:24.
        val junk = SleepBlockRecord("2026-11-12T00:59:38", 5, 24, 0, 0, 0, 0, listOf(0, 215, 1, 11, 0, 60, 47, 36, 26, 25, 0, 0, 0, 0, 0, 82, 37, 0, 38, 17, 18, 0, 22, 72), "t")
        val real = SleepBlockRecord("2026-11-12T02:24:01", 1, 60, 0, 0, 0, 0, List(60) { J2208Protocol.STAGE_LIGHT }, "t")
        val s = SleepAndReadinessEngine.stitchSleepSessions(listOf(junk, real)).single()
        assertEquals(t("2026-11-12T02:24"), s.startTime)
        assertEquals(60, s.totalSleepMin)
    }
}
