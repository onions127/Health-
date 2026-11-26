package com.jcwear.coach.domain.sky

import com.jcwear.coach.domain.sky.JsText.grouped
import com.jcwear.coach.domain.sky.JsText.jsRound
import com.jcwear.coach.domain.sky.JsText.num
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The hour's colours a weather blends over the sky (renderer palettes, design-prototype/scene-gl.js PAL_*). */
enum class SkyPalette { NONE, FOG, RAIN, STORM, OVERCAST }

/** Low-cloud regime from readiness (PLAN-sky D1): ≥ 80 fair, 65–79 partly, 50–64 stratocumulus, < 50 stratus. */
enum class SkyRegime { FIRST_RUN, FAIR, PARTLY, STRATOCUMULUS, STRATUS;
    companion object {
        fun of(readiness: Double?): SkyRegime = when {
            readiness == null -> FIRST_RUN
            readiness >= 80 -> FAIR
            readiness >= 65 -> PARTLY
            readiness >= 50 -> STRATOCUMULUS
            else -> STRATUS
        }
    }
}

/**
 * Everything the sky can be: one struct drives the renderer. Defaults are the prototype's SKY0 (a clean high sky,
 * a few fair-weather cumulus, a breeze).
 */
data class SkyState(
    val style: Double = 0.0, val paintCov: Double = 0.5,
    val cirrus: Double = 0.50, val ciHook: Double = 0.6, val ciVeil: Double = 0.0, val ciAng: Double = 0.95,
    val cover: Double = 0.30, val tall: Double = 0.62, val grey: Double = 0.0, val haze: Double = 0.0,
    val sheet: Double = 0.0, val sType: Double = 0.0, val sb: Double = 1.0, val st: Double = 1.7,
    val fog: Double = 0.0, val fogH: Double = 0.0, val rain: Double = 0.0, val wind: Double = 0.45,
    val snow: Double = 0.0, val bolt: Double = 0.0,
    val pal: SkyPalette = SkyPalette.NONE, val palK: Double = 0.0
) {
    companion object {
        /** The regime's base sky (scene-gl.js `weather(r)`). */
        fun weather(readiness: Double?): SkyState = when (SkyRegime.of(readiness)) {
            SkyRegime.FIRST_RUN -> SkyState(cover = .52, tall = .60, grey = .05, haze = .05)
            SkyRegime.FAIR -> SkyState()
            SkyRegime.PARTLY -> SkyState(cover = .50, tall = .75, grey = .06, haze = .04, wind = .60)
            SkyRegime.STRATOCUMULUS -> SkyState(cover = .30, tall = .40, grey = .20, haze = .08, sheet = .75, sType = .30, sb = 1.0, st = 1.75, wind = .65)
            SkyRegime.STRATUS -> SkyState(cover = .15, tall = .35, grey = .42, haze = .15, sheet = 1.0, sType = 1.0, sb = .55, st = 1.25, wind = .50)
        }
    }
}

/** The sky a body gives at an hour, which signals act (0..1), and the words for a tap on the sky and TalkBack (D4). */
data class BodySkyResult(
    val sky: SkyState, val regime: SkyRegime, val notes: List<String>, val talkback: String,
    val fog: Double, val hrv: Double?, val activity: Double?, val load: Double?, val ill: Double?, val storm: Double?
)

/**
 * The weather of the body (design rounds B1, D1–D6, ST1, FW1). Each signal owns one layer, so the sky never says
 * something the numbers do not:
 *  readiness → the low-cloud regime; sleep → morning valley fog; HRV against your normal → the high cloud;
 *  activity pace → cumulus building through the afternoon; skin temp + resting HR → haze, light rain for likely
 *  illness (D6), a storm only for likely illness with readiness under 35 (D2); missing data → a plain fair sky.
 * Pure: no randomness, no state. Parity with design-prototype/body-sky.js is tested (parity/body_sky.tsv).
 */
object BodySky {
    object T {
        val fogSleepScore = 78.0 to 50.0      // no fog at 78+, full valley fog at 50 or under
        val fogDeficitMin = 20.0 to 100.0     // minutes under the usual: no fog at 20, full at 100
        val fogHours = doubleArrayOf(5.0, 7.0, 10.5, 12.0)
        val hrvLow = 0.0 to -1.6              // HRV in half-band units (0 = bottom of normal): streaks from 0, veil by -1.6
        val activity = 1.15 to 1.6            // pace against your usual for the hour (BD4's 1.15)
        val buildHours = doubleArrayOf(10.0, 15.0, 19.0, 21.0)
        const val hazeSkin = 0.3; const val hazeRhr = 3.0
        const val illSkin = 0.5; const val illRhr = 5.0
        const val stormReadiness = 35.0
    }

    private fun clamp(v: Double, a: Double = 0.0, b: Double = 1.0) = max(a, min(b, v))
    private fun sstep(a: Double, b: Double, x: Double): Double { val t = clamp((x - a) / (b - a)); return t * t * (3 - 2 * t) }
    private fun fmtMin(m: Double) = "${floor(m / 60).toLong()} h ${jsRound(m % 60).toString().padStart(2, '0')} m"

    private val RAIN = SkyState(cover = .10, tall = .30, grey = .30, haze = .12, sheet = 1.0, sType = 1.0, sb = .50, st = 1.4,
        fog = .30, fogH = .35, rain = .85, wind = .90, pal = SkyPalette.RAIN, palK = .85)
    private val THUNDER = SkyState(cover = .10, tall = .30, grey = .30, haze = .14, sheet = 1.0, sType = 1.0, sb = .45, st = 1.6,
        fog = .25, fogH = .30, rain = 1.0, wind = 1.0, bolt = 1.0, pal = SkyPalette.STORM, palK = .9)

    fun bodySky(b: BodyInputs, hour: Double): BodySkyResult {
        val regime = SkyRegime.of(b.readiness)
        var sky = SkyState.weather(b.readiness)
        val notes = mutableListOf<Pair<String, Boolean>>() // (text, is the HRV note)

        // readiness: the regime
        if (b.readiness != null) {
            val word = when (regime) { SkyRegime.FAIR -> "Mostly clear"; SkyRegime.PARTLY -> "Partly cloudy"
                SkyRegime.STRATOCUMULUS -> "Broken cloud"; else -> "Low grey cloud" }
            val d = b.readinessUsual?.let { jsRound(b.readiness - it) }
            notes += "$word: readiness ${num(b.readiness)}${if (d != null) ", ${abs(d)} ${if (d >= 0) "above" else "below"} your normal" else ""}." to false
        } else notes += "A plain fair sky: there is no readiness score yet today." to false

        // sleep: morning valley fog
        var fog = 0.0
        b.sleepScore?.let { fog = max(fog, sstep(T.fogSleepScore.first, T.fogSleepScore.second, it)) }
        if (b.sleepMin != null && b.sleepUsual != null) fog = max(fog, sstep(T.fogDeficitMin.first, T.fogDeficitMin.second, b.sleepUsual - b.sleepMin))
        val morning = sstep(T.fogHours[0], T.fogHours[1], hour) * (1 - sstep(T.fogHours[2], T.fogHours[3], hour))
        val lFog = fog * morning
        if (lFog > .02) {
            sky = sky.copy(fog = max(sky.fog, .90 * lFog), fogH = .25, haze = max(sky.haze, .10 * lFog), wind = min(sky.wind, .15 + .5 * (1 - lFog)),
                pal = SkyPalette.FOG, palK = .85 * lFog)
            val less = if (b.sleepMin != null && b.sleepUsual != null) b.sleepUsual - b.sleepMin else null
            notes += ("Morning mist in the valley: you slept ${b.sleepMin?.let(::fmtMin) ?: "less well"}" +
                "${if (less != null && less > 5) ", ${jsRound(less)} min less than usual" else ""}. It clears by late morning.") to false
        }

        // HRV: the high cloud (hidden under stratus's full sheet, so no note there: VP1)
        var lHrv: Double? = null
        if (b.hrv != null && b.hrvMin != null && b.hrvMax != null) {
            val half = max(1.0, (b.hrvMax - b.hrvMin) / 2)
            val z = (b.hrv - b.hrvMin) / half
            val low = sstep(T.hrvLow.first, T.hrvLow.second, z)
            lHrv = low
            val veil = sstep(.6, 1.0, low)
            sky = sky.copy(cirrus = .30 + .62 * low, ciHook = if (low < .6) .9 * sstep(0.0, .5, low) else .9 * (1 - veil), ciVeil = veil, haze = max(sky.haze, .05 * veil))
            if (low < .05) sky = sky.copy(ciHook = 0.0)
            if (low > .05 && regime != SkyRegime.STRATUS) notes +=
                "${if (veil > .5) "A milky veil of high cloud" else "Streaks of high cloud"}: HRV ${num(b.hrv)} ms, under your normal ${num(b.hrvMin)}-${num(b.hrvMax)}." to true
        }

        // activity: cumulus building through the afternoon (only where there is cumulus to build)
        var pace: Double? = null
        if (b.steps != null && b.stepsUsual != null) { val dayFrac = clamp((hour - 7) / 14, .05, 1.0); pace = b.steps / max(1.0, b.stepsUsual * dayFrac) }
        if (b.strain != null && b.strainUsual != null) pace = max(pace ?: 0.0, b.strain / max(1.0, b.strainUsual))
        var lActivity: Double? = null
        if (pace != null) {
            val act = sstep(T.activity.first, T.activity.second, pace)
            val whenK = sstep(T.buildHours[0], T.buildHours[1], hour) * (1 - sstep(T.buildHours[2], T.buildHours[3], hour))
            val a = act * whenK * (if (regime == SkyRegime.FAIR || regime == SkyRegime.PARTLY) 1.0 else .4)
            lActivity = a
            if (a > .02) {
                sky = sky.copy(tall = min(1.0, sky.tall + .30 * a), cover = min(.75, sky.cover + .18 * a))
                notes += "Clouds building through the afternoon: ${if (b.steps != null) "${grouped(b.steps)} steps" else "strain ${num(b.strain!!)}"}, ahead of your usual pace." to false
            }
        }

        // skin temperature + resting HR: haze, then light rain (likely illness), then a storm (illness and readiness under 35)
        var lLoad: Double? = null; var lIll: Double? = null; var lStorm: Double? = null
        if (b.skinTemp != null && b.rhr != null && b.rhrUsual != null) {
            val dR = b.rhr - b.rhrUsual
            val hazy = b.skinTemp >= T.hazeSkin && dR >= T.hazeRhr
            val ill = b.skinTemp >= T.illSkin && dR >= T.illRhr
            val storm = ill && b.readiness != null && b.readiness < T.stormReadiness
            lLoad = if (hazy) 1.0 else 0.0; lIll = if (ill) 1.0 else 0.0; lStorm = if (storm) 1.0 else 0.0
            val signs = "skin temperature ${if (b.skinTemp >= 0) "+" else ""}${JsText.fixed1(b.skinTemp)} °C and resting HR ${num(b.rhr)} bpm (usual ${num(b.rhrUsual)}), both up"
            if (ill) notes.removeAll { it.second } // under rain the high cloud is hidden: say only what the sky shows
            when {
                storm -> {
                    sky = THUNDER.copy(style = sky.style, paintCov = sky.paintCov, ciHook = sky.ciHook, ciVeil = sky.ciVeil, ciAng = sky.ciAng,
                        snow = sky.snow, cirrus = 0.0)
                    notes += "A storm: $signs, and readiness ${num(b.readiness!!)}. This can mean your body is fighting something; rest if you can." to false
                }
                ill -> {
                    sky = RAIN.copy(style = sky.style, paintCov = sky.paintCov, ciHook = sky.ciHook, ciVeil = sky.ciVeil, ciAng = sky.ciAng,
                        snow = sky.snow, bolt = sky.bolt, cirrus = 0.0, fog = max(RAIN.fog, sky.fog * .6))
                    notes += "Light rain: $signs. This can be an early sign of illness." to false
                }
                hazy -> {
                    sky = sky.copy(haze = max(sky.haze, .30), grey = max(sky.grey, .16), cirrus = max(sky.cirrus, .55), ciVeil = max(sky.ciVeil, .45))
                    notes += "A humid haze: $signs. Your body is under some load." to false
                }
            }
        }

        val texts = notes.map { it.first }
        return BodySkyResult(sky, regime, texts, "Sky: " + texts.joinToString(" "), lFog, lHrv, lActivity, lLoad, lIll, lStorm)
    }
}
