package com.jcwear.coach.domain.sky

import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How strong the glass is and which ink the text takes, measured over the frame the sky renderer just drew
 * (design rounds G2, G3, DD1, CO3, PD1, VP1; "contrast is measured, never estimated").
 *
 * For each glass area the rule finds the weakest point under the text: the scene blurred 22 px (as the glass blurs it),
 * saturated (1.6 under white day glass, 1 under the night glass), with the glass tint laid over it at strength `a`.
 * Each mode's glass is only as strong as the [FLOOR] needs (+ a small margin), never under 22%; the mode is the one
 * that needs less glass. Sky text (no glass) takes the ink that needs the weaker veil; a veil is laid only when one is
 * needed (the ink-swap minutes around 20:00 and 05:45). The top-right button takes the first fill that reaches 3.1:1.
 *
 * Ported 1:1 from design-prototype/glass-check.js and tools/visual-loop/g2.js at lock (2026-11-24); parity is tested
 * on the prototype's own renders. Frames are in the 412-wide design frame; the renderer may hand any resolution.
 */
object GlassRule {
    const val FLOOR = 3.0
    private const val STEP = 4          // sample every 4 frame units
    private const val BLUR = 22.0       // the glass blur, frame units
    private const val SAT_DAY = 1.6
    private const val SAT_NIGHT = 1.0
    private const val MIN_ALPHA = 0.22
    private const val MIN_NAV = 0.30

    data class Ink(val primary: Int, val secondary: Int, val sky: Int)
    val INK_DAY = Ink(0x0E2A43, 0x2E4A62, 0x0E2A43)
    val INK_NIGHT = Ink(0xFFFFFF, 0xC9D7EA, 0xF2F6FB)
    const val WHITE = 0xFFFFFF

    data class Fill(val fill: Int, val ink: Int)
    /** The button over the sky: blue, then a white pill, then navy; the first to reach 3.1:1 against the sky behind it. */
    val BUTTON_FILLS = listOf(Fill(0x0B5FA5, 0xFFFFFF), Fill(0xFFFFFF, 0x0B3A66), Fill(0x082F5A, 0xFFFFFF))

    /** A rectangle in the 412 × 915 design frame. */
    data class Rect(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    enum class Mode { DAY, NIGHT }

    data class Decision(
        val mode: Mode, val tint: Int, val alpha: Double, val ink: Ink, val navAlpha: Double,
        val needDay: Double, val needNight: Double,
        val skyMode: Mode, val skyInk: Ink, val veilTint: Int, val veilAlpha: Double,
        val button: Fill?, val pageL: Double,
        /** Measured at the chosen strengths: weakest card text (primary, secondary), nav text, sky text, button. */
        val cardPrimary: Double, val cardSecondary: Double, val navSecondary: Double, val sky: Double, val buttonContrast: Double?
    )

    /** A frame from the renderer: packed 0xRRGGBB (alpha ignored), row-major, top row first. */
    class Frame(val rgb: IntArray, val width: Int, val height: Int)

    // ---- colour ----
    private fun lin(c: Double): Double { val v = c / 255.0; return if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4) }
    private fun gam(v: Double): Int = (255 * (if (v <= .0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - .055)).let { kotlin.math.round(it).toInt() }
    private fun ch(c: Int, k: Int) = (c shr (16 - 8 * k)) and 0xFF
    private fun lum(c: Int) = .2126 * lin(ch(c, 0).toDouble()) + .7152 * lin(ch(c, 1).toDouble()) + .0722 * lin(ch(c, 2).toDouble())
    private fun lStar(y: Double) = if (y > .008856) 116 * cbrt(y) - 16 else 903.3 * y

    /** Three channels sampled every [STEP] frame units (glass-check.js), optionally blurred and saturated like the glass. */
    private class Grid(val gw: Int, val gh: Int, val c: Array<DoubleArray>)

    private fun sample(f: Frame): Grid {
        val s = f.width / 412.0
        val gw = 412 / STEP; val gh = 915 / STEP
        val c = Array(3) { DoubleArray(gw * gh) }
        for (j in 0 until gh) for (i in 0 until gw) {
            val x = min(f.width - 1, floor((i * STEP + STEP / 2) * s).toInt())
            val y = min(f.height - 1, floor((j * STEP + STEP / 2) * s).toInt())
            val p = f.rgb[y * f.width + x]
            for (k in 0..2) c[k][j * gw + i] = ch(p, k).toDouble()
        }
        return Grid(gw, gh, c)
    }

    private fun blurred(g: Grid, blurPx: Double, sat: Double): Grid {
        val sig = max(blurPx, .01) / STEP
        val r = if (blurPx > 0) ceil(sig * 3).toInt() else 0
        val ker = DoubleArray(2 * r + 1) { t -> exp(-((t - r) * (t - r)) / (2 * sig * sig)) }.let { k -> val s = k.sum(); DoubleArray(k.size) { k[it] / s } }
        fun pass(a: DoubleArray, horiz: Boolean): DoubleArray {
            val b = DoubleArray(a.size)
            for (j in 0 until g.gh) for (i in 0 until g.gw) {
                var acc = 0.0
                for (t in -r..r) {
                    val ii = if (horiz) min(g.gw - 1, max(0, i + t)) else i
                    val jj = if (horiz) j else min(g.gh - 1, max(0, j + t))
                    acc += a[jj * g.gw + ii] * ker[t + r]
                }
                b[j * g.gw + i] = acc
            }
            return b
        }
        var c = Array(3) { k -> pass(pass(g.c[k], true), false) }
        if (sat != 1.0) {
            val m = arrayOf(doubleArrayOf(.213 + .787 * sat, .715 - .715 * sat, .072 - .072 * sat),
                doubleArrayOf(.213 - .213 * sat, .715 + .285 * sat, .072 - .072 * sat),
                doubleArrayOf(.213 - .213 * sat, .715 - .715 * sat, .072 + .928 * sat))
            c = Array(3) { k -> DoubleArray(g.gw * g.gh) { q -> min(255.0, max(0.0, m[k][0] * c[0][q] + m[k][1] * c[1][q] + m[k][2] * c[2][q])) } }
        }
        return Grid(g.gw, g.gh, c)
    }

    /** Weakest contrast of [ink] over the glass ([tint] at [a]) on [grid] inside [rects] (glass-check.js). */
    private fun worst(grid: Grid, rects: List<Rect>, ink: Int, a: Double, tint: Int): Double {
        val inkL = lum(ink)
        val tc = DoubleArray(3) { ch(tint, it).toDouble() }
        var worst = 99.0
        for (rc in rects) {
            var y = rc.y0
            while (y < rc.y1) {
                var x = rc.x0
                while (x < rc.x1) {
                    val q = (y / STEP) * grid.gw + (x / STEP)
                    val l = .2126 * lin(a * tc[0] + (1 - a) * grid.c[0][q]) + .7152 * lin(a * tc[1] + (1 - a) * grid.c[1][q]) +
                        .0722 * lin(a * tc[2] + (1 - a) * grid.c[2][q])
                    val cr = (max(l, inkL) + .05) / (min(l, inkL) + .05)
                    if (cr < worst) worst = cr
                    x += STEP
                }
                y += STEP
            }
        }
        return (worst * 100).roundToInt() / 100.0 // glass-check reports two decimals
    }

    /** The smallest strength that keeps [ink] at the floor (+ .05), by bisection (g2.js `need`). */
    private fun need(grid: Grid, rects: List<Rect>, ink: Int, tint: Int, floor: Double): Double {
        fun ok(a: Double) = worst(grid, rects, ink, a, tint) >= floor + .05
        if (ok(0.0)) return 0.0
        if (!ok(.95)) return 1.0
        var lo = 0.0; var hi = .95
        repeat(7) { val m = (lo + hi) / 2; if (ok(m)) hi = m else lo = m }
        return hi
    }

    /** The night glass's tint: the land's own hue under the cards at Y .02, chroma .8 (G2 `landTint`). */
    fun landTint(pageLinear: DoubleArray): Int {
        val y = .2126 * pageLinear[0] + .7152 * pageLinear[1] + .0722 * pageLinear[2]
        val c = IntArray(3) { k -> max(0, min(255, gam(min(1.0, .02 * (.8 * pageLinear[k] / max(y, 1e-5) + .2))))) }
        return (c[0] shl 16) or (c[1] shl 8) or c[2]
    }

    private fun r2(x: Double) = (x * 100).roundToInt() / 100.0

    /**
     * Decide the glass for this frame. [cards] are the glass areas holding text, [nav] the tab bar, [skyText] the text
     * straight on the sky, [button] the top-right button over the sky (if any). [forceMode] / [forceAlpha] pin the
     * glass where the design pins it (the dusk rule: white glass at dusk, DD1).
     */
    fun decide(
        frame: Frame, cards: List<Rect>, nav: Rect?, skyText: List<Rect>, button: Rect? = Rect(250, 40, 392, 88),
        floor: Double = FLOOR, forceMode: Mode? = null, forceAlpha: Double? = null
    ): Decision {
        val raw = sample(frame)
        // the page under the cards: mean linear colour of five rows (y .45 … .85), every 4th pixel
        val acc = DoubleArray(3); var n = 0
        for (yy in doubleArrayOf(.45, .55, .65, .75, .85)) {
            val y = (frame.height - 1 - (frame.height * (1 - yy)).roundToInt()).coerceIn(0, frame.height - 1)
            var x = 0
            while (x < frame.width) { val p = frame.rgb[y * frame.width + x]; for (k in 0..2) acc[k] += lin(ch(p, k).toDouble()); n++; x += 4 }
        }
        val pg = DoubleArray(3) { acc[it] / n }
        val pageL = lStar(.2126 * pg[0] + .7152 * pg[1] + .0722 * pg[2])
        val darkT = landTint(pg)

        val dayGrid = blurred(raw, BLUR, SAT_DAY)
        val nightGrid = blurred(raw, BLUR, SAT_NIGHT)
        val skyGrid = blurred(raw, 0.0, 1.0)

        val nl = need(dayGrid, cards, INK_DAY.secondary, WHITE, floor)
        val nd = need(nightGrid, cards, INK_NIGHT.secondary, darkT, floor)
        val mode = forceMode ?: if (nl == nd) (if (pageL >= 56) Mode.DAY else Mode.NIGHT) else if (nl < nd) Mode.DAY else Mode.NIGHT
        val tint = if (mode == Mode.DAY) WHITE else darkT
        val grid = if (mode == Mode.DAY) dayGrid else nightGrid
        val ink = if (mode == Mode.DAY) INK_DAY else INK_NIGHT
        val a = forceAlpha ?: max(MIN_ALPHA, (if (mode == Mode.DAY) nl else nd) + .02)
        val navA = nav?.let { max(MIN_NAV, need(grid, listOf(it), ink.secondary, tint, floor) + .02) } ?: MIN_NAV

        val sl = need(skyGrid, skyText, INK_DAY.sky, WHITE, floor)
        val sd = need(skyGrid, skyText, INK_NIGHT.sky, darkT, floor)
        val skyMode = if (sd <= sl) Mode.NIGHT else Mode.DAY
        val veilT = if (skyMode == Mode.DAY) WHITE else darkT
        val s0 = min(sl, sd)
        val veilA = if (s0 > 0) min(.9, s0 + .03) else 0.0
        val skyInk = if (skyMode == Mode.DAY) INK_DAY else INK_NIGHT

        var btn: Fill? = null; var btnC: Double? = null
        if (button != null) {
            val bc = BUTTON_FILLS.map { worst(skyGrid, listOf(button), it.fill, veilA, veilT) }
            val bi = bc.indexOfFirst { it >= 3.1 }.let { if (it >= 0) it else bc.indexOf(bc.max()) }
            btn = BUTTON_FILLS[bi]; btnC = bc[bi]
        }
        return Decision(
            mode, tint, r2(a), ink, r2(navA), r2(nl), r2(nd), skyMode, skyInk, veilT, r2(veilA), btn, pageL,
            cardPrimary = worst(grid, cards, ink.primary, a, tint), cardSecondary = worst(grid, cards, ink.secondary, a, tint),
            navSecondary = nav?.let { worst(grid, listOf(it), ink.secondary, navA, tint) } ?: 0.0,
            sky = worst(skyGrid, skyText, skyInk.sky, veilA, veilT), buttonContrast = btnC
        )
    }
}
