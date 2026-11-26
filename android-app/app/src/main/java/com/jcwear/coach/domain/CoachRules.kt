package com.jcwear.coach.domain

import com.jcwear.coach.domain.sky.BodyInputs
import com.jcwear.coach.domain.sky.BodySky
import com.jcwear.coach.domain.sky.JsText.fixed1
import com.jcwear.coach.domain.sky.JsText.grouped
import com.jcwear.coach.domain.sky.JsText.jsRound
import com.jcwear.coach.domain.sky.JsText.num
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Coach's read over the sky: one sentence and a detail line with the numbers it rests on (design rounds CR1, FW1, PS1,
 * TW1). One pure function from the body and the hour, on BodySky's own thresholds, so the sky and the read never
 * disagree. First match wins, per part of the day:
 *  morning (to 12:00) the plan · afternoon (to 18:00) progress against your usual day ·
 *  dusk (to 21:30) how hard the day was · night: bedtime against your usual.
 * Works offline. Parity with design-prototype/coach-rules.js is tested (parity/coach_rules.tsv).
 */
object CoachRules {
    /** FW1: the app's minimums: readiness from day 4, a normal from day 8, a usual bedtime after 5 nights. */
    object FirstWeek { const val readiness = 4; const val normal = 8; const val bedtime = 5 }
    object T {
        const val rest = 50.0; const val easy = 65.0; const val ready = 80.0
        const val ahead = 1.15; const val behind = 0.85; const val hard = 1.15; const val light = 0.85
        const val short = 30.0; const val early = 30.0
    }

    data class Read(val rule: String, val line: String, val detail: String)

    private data class Signs(val dR: Double?, val haze: Boolean, val ill: Boolean, val storm: Boolean)

    private fun hm(m: Double) = "${floor(m / 60).toLong()}h ${jsRound(m % 60).toString().padStart(2, '0')}m"
    private fun clock(h: Double) = "${floor(h).toLong().toString().padStart(2, '0')}:${jsRound((h % 1) * 60).toString().padStart(2, '0')}"
    private fun at(mfn: Double): String {
        val t = ((jsRound(mfn / 5) * 5) + 720) % 1440
        return "${(t / 60).toString().padStart(2, '0')}:${(t % 60).toString().padStart(2, '0')}"
    }
    private fun dayFrac(h: Double) = min(1.0, max(.05, (h - 7) / 14)) // the usual day accrues 07:00 → 21:00

    private fun body(b: BodyInputs): Signs {
        if (b.rhrUsual == null || b.skinTemp == null || b.rhr == null) return Signs(null, false, false, false) // FW1: no usual yet
        val dR = b.rhr - b.rhrUsual
        val ill = b.skinTemp >= BodySky.T.illSkin && dR >= BodySky.T.illRhr
        return Signs(dR, b.skinTemp >= BodySky.T.hazeSkin && dR >= BodySky.T.hazeRhr, ill,
            ill && b.readiness != null && b.readiness < BodySky.T.stormReadiness)
    }

    private fun hrvWord(b: BodyInputs): String {
        if (b.hrvMin == null || b.hrvMax == null || b.hrv == null) return "your normal from day ${FirstWeek.normal}"
        val half = max(1.0, (b.hrvMax - b.hrvMin) / 2)
        return when {
            b.hrv < b.hrvMin - half -> "well under your normal"
            b.hrv < b.hrvMin -> "below your normal"
            b.hrv > b.hrvMax -> "above your normal"
            b.hrv == b.hrvMax -> "at the top of your normal"
            else -> "within your normal"
        }
    }

    fun read(b0: BodyInputs, hour: Double): Read {
        val b = b0.copy(steps = b0.steps ?: 0.0, strain = b0.strain ?: 0.0) // no step data yet reads as 0
        val steps = b.steps!!; val strain = b.strain!!
        val s = body(b); val r = b.readiness
        val signs = if (s.dR == null) "" else "Skin +${fixed1(b.skinTemp!!)} °C · resting HR ${num(b.rhr!!)}, ${num(s.dR)} above usual"
        val rh = "Readiness ${r?.let(::num)} · HRV ${b.hrv?.let(::num)} ms, ${hrvWord(b)}"
        val none = r == null
        val sl = if (b.sleepMin != null) "Last night ${hm(b.sleepMin)}${if (b.sleepScore != null) ", sleep score ${num(b.sleepScore)}" else ""}" else ""

        if (hour < 12) {
            if (none) return Read("am.first", "Getting to know you.", "${if (b.sleepScore != null) "Sleep score ${num(b.sleepScore)}" else sl} · readiness from day ${FirstWeek.readiness}")
            if (s.storm) return Read("am.storm", "Rest. Your body needs it.", signs)
            if (s.ill) return Read("am.ill", "Rest, and see how you feel.", signs)
            if (r!! < T.rest) return Read("am.rest", "Rest today.", rh)
            if (r < T.easy) return Read("am.easy", "Take it easy today.", rh)
            if (s.haze) return Read("am.haze", "Keep it moderate today.", signs)
            if (r < T.ready) return Read("am.steady", "Train, but not to the limit.", rh)
            return Read("am.ready", "A good day to train hard.", rh)
        }
        if (hour < 18) {
            val known = b.stepsUsual != null
            val p = if (known) steps / max(1.0, b.stepsUsual!! * dayFrac(hour)) else 1.0
            val pace = if (p >= T.ahead) "ahead" else if (p < T.behind) "behind" else "on"
            val d = "${grouped(steps)} steps by ${clock(hour)} · strain ${num(strain)}${if (known) ", your usual ${b.strainUsual?.let(::num)}" else ""}"
            if (s.storm) return Read("pm.storm", "Rest is the plan today.", d)
            if (s.ill) return Read("pm.ill", "Keep it a rest day.", d)
            if (none || !known) { // FW1: no pace without a usual day
                if (!none && r!! < T.rest) return Read("pm.rest", "Resting so far. Good.", d)
                if (!none && r!! < T.easy) return Read("pm.easy", "Easy so far. Keep it that way.", d)
                return Read("pm.first", "Learning your usual day.", d)
            }
            if (r!! < T.easy && pace == "ahead") return Read("pm.over", "More than planned. Ease off now.", d)
            if (r < T.rest) return Read("pm.rest", "Resting so far. Good.", d)
            if (r < T.easy) return Read("pm.easy", "Easy so far. Keep it that way.", d)
            if (s.haze) return Read("pm.haze.$pace", when (pace) {
                "ahead" -> "Ahead of pace. Keep it moderate."; "on" -> "On pace. Keep it moderate."; else -> "A quiet day. Keep it moderate." }, d)
            return Read("pm.$pace", when (pace) {
                "ahead" -> "Ahead of your usual day. Keep it going."; "on" -> "On pace for your usual day."
                else -> "Behind your usual day. A walk would help." }, d)
        }
        if (hour < 21.5) {
            if (none || b.strainUsual == null) { // FW1
                val d0 = "Strain ${num(strain)}${if (none) "" else " · readiness was ${num(r!!)}"}"
                if (!none && r!! < T.rest) return Read("dusk.rest", "A quiet day. Sleep early.", d0)
                if (!none && r!! < T.easy) return Read("dusk.easy", "An easy day. Sleep early.", d0)
                return Read("dusk.first", "A day done. Sleep on time.", d0)
            }
            val q = strain / max(1.0, b.strainUsual * dayFrac(hour))
            val load = if (q >= T.hard) "hard" else if (q < T.light) "light" else "full"
            val d = "Strain ${num(strain)}, ${when (load) { "hard" -> "above"; "light" -> "under"; else -> "near" }} your usual ${num(b.strainUsual)} · readiness was ${num(r!!)}"
            if (s.storm) return Read("dusk.storm", "Wind down early tonight.", d)
            if (s.ill) return Read("dusk.ill", "An early night helps most.", d)
            if (r < T.rest) return Read("dusk.rest", "A quiet day. Sleep early.", d)
            if (r < T.easy) return Read("dusk.easy", "An easy day. Sleep early.", d)
            if (s.haze) return Read("dusk.haze", "A fair day. Sleep on time.", d)
            return Read("dusk.$load", when (load) {
                "hard" -> "A hard day. Ease off now."; "full" -> "A full day. Sleep on time."; else -> "A light day. Sleep on time." }, d)
        }
        if (b.onsetUsual == null) { // FW1: fewer than 5 nights: no usual bedtime yet
            if (!none && r!! < T.rest) return Read("night.rest", "Bed now, if you can.", "Last night ${b.sleepMin?.let(::hm)}")
            return Read("night.first", "Aim for seven and a half hours.", "Your usual bedtime after ${FirstWeek.bedtime} nights · ${b.nights ?: 0} so far")
        }
        val usual = "you usually fall asleep at ${at(b.onsetUsual)}"
        val last = "Last night ${b.sleepMin?.let(::hm)} · $usual"
        val short = b.sleepUsual != null && b.sleepMin != null && b.sleepUsual - b.sleepMin >= T.short
        if (s.storm) return Read("night.storm", "Sleep now if you can.", last)
        if (s.ill) return Read("night.ill", "Bed early. Rest helps.", last)
        if ((r ?: 0.0) < T.rest) return Read("night.rest", "Bed now, if you can.", last) // no readiness at all: rest first, as the prototype
        if (short) return Read("night.short", "Bed by ${at(b.onsetUsual - T.early)} tonight.", "Half an hour early: last night was short, ${hm(b.sleepMin!!)}")
        return Read("night.usual", "Bed by ${at(b.onsetUsual)} tonight.", "Keeps your sleep on track · $usual")
    }
}
