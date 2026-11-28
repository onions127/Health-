package com.jcwear.coach.domain.sky

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * What the sky renderer is told each frame: a port of scene-gl.js (the design prototype's renderer) for everything but the GL
 * itself. The hour's palette (keyframes blended in linear light), the weather's colours over it, the sun or moon, the land's
 * shapes and tokens, the season (dryness, the maples' leaves) and the day's seed (cloud formation, upper wind, hero clouds),
 * turned into the uniforms of the scene programs (sky, land), compose and the post passes (rays, final).
 *
 * Parity with the prototype: SceneParamsParityTest against tools/visual-loop/sky-parity.js. Colours are mixed and quantized
 * to 8-bit sRGB exactly as the prototype does (its palettes are hex strings), so the GPU sees the same numbers.
 */
object SceneParams {
    /** sky: the full SkyState (BodySky's). hour 0-24, seed: the day (formation), doy: day of the year (season). w, h: the frame in px. */
    data class Input(
        val sky: SkyState, val hour: Double, val seed: Int, val doy: Double,
        val scroll: Double = 0.0, val dpr: Double = 2.0, val w: Int = 824, val h: Int = 1830,
        val live: Boolean = true, val showSun: Boolean = true, val post: Boolean = true, val bake: Boolean = false,
    )

    /** Uniform values by name, per program. SKY and LAND take the same scene set. Ints (uTreeN) are in [ints]. */
    class Out {
        val scene = LinkedHashMap<String, DoubleArray>(); val compose = LinkedHashMap<String, DoubleArray>()
        val rays = LinkedHashMap<String, DoubleArray>(); val final = LinkedHashMap<String, DoubleArray>()
        val ints = LinkedHashMap<String, IntArray>()
        /** The post passes' switches the renderer needs: shafts drawn at all. */
        var raysOn = false
        /** The hour's palette after weather (the glass and the page take their colours from it). */
        lateinit var pal: Pal
    }

    // ------------------------------------------------------------------ palettes (scene-gl.js PAL, PAL_*)
    data class Grade(val gain: DoubleArray, val lift: DoubleArray, val sat: Double, val con: Double, val rays: Double, val bloom: Double)
    data class Pal(
        val zen: String, val mid: String, val hor: String, val sunC: String, val sunR: Double, val cl: String, val cd: String,
        val fog: String, val path: String, val h: List<String>, val tree: String, val hill: String, val water: String,
        val pg: List<String>, val night: Double, val i: Double, val key: DoubleArray, val g: Grade,
    )
    /** A weather's colours, laid over the hour's palette (only these fields). */
    private class WPal(val zen: String, val mid: String, val hor: String, val cl: String, val cd: String, val fog: String,
                       val hill: String, val water: String, val h: List<String>, val pg: List<String>)

    private fun g(gain: List<Double>, lift: List<Double>, sat: Double, con: Double, rays: Double, bloom: Double) =
        Grade(gain.toDoubleArray(), lift.toDoubleArray(), sat, con, rays, bloom)

    val DAWN = Pal("#7FAACB", "#A3BCC8", "#DCC6BA", "#FFF0DC", .017, "#FFEFE2", "#A9AFC0", "#E3DCD8", "#E6D6BC",
        listOf("#A9C4A2", "#9FC48E", "#A2C78C", "#A6CB8C"), "#4F6E57", "#B3BCCF", "#7CC3E4", listOf("#A9D2A0", "#98D0C0", "#98CBE6", "#88BBEE"),
        0.0, 1.15, doubleArrayOf(-.75, .30, .35), g(listOf(1.03, 1.0, .97), listOf(0.0, .006, .03), 1.06, .05, .45, .45))
    val DAY = Pal("#4F9EEE", "#7DBBF3", "#ACD7F7", "#FFF8EA", .017, "#FFFDF8", "#A9C2DA", "#C8E2F4", "#E4DCC2",
        listOf("#9CC79A", "#92C57C", "#97CA79", "#9DCF7B"), "#55895C", "#86AED3", "#52B6E4", listOf("#A1D29C", "#8ECFBE", "#8DC8E8", "#82B7EE"),
        0.0, 1.25, doubleArrayOf(.45, .80, -.35), g(listOf(1.0, 1.0, 1.0), listOf(0.0, .004, .012), 1.08, .05, .18, .30))
    val DUSK = Pal("#8C86D6", "#9D8DDA", "#C6A0E4", "#FFF6F2", .017, "#F6DDF0", "#6E66A6", "#A797CC", "#C9A88A",
        listOf("#7F8F8C", "#6D8466", "#627C5A", "#5B7654"), "#33473A", "#8F87B8", "#5677AC", listOf("#7E9A8E", "#7891A8", "#7F86BC", "#7A7BC0"),
        0.0, 1.1, doubleArrayOf(.75, .22, .35), g(listOf(1.0, .98, 1.02), listOf(.01, 0.0, .03), 1.06, .06, .45, .50))
    val NIGHT = Pal("#081850", "#0D266A", "#1A4AA2", "#EEF2FA", .022, "#6F8CC6", "#14295E", "#203A72", "#2E3E50",
        listOf("#1E3462", "#1C325C", "#1A2F56", "#182B50"), "#0B1724", "#182F66", "#161C62", listOf("#142B58", "#122A64", "#10286A", "#0E246C"),
        1.0, .26, doubleArrayOf(.35, .85, -.25), g(listOf(.97, .98, 1.02), listOf(0.0, .002, .010), 1.30, .04, .04, .22))

    private val W_OVC = WPal("#C3D6E4", "#B0C8D8", "#8EB0C8", "#FFFFFF", "#9DB4C8", "#B7CAD8", "#6E92AC", "#5E97BC",
        listOf("#88A99C", "#84A790", "#88AB92", "#8CAF96"), listOf("#A6C9B4", "#9CC6C8", "#9DC0DC", "#9AB8E2"))
    private val W_RAIN = WPal("#9497BE", "#8F92B9", "#B39CD2", "#E8E6F4", "#7779A6", "#ABA7CC", "#6D7199", "#5B6F9A",
        listOf("#6F8F8A", "#6A8C82", "#6E9186", "#73968C"), listOf("#9EBFB4", "#99BAC4", "#9EB4D4", "#9CADDE"))
    private val W_STORM = WPal("#97A6BE", "#919CAF", "#66779A", "#DCE2EC", "#5C6984", "#8E9AB2", "#42536E", "#3F5677",
        listOf("#6E8A88", "#6A867E", "#6E8A82", "#728E86"), listOf("#9CB8B0", "#98B4C0", "#9AAED0", "#98A8DA"))
    private val W_FOG = WPal("#7D9AB7", "#9BB3C6", "#B9CBD6", "#E9EEF2", "#A7B8C6", "#C3D2DC", "#8DA6BA", "#93B8CC",
        listOf("#94BE9C", "#8FBC92", "#93BF96", "#97C29A"), listOf("#A6C2B4", "#9FBDC2", "#9CB6CE", "#97AED6"))
    private fun wpal(p: SkyPalette) = when (p) { SkyPalette.NONE -> null; SkyPalette.FOG -> W_FOG; SkyPalette.RAIN -> W_RAIN; SkyPalette.STORM -> W_STORM; SkyPalette.OVERCAST -> W_OVC }

    private val KEYS = listOf(0.0 to NIGHT, 5.0 to NIGHT, 6.5 to DAWN, 9.0 to DAY, 16.5 to DAY, 19.0 to DUSK, 21.0 to NIGHT, 24.0 to NIGHT)
    private val SUN = listOf(5.5 to doubleArrayOf(.10, .43), 6.5 to doubleArrayOf(.13, .355), 8.5 to doubleArrayOf(.22, .20), 13.0 to doubleArrayOf(.64, .15),
        16.5 to doubleArrayOf(.80, .17), 19.0 to doubleArrayOf(.86, .35), 20.0 to doubleArrayOf(.90, .43))
    private val MOON = doubleArrayOf(.78, .12)
    private const val TWI = .9           // G3: the twilight dip of the key light
    private const val SPIKES = 1         // L1: the soft glare

    // ------------------------------------------------------------------ colour maths, as the prototype's
    private fun pow(a: Double, b: Double) = StrictMath.pow(a, b)
    fun hex(h: String): DoubleArray = DoubleArray(3) { Integer.parseInt(h.substring(1 + 2 * it, 3 + 2 * it), 16) / 255.0 }
    private fun toHex(a: DoubleArray): String = "#" + a.joinToString("") {
        Math.round(pow(min(1.0, max(0.0, it)), 1 / 2.2) * 255).toInt().toString(16).padStart(2, '0').uppercase()
    }
    /** Two sRGB colours mixed in linear light, back to 8-bit sRGB (mixV on strings). Equal strings stay as they are. */
    fun mixHex(a: String, b: String, f: Double): String {
        if (a.equals(b, ignoreCase = true)) return a
        val x = hex(a); val y = hex(b)
        return toHex(DoubleArray(3) { pow(x[it], 2.2) + (pow(y[it], 2.2) - pow(x[it], 2.2)) * f })
    }
    private fun mixN(a: Double, b: Double, f: Double) = if (a == b) a else a + (b - a) * f
    private fun mixA(a: DoubleArray, b: DoubleArray, f: Double) = if (a === b) a else DoubleArray(a.size) { mixN(a[it], b[it], f) }
    private fun mixL(a: List<String>, b: List<String>, f: Double) = a.indices.map { mixHex(a[it], b[it], f) }
    private fun mixG(a: Grade, b: Grade, f: Double) = if (a === b) a else
        Grade(mixA(a.gain, b.gain, f), mixA(a.lift, b.lift, f), mixN(a.sat, b.sat, f), mixN(a.con, b.con, f), mixN(a.rays, b.rays, f), mixN(a.bloom, b.bloom, f))
    private fun mixP(a: Pal, b: Pal, f: Double): Pal = if (a === b) a else Pal(
        mixHex(a.zen, b.zen, f), mixHex(a.mid, b.mid, f), mixHex(a.hor, b.hor, f), mixHex(a.sunC, b.sunC, f), mixN(a.sunR, b.sunR, f),
        mixHex(a.cl, b.cl, f), mixHex(a.cd, b.cd, f), mixHex(a.fog, b.fog, f), mixHex(a.path, b.path, f), mixL(a.h, b.h, f),
        mixHex(a.tree, b.tree, f), mixHex(a.hill, b.hill, f), mixHex(a.water, b.water, f), mixL(a.pg, b.pg, f),
        mixN(a.night, b.night, f), mixN(a.i, b.i, f), mixA(a.key, b.key, f), mixG(a.g, b.g, f))
    private fun over(p: Pal, w: WPal) = p.copy(zen = w.zen, mid = w.mid, hor = w.hor, cl = w.cl, cd = w.cd, fog = w.fog, hill = w.hill, water = w.water, h = w.h, pg = w.pg)

    fun sstep(a: Double, b: Double, x: Double): Double { val t = min(1.0, max(0.0, (x - a) / (b - a))); return t * t * (3 - 2 * t) }
    private fun wrap24(h: Double) = ((h % 24) + 24) % 24
    private fun <T> seg(t: List<Pair<Double, T>>, h: Double, mix: (T, T, Double) -> T): T {
        if (h <= t[0].first) return t[0].second
        for (i in 1 until t.size) if (h <= t[i].first) return mix(t[i - 1].second, t[i].second, sstep(t[i - 1].first, t[i].first, h))
        return t.last().second
    }
    fun palAt(hour: Double): Pal = seg(KEYS, wrap24(hour), ::mixP)
    fun sunAt(hour: Double): DoubleArray = seg(SUN, wrap24(hour), ::mixA)
    // ------------------------------------------------------------------ bakes (3.12, G3): the hours baked per sky, and the blend between two
    /** Hours baked per sky: densest where the light changes fastest; 21:00 holds the night until 05:00 (prototype KEYFRAMES). */
    val KEYFRAMES = listOf(5.0, 5.5, 5.75, 6.5, 7.5, 9.0, 11.0, 13.0, 15.0, 16.5, 17.75, 19.0, 20.0, 20.25, 20.5, 21.0)
    /** The palettes' own sun place (the prototype's `sun` field), mixed as the palettes are; it only enters the bake blend. */
    private val PSUN = listOf(0.0 to MOON, 5.0 to MOON, 6.5 to doubleArrayOf(.20, .30), 9.0 to doubleArrayOf(.64, .15), 16.5 to doubleArrayOf(.64, .15),
        19.0 to doubleArrayOf(.86, .35), 21.0 to MOON, 24.0 to MOON)
    /** The light of an hour as numbers: palette colours in linear light, its scalars, and the sun's place (the grade is live, so left out). */
    fun lightVec(hour: Double): DoubleArray {
        val p = palAt(hour); val v = ArrayList<Double>()
        fun c(h: String) = hex(h).forEach { v += pow(it, 2.2) }
        c(p.zen); c(p.mid); c(p.hor); c(p.sunC); v += seg(PSUN, wrap24(hour), ::mixA).toList(); v += p.sunR; c(p.cl); c(p.cd); c(p.fog); c(p.path)
        p.h.forEach(::c); c(p.tree); c(p.hill); c(p.water); p.pg.forEach(::c); v += p.night; v += p.i; v += p.key.toList()
        v += (if (p.night < .5) sunAt(hour) else MOON).toList()
        return v.toDoubleArray()
    }
    /**
     * The two baked hours around [hour] (of [hours]) and how far between: where this hour's light sits on the line between the two
     * keyframes' light (least squares), not how far the clock is, since palettes move along a smoothstep (05:23 is 16% of the way
     * to 05:45, not 50%). Falls back to the clock when the two lights are the same (the night).
     */
    fun bracket(hour: Double, hours: List<Double> = KEYFRAMES): Triple<Double, Double, Double> {
        val l = hours.sorted(); val h = wrap24(hour)
        if (l.size == 1) return Triple(l[0], l[0], 0.0)
        val i = l.indexOfFirst { it > h }; val b = l[if (i < 0) 0 else i]; val a = l[if (i <= 0) l.size - 1 else i - 1]
        val va = lightVec(a); val vb = lightVec(b); val x = lightVec(h)
        var num = 0.0; var den = 0.0
        for (j in va.indices) { num += (x[j] - va[j]) * (vb[j] - va[j]); den += (vb[j] - va[j]) * (vb[j] - va[j]) }
        val span = ((b - a) + 24) % 24; val into = ((h - a) + 24) % 24
        return Triple(a, b, if (den > 1e-9) min(1.0, max(0.0, num / den)) else into / (if (span == 0.0) 24.0 else span))
    }

    private fun shoulder(v: Double) = if (v < .8) v else 1 - .2 * StrictMath.exp(-(v - .8) / .2)
    private fun lin(h: String) = hex(h).map { pow(it, 2.2) }.toDoubleArray()
    private fun norm(v: DoubleArray): DoubleArray { val l = StrictMath.sqrt(v.sumOf { it * it }); return DoubleArray(v.size) { v[it] / l } }

    // ------------------------------------------------------------------ the land (terrain lock: the open calm loch)
    private const val HOR = .37
    private val LIP = doubleArrayOf(.404, 0.0, 0.0, 1.0, -.014, 0.0, .16, .006, 2.0, 4.0, 1.0, 1.0)
    private val GR = doubleArrayOf(.40, .008, .50, .014, -10.0, -9.0, 0.0, 0.0)
    private val TREES = listOf(doubleArrayOf(.865, .108, 3.0, 1.0), doubleArrayOf(.655, .052, 7.0, 1.0))
    private val SHORE = doubleArrayOf(.0055, .20, 1.0, 0.0)
    private val CREST = listOf(
        doubleArrayOf(HOR, .004, .018, .72, .42, .006, .22, .12, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, -10.0, -9.0, .004, 6.0, 1.3, .0008, 150.0, 0.0, 0.0, 0.0),
        doubleArrayOf(HOR, .0015, .012, .12, .22, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, -10.0, -9.0, .003, 9.0, 4.1, .0008, 170.0, 0.0, 0.0, 0.0))
    private val PROPS = listOf(
        doubleArrayOf(26.0, HOR + .0004, 0.0, .18, 0.0, -10.0, 0.0, 1.0, .2, 0.0, 0.0, 0.0),
        doubleArrayOf(11.0, HOR + .0004, 0.0, .18, 0.0, -10.0, 0.0, 1.0, .35, 0.0, 0.0, 1.0))
    private val TK = listOf("#5E7078", "#5C7A50", "#3A5A3C", "#7DA556", "#B79C5A", "#2D6C93", "#3C88A8", "#D9C7A0")
    private val LEAF = doubleArrayOf(.040, .20, 1.0, .17)
    private val DRY = listOf(0.0 to .05, 59.0 to .05, 105.0 to .2, 135.0 to .45, 166.0 to .8, 196.0 to 1.0, 258.0 to 1.0, 288.0 to .85, 319.0 to .45, 349.0 to .15, 366.0 to .05)
    private fun lerpT(t: List<Pair<Double, Double>>, d: Double): Double {
        for (i in 1 until t.size) if (d <= t[i].first) return t[i - 1].second + (t[i].second - t[i - 1].second) * (d - t[i - 1].first) / (t[i].first - t[i - 1].first)
        return t.last().second
    }
    /** Dryness of the land by day of the year (Mediterranean: green winter and spring, straw by July). */
    fun dryAt(doy: Double) = lerpT(DRY, doy)

    // the maple (Round T3): shape, bark, bloom, and its year: [day, leaf, leaf 2, fullness, bloom]
    private const val BARE = "#6B5B45"
    private val MAPLE_SHAPE = doubleArrayOf(.44, .40, .24, 0.0)
    private const val MAPLE_BLOOM = "#D8D890"
    private class Y(val d: Double, val a: String, val b: String, val full: Double, val bloom: Double)
    private val MAPLE_YEAR = listOf(Y(0.0, BARE, BARE, 0.0, 0.0), Y(95.0, BARE, BARE, 0.0, 0.0), Y(108.0, "#A6C860", "#BCD26A", .55, 0.0), Y(125.0, "#5E8C3E", "#6E9A44", .95, 0.0),
        Y(272.0, "#4E7A36", "#5C8A3C", 1.0, 0.0), Y(292.0, "#8E9A3A", "#C9A23A", 1.0, 0.0), Y(306.0, "#E2B23A", "#E08A2E", .97, 0.0), Y(318.0, "#E27A2C", "#D2482A", .92, 0.0),
        Y(328.0, "#CC3F2C", "#E2862E", .72, 0.0), Y(338.0, "#A8422E", "#B86A30", .3, 0.0), Y(348.0, BARE, BARE, 0.0, 0.0), Y(366.0, BARE, BARE, 0.0, 0.0))
    /** [shape vec4, leaf rgb + fullness, leaf 2 rgb + bark, bloom rgb + amount] for the day of the year (phen). */
    fun phen(doy: Double): DoubleArray {
        val y = MAPLE_YEAR; var i = 1; while (i < y.size - 1 && doy > y[i].d) i++
        val a = y[i - 1]; val b = y[i]; val f = min(1.0, max(0.0, (doy - a.d) / max(1.0, b.d - a.d)))
        fun l(p: Double, q: Double) = p + (q - p) * f
        fun c(p: String, q: String) = hex(p).zip(hex(q)).map { (u, v) -> l(u, v) }
        return (MAPLE_SHAPE.toList() + c(a.a, b.a) + l(a.full, b.full) + c(a.b, b.b) + 0.0 + hex(MAPLE_BLOOM).toList() + l(a.bloom, b.bloom)).toDoubleArray()
    }

    // ------------------------------------------------------------------ the uniforms
    private fun fract(x: Double) = x - floor(x)
    private fun rnd(day: Int, k: Int) = fract(StrictMath.sin(day * 127.1 + k * 311.7) * 43758.5453)

    fun build(inp: Input): Out {
        val o = Out(); val w = inp.sky; val hour = inp.hour
        // state(): the hour's palette, the weather's colours over it (much less at night), the sun or the moon
        val p0 = palAt(hour); val wp = wpal(w.pal)
        val p = if (wp == null) p0 else mixP(p0, over(p0, wp), (if (w.palK == 0.0) 1.0 else w.palK) * (1 - .75 * p0.night))
        o.pal = p
        val night = p.night
        val vis = if (night < .5) 1 - sstep(.3, .5, night) else sstep(.5, .7, night)
        val sp = if (night < .5) sunAt(hour) else MOON
        val sv = vis * (1 - sstep(.45, .85, w.fog))

        // setScene(): both scene programs
        val s = o.scene
        fun put(n: String, vararg v: Double) { s[n] = v }
        put("uCover", w.cover); put("uTall", w.tall); put("uGrey", w.grey); put("uHaze", w.haze)
        put("uStyle", w.style); put("uPaintCov", w.paintCov); put("uCirrus", w.cirrus); put("uCiHook", w.ciHook); put("uCiVeil", w.ciVeil)
        put("uCiAng", w.ciAng + (rnd(inp.seed, 12) - .5) * 1.1)
        put("uSnow", w.snow); put("uSheet", w.sheet); put("uSType", w.sType); put("uSB", w.sb); put("uST", w.st)
        put("uFogD", w.fog); put("uFogH", w.fogH); put("uRain", w.rain); put("uNight", night)
        put("uSunI", p.i * (1 - TWI * sstep(.35, .6, night) * (1 - sstep(.85, 1.0, night))))
        listOf("uPg0" to p.pg[0], "uPg1" to p.pg[1], "uPg2" to p.pg[2], "uPg3" to p.pg[3], "uZen" to p.zen, "uMid" to p.mid, "uHor" to p.hor,
            "uSunC" to p.sunC, "uCLit" to p.cl, "uCDark" to p.cd, "uFog" to p.fog, "uH0" to p.h[0], "uH1" to p.h[1], "uH2" to p.h[2], "uH3" to p.h[3],
            "uTree" to p.tree, "uPath" to p.path).forEach { (n, c) -> s[n] = hex(c) }
        s["uKey"] = norm(p.key)
        put("uDry", dryAt(inp.doy))
        s["uTk"] = TK.flatMap { hex(it).toList() }.toDoubleArray()
        put("uNL", 2.0); s["uLip"] = LIP
        s["uLy"] = (CREST[0].toList() + CREST[1].toList() + List(24) { 0.0 }).toDoubleArray()
        s["uLz"] = (PROPS[0].toList() + PROPS[1].toList() + List(12) { 0.0 }).toDoubleArray()
        s["uGr"] = GR; s["uTr"] = TREES.flatMap { it.toList() }.toDoubleArray()
        val ph = phen(inp.doy); s["uTs"] = ph + ph
        o.ints["uTreeN"] = treeCounts
        s["uPk"] = DoubleArray(20) { doubleArrayOf(0.0, -1.0, 1.0, 1.0)[it % 4] }
        s["uLm"] = DoubleArray(12)
        s["uSd"] = SHORE; s["uTst"] = LEAF
        put("uSeed", rnd(inp.seed, 1) * 160, rnd(inp.seed, 2) * 160)
        // hero clouds: the near mass on the left (behind the readiness number), sized by the low-cloud cover (3.15)
        val side = -1.0; val hk = .55 + .45 * sstep(.30, .50, w.cover); val r = { k: Int -> rnd(inp.seed, k) }
        put("uHero1", side * (.6 + r(3) * 1.2), 6 + r(4) * 2.5, (3.0 + r(5) * 1.2) * hk, .55 + .35 * r(6))
        put("uHero2", -side * (1.6 + r(7) * 2.2), 12 + r(8) * 5, if (r(9) > .45) (2.2 + r(10)) * hk else 0.0, .45 + .3 * r(11))
        put("uSun", sp[0], sp[1]); put("uSunR", p.sunR); put("uShowSun", if (inp.showSun) vis * (1 - .8 * sstep(.45, .85, w.fog)) else 0.0)
        put("uStepK", 1.0)

        // compose(): wind, rain, snow, birds, the disc and halo, stars, SC1 scroll
        val c = o.compose
        fun cput(n: String, vararg v: Double) { c[n] = v }
        cput("uRain", w.rain); cput("uNight", night)
        c["uRainCol"] = lin(mixHex("#EDF2FA", p.fog, night))
        cput("uSnowF", w.snow); c["uSnowCol"] = lin(mixHex("#FBFDFF", p.cl, night)).map { it * (1 - .4 * night) }.toDoubleArray()
        cput("uBolt", w.bolt); cput("uWind", w.wind); cput("uDpr", inp.dpr)
        val sc = max(0.0, inp.scroll); cput("uScrK", min(1.0, sc / 200)); cput("uLift", .15 * min(sc, 200.0) * inp.dpr)
        cput("uScrLod", StrictMath.log(24 * inp.dpr) / StrictMath.log(2.0))
        cput("uBirds", if (inp.live) (1 - night) * (1 - w.rain) * (1 - .8 * min(1.0, w.fog)) * (1 - .7 * w.grey) else 0.0)
        c["uBirdCol"] = lin(mixHex("#2F3B48", p.mid, .18))
        cput("uSun", sp[0], sp[1]); cput("uSunR", p.sunR)
        cput("uShowSun", if (inp.showSun && !inp.bake) sv * (1 - w.style) else 0.0)
        cput("uVeil", min(1.0, w.fog * .9 + w.rain * .5))
        c["uSunDisc"] = hex(p.sunC).map { shoulder(pow(it, 2.2) * (if (night > .5) 1.0 else 2.2)) }.toDoubleArray()
        val halo = if (inp.showSun) sv * (1 - night * .93) * (1 - w.style) else 0.0
        c["uHalo"] = hex(p.sunC).map { pow(it, 2.2) * halo }.toDoubleArray()
        cput("uStars", sstep(.3, .9, night) * (1 - w.style))

        // post(): the grade, shafts, the sun's glare (frame = canvas: no cover-fit offset)
        val gr = if (inp.post) p.g else Grade(doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0), 1.0, 0.0, 0.0, 0.0)
        val sunUV = doubleArrayOf(sp[0] * inp.w / inp.w, (1 - sp[1]) * inp.h / inp.h)
        val rays = gr.rays * (if (inp.showSun) sv else 0.0) * (1 - w.style) * (1 - .8 * min(1.0, w.sheet))
        o.raysOn = rays > 0
        if (rays > 0) o.rays["uSunUV"] = sunUV
        val f = o.final
        fun fput(n: String, vararg v: Double) { f[n] = v }
        fput("uBloom", gr.bloom); fput("uRays", rays); fput("uSat", gr.sat); fput("uCon", gr.con)
        val flare = if (inp.post && inp.showSun && sunUV[1] > 0 && sunUV[1] < 1.2) sv * (1 - w.style) * (1 - .9 * min(1.0, w.sheet)) * (1 - .93 * night) else 0.0
        f["uSunUV"] = sunUV; fput("uFlare", flare); fput("uRimK", 1.2); fput("uGhost", 1 - night)
        fput("uSpk", if (SPIKES == 2) 1.0 else 1 - sstep(.25, .5, night)); fput("uSpkS", SPIKES.toDouble())
        f["uGain"] = gr.gain; f["uLift"] = gr.lift; f["uSunCol"] = lin(p.sunC)
        return o
    }

    // ------------------------------------------------------------------ the trees' branching (Round T5), grown once
    const val SEG_MAX = 400
    /** The branch texture: 800 x 2 RGBA32F (row = tree, 2 texels per segment: a.xy b.xy, widths, scaffold mass centre). */
    val treeTexture: FloatArray by lazy { trees.first }
    val treeCounts: IntArray get() = trees.second
    private val trees: Pair<FloatArray, IntArray> by lazy {
        val buf = FloatArray(SEG_MAX * 2 * 4 * 2); val ns = IntArray(2)
        TREES.forEachIndexed { i, t ->
            val sg = treeSkel(t[2].toInt() * 7 + 1); ns[i] = sg.size
            sg.forEachIndexed { j, g -> g.forEachIndexed { k, v -> buf[(i * SEG_MAX * 2 + j * 2) * 4 + k] = v.toFloat() } }
        }
        buf to ns
    }

    /** A maple's skeleton: trunk to the fork, four scaffold limbs, leaders and laterals inside the crown (treeSkel). */
    fun treeSkel(seed: Int): List<DoubleArray> {
        val wR = MAPLE_SHAPE[0]; val hR = MAPLE_SHAPE[1]; val fork = MAPLE_SHAPE[2]; val cy = 1 - hR
        var z = ((seed.toLong() * 2654435761L + 12345L) and 0xFFFFFFFFL).toInt()
        fun rnd(): Double {
            z += 0x6D2B79F5
            var t = (z xor (z ushr 15)) * (1 or z)
            t = t xor (t + (t xor (t ushr 7)) * (61 or t))
            return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
        }
        val out = ArrayList<DoubleArray>()
        fun push(a: DoubleArray, b: DoubleArray, wa: Double, wb: Double, mc: DoubleArray) { if (out.size < SEG_MAX) out.add(doubleArrayOf(a[0], a[1], b[0], b[1], wa, wb, mc[0], mc[1])) }
        fun inEnv(p: DoubleArray, k: Double): DoubleArray {
            val ex = p[0] / (wR * k); val ey = (p[1] - cy) / (hR * k); val r = Math.hypot(ex, ey)
            return if (r <= 1) p else doubleArrayOf(ex / r * wR * k, cy + ey / r * hR * k)
        }
        val maxL = 6
        class Q(val p: DoubleArray, val ang: Double, val len: Double, val w: Double, val lev: Int, val mc: DoubleArray)
        val queue = ArrayDeque<Q>()
        fun inside(q: DoubleArray, k: Double) = Math.hypot(q[0] / (wR * k), (q[1] - cy) / (hR * k)) <= 1
        fun grow(p: DoubleArray, ang: Double, len0: Double, w: Double, lev: Int, mc0: DoubleArray) {
            var len = len0; var mc = mc0
            val bend = (rnd() - .5) * (if (lev < 3) .45 else .35)
            var mid = doubleArrayOf(p[0] + StrictMath.sin(ang) * len * .5, p[1] + StrictMath.cos(ang) * len * .5)
            val a2 = ang + bend; var end = doubleArrayOf(mid[0] + StrictMath.sin(a2) * len * .5, mid[1] + StrictMath.cos(a2) * len * .5)
            val k = if (lev >= 4) 1.04 else .94
            if (!inside(end, k) && inside(p, k)) {
                var lo = 0.0; var hi = 1.0
                for (i in 0 until 10) { val m = (lo + hi) / 2; if (inside(doubleArrayOf(p[0] + (end[0] - p[0]) * m, p[1] + (end[1] - p[1]) * m), k)) lo = m else hi = m }
                val f = max(lo, .25); end = doubleArrayOf(p[0] + (end[0] - p[0]) * f, p[1] + (end[1] - p[1]) * f); mid = doubleArrayOf(p[0] + (mid[0] - p[0]) * f, p[1] + (mid[1] - p[1]) * f); len *= f
            }
            val wm = w * .90; val we = w * .80
            if (lev == 2) { val dx = end[0] - p[0]; val dy = end[1] - p[1]; val l = Math.hypot(dx, dy).let { if (it == 0.0) 1.0 else it }; mc = inEnv(doubleArrayOf(end[0] + dx / l * .06, end[1] + dy / l * .06), .86) }
            if (lev < 4) { push(p, mid, w, wm, mc); push(mid, end, wm, we, mc) } else push(p, end, w, we, mc)
            if (lev >= maxL) return
            val da = StrictMath.atan2(end[0] - mid[0], end[1] - mid[1])
            val nLat = if (lev <= 2) 2 else if (lev <= 4) (if (rnd() < .5) 2 else 1) else (if (rnd() < .45) 1 else 0)
            class K(val at: DoubleArray, val a: Double, val lk: Double, val lead: Boolean)
            val kids = ArrayList<K>()
            if (!(lev == 5 && rnd() < .35)) kids.add(K(end, da + (rnd() - .5) * .50, .74, true))
            var sd = if (rnd() < .5) -1 else 1
            for (i in 0 until nLat) {
                val t = .45 + .45 * (i + rnd()) / max(1, nLat); val at = doubleArrayOf(p[0] + (end[0] - p[0]) * t, p[1] + (end[1] - p[1]) * t)
                kids.add(K(at, da + sd * (.55 + .35 * rnd()), .60 + .12 * rnd(), false)); sd = -sd
            }
            val area = kids.sumOf { if (it.lead) 1.0 else .55 }
            for (kd in kids) {
                var aa = kd.a * (if (lev >= 2) .88 else 1.0)
                if (lev >= 3) aa += Math.signum(if (kd.at[0] == 0.0) 1.0 else kd.at[0]) * .08
                queue.addLast(Q(kd.at, aa, len * kd.lk, we * StrictMath.sqrt((if (kd.lead) 1.0 else .55) / area), lev + 1, mc))
            }
        }
        val lean = (rnd() - .5) * .05; val top = doubleArrayOf(lean, fork)
        push(doubleArrayOf(0.0, 0.0), doubleArrayOf(lean * .5, fork * .5), .040, .033, doubleArrayOf(0.0, cy))
        push(doubleArrayOf(lean * .5, fork * .5), top, .033, .029, doubleArrayOf(0.0, cy))
        val l1 = listOf(-.80, -.28, .26, .78).map { it + (rnd() - .5) * .22 }
        l1.forEachIndexed { i, a -> val at = doubleArrayOf(top[0] - lean * .15 * i, top[1] - .025 * (i % 2)); queue.addLast(Q(at, a, .30 + .06 * rnd(), .021, 1, doubleArrayOf(0.0, cy))) }
        while (queue.isNotEmpty() && out.size < SEG_MAX) { val q = queue.removeFirst(); grow(q.p, q.ang, q.len, q.w, q.lev, q.mc) }
        return out
    }
}
