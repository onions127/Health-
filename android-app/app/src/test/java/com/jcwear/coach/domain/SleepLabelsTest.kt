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

    private fun block(start: String, min: Int, stage: Int = J2208Protocol.STAGE_LIGHT) =
        SleepBlockRecord(start, 1, min, 0, 0, 0, 0, List(min) { stage }, "t")

    @Test
    fun blocksUpTo90MinApartAreOneNightWithTheGapAwake() {
        // 00:00-02:00 asleep, 50 min no data, 02:50-06:00 asleep.
        val s = SleepAndReadinessEngine.stitchSleepSessions(listOf(block("2026-11-13T00:00:00", 120), block("2026-11-13T02:50:00", 190))).single()
        assertEquals(310, s.totalSleepMin)
        assertEquals(50, s.awakeMin)
        assertEquals(java.time.Duration.between(s.startTime, s.endTime).toMinutes() + 1, (s.totalSleepMin + s.awakeMin).toLong())
    }

    @Test
    fun blocksOver90MinApartAreTwoSessions() {
        val ss = SleepAndReadinessEngine.stitchSleepSessions(listOf(block("2026-11-12T21:00:00", 60), block("2026-11-12T23:40:00", 300)))
        assertEquals(2, ss.size)
    }

    @Test
    fun realNightOf17NovWithAwakePeriod() {
        // The band's blocks that night: 02:19-05:54 and 06:22-10:46, nothing in between.
        val s = SleepAndReadinessEngine.stitchSleepSessions(listOf(block("2026-11-17T02:19:01", 215), block("2026-11-17T06:22:01", 264))).single()
        val (on, off) = anchorEdit(s.bandStartTime, LocalTime.of(2, 0), LocalTime.of(10, 45))
        val awake = mergeAwake(listOf(anchorAwake(on, LocalTime.of(5, 20), LocalTime.of(6, 10))))
        assertEquals(EditCheck.Ok, checkEdit(on, off, awake))
        val l = labelFor(s, LabelKind.EDITED, on, off, utc, 0, awake)
        val edited = applyLabels(listOf(s), listOf(l), utc).single()
        assertEquals(475, edited.totalSleepMin) // 7h 55m
        assertEquals(50, edited.awakeMin)
        assertEquals(awake, edited.userAwake)
    }

    @Test
    fun editedNoDataMinutesOutsideAwakePeriodsCountAsAsleep() {
        val s = SleepAndReadinessEngine.stitchSleepSessions(listOf(block("2026-11-13T00:00:00", 60), block("2026-11-13T01:30:00", 60))).single()
        val edited = applyLabels(listOf(s), listOf(label(s, LabelKind.EDITED, "2026-11-13T00:00", "2026-11-13T02:30")), utc).single()
        assertEquals(150, edited.totalSleepMin)
        assertEquals(30, edited.unstagedMin)
        assertEquals(0, edited.awakeMin)
    }

    @Test
    fun awakeChecksAnchorAndMerge() {
        val on = t("2026-11-12T23:30")
        val off = t("2026-11-13T07:00")
        assertEquals(t("2026-11-13T01:00") to t("2026-11-13T01:30"), anchorAwake(on, LocalTime.of(1, 0), LocalTime.of(1, 30)))
        assertEquals(t("2026-11-12T23:50") to t("2026-11-13T00:20"), anchorAwake(on, LocalTime.of(23, 50), LocalTime.of(0, 20)))
        assertEquals(
            listOf(t("2026-11-13T01:00") to t("2026-11-13T02:00")),
            mergeAwake(listOf(t("2026-11-13T01:30") to t("2026-11-13T02:00"), t("2026-11-13T01:00") to t("2026-11-13T01:40")))
        )
        assertTrue(checkEdit(on, off, listOf(t("2026-11-13T06:30") to t("2026-11-13T07:30"))) is EditCheck.Invalid)
        assertEquals(EditCheck.Invalid("No sleep left in this night."), checkEdit(on, off, listOf(on to off)))
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
