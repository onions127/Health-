package com.jcwear.coach.domain.parity

import com.jcwear.coach.domain.SleepAndReadinessEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lock phase 1: the S2 sleep score gives what the design prototype gives (design-prototype/scores.js `sleepS2`) on every
 * night of the sample data and the four designed bad nights. Fixture: tools/visual-loop/parity.js → parity/sleep_s2.tsv.
 */
class SleepS2ParityTest {

    @Test
    fun everyPrototypeNightScoresTheSame() {
        val rows = Fixtures.tsv("sleep_s2.tsv")
        assertEquals(94, rows.size)
        rows.forEach { r ->
            val b = SleepAndReadinessEngine.sleepScoreParts(
                asleepMin = r.d("min"), restorativeShare = r.d("share"), efficiencyPct = r.d("eff"), offMin = r.d("off"),
                onsetMfn = r.i("onset"), stagesAvailable = true, priorNights = 14
            )
            val want = r["s2parts"].split(',').map { it.toDouble() }
            b.parts.zip(want).forEach { (part, w) -> assertEquals("${part.label} in $r", w, part.points, 1e-5) }
            assertEquals("score in $r", r.i("s2"), b.score)
        }
    }

    @Test
    fun theDesignedBadNightsGetTheirScores() {
        // VP1, ST1, BD2: haze 70, illness 56, very poor 42, storm 40 (the old formula gave 82–88 and 72–73)
        val want = listOf(70, 56, 42, 40)
        assertEquals(want, Fixtures.tsv("sleep_s2.tsv").takeLast(4).map { it.i("s2") })
    }
}
