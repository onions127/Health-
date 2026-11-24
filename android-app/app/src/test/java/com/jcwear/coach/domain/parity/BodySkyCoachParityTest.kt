package com.jcwear.coach.domain.parity

import com.jcwear.coach.domain.CoachRules
import com.jcwear.coach.domain.sky.BodyInputs
import com.jcwear.coach.domain.sky.BodySky
import com.jcwear.coach.domain.sky.SkyPalette
import com.jcwear.coach.domain.sky.SkyRegime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lock phase 1: BodySky and CoachRules give what the design prototype gives (body-sky.js, coach-rules.js) for every day of
 * the sample data at nine hours, the six drawn days, the first week and an empty body. Fixtures: tools/visual-loop/parity.js.
 */
class BodySkyCoachParityTest {

    private fun body(r: Fixtures.Row) = BodyInputs(
        readiness = r.dOrNull("readiness"), readinessUsual = r.dOrNull("readinessUsual"),
        sleepScore = r.dOrNull("sleepScore"), sleepMin = r.dOrNull("sleepMin"), sleepUsual = r.dOrNull("sleepUsual"),
        hrv = r.dOrNull("hrv"), hrvMin = r.dOrNull("hrvMin"), hrvMax = r.dOrNull("hrvMax"),
        steps = r.dOrNull("steps"), stepsUsual = r.dOrNull("stepsUsual"), strain = r.dOrNull("strain"), strainUsual = r.dOrNull("strainUsual"),
        skinTemp = r.dOrNull("skinTemp"), rhr = r.dOrNull("rhr"), rhrUsual = r.dOrNull("rhrUsual"),
        onsetUsual = r.dOrNull("onsetUsual"), nights = r.iOrNull("nights")
    )

    private val regimes = mapOf("firstRun" to SkyRegime.FIRST_RUN, "fair" to SkyRegime.FAIR, "partly" to SkyRegime.PARTLY,
        "stratocumulus" to SkyRegime.STRATOCUMULUS, "stratus" to SkyRegime.STRATUS)
    private val palettes = mapOf("none" to SkyPalette.NONE, "fog" to SkyPalette.FOG, "rain" to SkyPalette.RAIN,
        "storm" to SkyPalette.STORM, "overcast" to SkyPalette.OVERCAST)

    @Test
    fun bodySkyMatchesThePrototype() {
        val rows = Fixtures.tsv("body_sky.tsv")
        assertTrue(rows.size > 600)
        rows.forEach { row ->
            val res = BodySky.bodySky(body(row), row.d("hour"))
            val at = "at ${row["hour"]} for $row"
            assertEquals("regime $at", regimes[row["regime"]], res.regime)
            val s = res.sky
            val got = mapOf("style" to s.style, "paintCov" to s.paintCov, "cirrus" to s.cirrus, "ciHook" to s.ciHook, "ciVeil" to s.ciVeil,
                "ciAng" to s.ciAng, "cover" to s.cover, "tall" to s.tall, "grey" to s.grey, "haze" to s.haze, "sheet" to s.sheet,
                "sType" to s.sType, "sb" to s.sb, "st" to s.st, "fog" to s.fog, "fogH" to s.fogH, "rain" to s.rain, "wind" to s.wind,
                "snow" to s.snow, "bolt" to s.bolt, "palK" to s.palK)
            got.forEach { (k, v) -> assertEquals("$k $at", row.d(k), v, 1e-9) }
            assertEquals("pal $at", palettes[row["pal"]], s.pal)
            assertEquals("fog layer $at", row.d("lFog"), res.fog, 1e-9)
            listOf("lHrv" to res.hrv, "lActivity" to res.activity, "lLoad" to res.load, "lIll" to res.ill, "lStorm" to res.storm).forEach { (k, v) ->
                val want = row.dOrNull(k)
                if (want == null) assertEquals("$k $at", null, v) else assertEquals("$k $at", want, v!!, 1e-9)
            }
            assertEquals("talkback $at", row["talkback"], res.talkback)
        }
    }

    @Test
    fun coachReadMatchesThePrototype() {
        val rows = Fixtures.tsv("coach_rules.tsv")
        assertTrue(rows.size > 600)
        rows.forEach { row ->
            val got = CoachRules.read(body(row), row.d("hour"))
            val at = "at ${row["hour"]} for $row"
            assertEquals("rule $at", row["rule"], got.rule)
            assertEquals("line $at", row["line"], got.line)
            assertEquals("detail $at", row["detail"], got.detail)
        }
    }

    @Test
    fun everyRuleIsCovered() {
        val rules = Fixtures.tsv("coach_rules.tsv").map { it["rule"] }.toSet()
        assertEquals(35, rules.size)
    }
}
