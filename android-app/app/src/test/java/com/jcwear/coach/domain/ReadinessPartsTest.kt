package com.jcwear.coach.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/** R4: the Readiness breakdown gives exactly the old score, and its rows always add up to it. */
class ReadinessPartsTest {
    private val today = LocalDate.of(2026, 11, 17)

    @Test
    fun sameScoreAsBeforeOverAGrid() {
        var n = 0
        for (hrv in listOf(15.0, 31.5, 48.0, 62.3, 90.0, 200.0)) for (rhr in listOf(38.0, 47.5, 55.0, 61.2, 80.0, 120.0))
            for (hrvBase in listOf(0.5, 30.0, 58.4, 75.0)) for (rhrBase in listOf(45.0, 52.3, 60.0))
                for (sleep in listOf(null, 0, 43, 77, 100)) for (spo2 in listOf(null, 85.0, 93.4, 95.0, 99.0))
                    for (temp in listOf(null, -0.9, -0.5, 0.0, 0.51, 2.0)) {
                        val parts = readinessParts(hrv, rhr, hrvBase, rhrBase, sleep, spo2, temp)!!
                        assertEquals("hrv=$hrv rhr=$rhr $hrvBase $rhrBase $sleep $spo2 $temp",
                            legacyReadinessScore(hrv, rhr, hrvBase, rhrBase, sleep, spo2, temp), parts.score)
                        assertEquals(parts.score, parts.shownPoints().filterNotNull().sum())
                        n++
                    }
        assertTrue(n > 60000)
        assertNull(readinessParts(null, 50.0, 60.0, 50.0, 80, 96.0, 0.0))
        assertNull(readinessParts(60.0, 50.0, null, 50.0, 80, 96.0, 0.0))
    }

    @Test
    fun missingSleepAndVitalsAreGreyedAndLeftOut() {
        val b = readinessParts(62.0, 58.0, 58.0, 55.0, null, null, null, hrvNote = "morning check")!!
        assertEquals(listOf(true, true, false, false), b.parts.map { it.counted })
        assertEquals(0.65, b.divisor, 1e-9)
        assertEquals(listOf("Sleep", "Blood oxygen and skin temp"), b.parts.filter { !it.counted }.map { it.label })
        assertEquals("HRV 62 ms (morning check) vs your 58 ms", b.parts[0].detail)
        assertEquals("Resting HR 58, your average 55", b.parts[1].detail)
        val pts = b.shownPoints()
        assertNull(pts[2]); assertNull(pts[3])
        assertEquals(b.score, pts[0]!! + pts[1]!!)
    }

    @Test
    fun vitalsDetailIsUnitFree() {
        val b = readinessParts(60.0, 50.0, 60.0, 50.0, 80, 93.0, 0.8)!!
        assertEquals("SpO₂ 93% (below 95%) · skin temp far from your average", b.parts[3].detail)
        assertFalse(b.parts.any { it.detail?.contains("°") == true })
    }

    @Test
    fun missingLines() {
        fun d(ago: Int, hrv: Double? = 60.0, rhr: Double? = 50.0) = DayMetrics(today.minusDays(ago.toLong()), hrvMs = hrv, rhrBpm = rhr)
        val two = listOf(d(3, hrv = null), d(2), d(1), d(0))
        assertEquals("Building your average · 2 of 3 nights", readinessMissingLine(two.last(), two))
        assertEquals("No HRV or resting HR for this day", readinessMissingLine(d(0, rhr = null), two))
        val scored = d(0).copy(readinessParts = readinessParts(60.0, 50.0, 60.0, 50.0, null, null, null))
        assertNull(readinessMissingLine(scored, two))
        assertNull(partsFor("hrv", scored))
        assertEquals(scored.readinessParts, partsFor("readiness", scored))
    }
}

/** The Readiness expression as it was before R4, kept as the equality guard. Needs HRV and resting HR against at least 3 prior days; sleep and vitals count when present. */
private fun legacyReadinessScore(
    hrv: Double?, rhr: Double?, hrvBase: Double?, rhrBase: Double?,
    sleepScore: Int?, spo2: Double?, tempDelta: Double?
): Int? {
    if (hrv == null || rhr == null || hrvBase == null || rhrBase == null) return null
    val parts = mutableListOf(
        0.40 to (75.0 + (hrv / hrvBase.coerceAtLeast(1.0) - 1.0) * 125.0).coerceIn(20.0, 100.0),
        0.25 to (78.0 + (rhrBase - rhr) * 4.5).coerceIn(20.0, 100.0)
    )
    if (sleepScore != null) parts += 0.25 to sleepScore.toDouble()
    if (spo2 != null || tempDelta != null) {
        val penalty = (if (tempDelta != null && abs(tempDelta) > 0.5) 25.0 else 0.0) +
            (if (spo2 != null && spo2 < 95.0) (95.0 - spo2) * 8.0 else 0.0)
        parts += 0.10 to (95.0 - penalty).coerceIn(20.0, 100.0)
    }
    return (parts.sumOf { it.first * it.second } / parts.sumOf { it.first }).roundToInt().coerceIn(1, 100)
}
