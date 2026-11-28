package com.jcwear.coach.domain.parity

import com.jcwear.coach.domain.sky.SceneParams
import com.jcwear.coach.domain.sky.SkyPalette
import com.jcwear.coach.domain.sky.SkyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Lock phase 3: the renderer's inputs. For 257 skies (every preset at 17 hours, and a sample of BodySky's skies) the uniforms
 * SceneParams gives match what the prototype's scene-gl.js set (tools/visual-loop/sky-parity.js), and the maples' branch
 * texture matches the prototype's.
 */
class SceneParamsParityTest {
    private fun close(a: Double, b: Double) = abs(a - b) <= 1e-6 * max(1.0, abs(b))

    @Test
    fun theUniformsMatchThePrototype() {
        val cases = Fixtures.tsv("scene_cases.tsv")
        assertEquals(257, cases.size)
        val rows = File("src/test/resources/parity/scene_uniforms.tsv").readLines().drop(1).filter { it.isNotBlank() }.map { it.split('\t') }
        val static = rows.filter { it[0] == "static" }
        val byCase = rows.filter { it[0] != "static" }.groupBy { it[0].toInt() }
        var checked = 0; val bad = ArrayList<String>()
        cases.forEachIndexed { i, c ->
            val sky = SkyState(
                style = c.d("style"), paintCov = c.d("paintCov"), cirrus = c.d("cirrus"), ciHook = c.d("ciHook"), ciVeil = c.d("ciVeil"), ciAng = c.d("ciAng"),
                cover = c.d("cover"), tall = c.d("tall"), grey = c.d("grey"), haze = c.d("haze"), sheet = c.d("sheet"), sType = c.d("sType"), sb = c.d("sb"), st = c.d("st"),
                fog = c.d("fog"), fogH = c.d("fogH"), rain = c.d("rain"), wind = c.d("wind"), snow = c.d("snow"), bolt = c.d("bolt"),
                pal = when (c["pal"]) { "fog" -> SkyPalette.FOG; "rain" -> SkyPalette.RAIN; "storm" -> SkyPalette.STORM; "overcast" -> SkyPalette.OVERCAST; else -> SkyPalette.NONE },
                palK = c.d("palK"))
            val o = SceneParams.build(SceneParams.Input(sky, c.d("hour"), c.i("seed"), c.d("doy"), scroll = c.d("scroll"), dpr = c.d("dpr"), w = 412, h = 915, live = false))
            fun mine(prog: String, n: String): DoubleArray? = when (prog) {
                "SKY", "LAND" -> o.scene[n] ?: o.ints[n]?.map { it.toDouble() }?.toDoubleArray()
                "COMPOSE" -> o.compose[n]; "RAYS" -> o.rays[n]; "FINAL" -> o.final[n]; else -> null
            }
            for (r in (byCase[i] ?: emptyList()) + static) {
                val want = r[3].split(',').map { it.toDouble() }; val got = mine(r[1], r[2])
                if (got == null) { bad += "case $i ${r[1]} ${r[2]}: missing"; continue }
                if (got.size != want.size || want.indices.any { !close(got[it], want[it]) })
                    bad += "case $i (${c["hour"]} h, ${c["pal"]}) ${r[1]} ${r[2]}: ${got.joinToString(",")} != ${r[3]}"
                checked++
            }
            // nothing extra: every uniform the prototype set for this sky's rays pass, and only those
            assertEquals("rays pass on, case $i", (byCase[i] ?: emptyList()).any { it[1] == "RAYS" }, o.raysOn)
        }
        if (bad.isNotEmpty()) fail("${bad.size} of $checked differ:\n" + bad.take(30).joinToString("\n"))
        assertTrue(checked > 20000)
    }

    /** Bakes (RP3): the two keyframes around an hour and the blend weight, as the prototype's bracket() (light projection). */
    @Test
    fun theBakeBlendMatchesThePrototype() {
        val rows = File("src/test/resources/parity/bake_bracket.tsv").readLines().drop(1).filter { it.isNotBlank() }.map { r -> r.split('	').map { it.toDouble() } }
        assertEquals(103, rows.size)
        for (r in rows) {
            val (a, b, w) = SceneParams.bracket(r[0])
            assertEquals("hour ${r[0]} from", r[1], a, 0.0); assertEquals("hour ${r[0]} to", r[2], b, 0.0)
            assertEquals("hour ${r[0]} weight", r[3], w, 1e-9)
        }
    }

    @Test
    fun theMaplesGrowAsInThePrototype() {
        val want = File("src/test/resources/parity/scene_tree.tsv").readText().trim().split(',').map { it.toDouble() }
        val got = SceneParams.treeTexture
        assertEquals(want.size, got.size)
        val off = want.indices.filter { abs(got[it] - want[it]) > 1e-6 }
        assertTrue("${off.size} texels differ, first at ${off.firstOrNull()}: ${off.take(5).map { "${got[it]} != ${want[it]}" }}", off.isEmpty())
    }
}
