package com.jcwear.coach.domain.parity

import com.jcwear.coach.domain.sky.GlassRule
import com.jcwear.coach.domain.sky.GlassRule.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Lock phase 1: the glass decided on a rendered frame matches what tools/visual-loop/g2.js measured on the same render
 * (design-prototype/ps1, tw1: Coach at 05:20 and 20:30 on the six drawn days and the steady day).
 */
class GlassRuleParityTest {
    private val cards = listOf(Rect(12, 236, 400, 851))
    private val nav = Rect(0, 851, 412, 915)
    /** The sky text boxes g2.js measured: the read box ends at y 300 at 05:20 (PS1, room for a third line) and 290 at 20:30 (TW1). */
    private fun skyText(png: String) = listOf(Rect(20, 12, 262, 92), Rect(22, 108, 262, if (png.startsWith("tw1/")) 290 else 300))

    private fun frame(png: String): GlassRule.Frame {
        val (px, w, h) = Fixtures.png(File("../../design-prototype/$png"))
        return GlassRule.Frame(px, w, h)
    }

    private fun hex(s: String) = s.removePrefix("#").toInt(16)

    @Test
    fun theGlassMatchesTheMeasuredRenders() {
        val rows = Fixtures.tsv("glass_g2.tsv")
        assertEquals(13, rows.size)
        rows.forEach { r ->
            val png = r["png"]
            val d = GlassRule.decide(frame(png), cards, nav, skyText(png))
            val want = if (r["mode"] == "light") GlassRule.Mode.DAY else GlassRule.Mode.NIGHT
            assertEquals("mode $png", want, d.mode)
            assertEquals("glass strength $png", r.d("a"), d.alpha, 0.021)
            assertEquals("need day $png", r.d("needLight"), d.needDay, 0.021)
            assertEquals("need night $png", r.d("needDark"), d.needNight, 0.021)
            assertEquals("nav $png", r.d("navA"), d.navAlpha, 0.021)
            assertEquals("card primary $png", r.d("cardP"), d.cardPrimary, 0.05)
            assertEquals("card secondary $png", r.d("cardS"), d.cardSecondary, 0.05)
            assertEquals("nav text $png", r.d("navS"), d.navSecondary, 0.05)
            assertEquals("sky ink $png", if (r["skyMode"] == "light") GlassRule.Mode.DAY else GlassRule.Mode.NIGHT, d.skyMode)
            assertEquals("veil $png", r.d("scrimA"), d.veilAlpha, 0.021)
            assertEquals("sky text $png", r.d("sky"), d.sky, 0.05)
            assertEquals("button $png", hex(r["btnFill"]), d.button!!.fill)
            assertEquals("button contrast $png", r.d("btnC"), d.buttonContrast!!, 0.05)
            assertEquals("floor kept on the glass $png", true, d.cardSecondary >= GlassRule.FLOOR)
        }
    }
}
