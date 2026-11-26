package com.jcwear.coach.domain.sky

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Today's body, as the sky (BodySky) and Coach's read (CoachRules) take it. Every field may be missing (a new band, a
 * missed night): the rules then say less, never something the numbers do not. The usuals are the person's own
 * (30 days before; `onsetUsual` = median sleep time of the last 14 nights, minutes from noon).
 * Ported from the design prototype (design-prototype/body-sky.js, coach-rules.js) at lock, 2026-11-24.
 */
data class BodyInputs(
    val readiness: Double? = null, val readinessUsual: Double? = null,
    val sleepScore: Double? = null, val sleepMin: Double? = null, val sleepUsual: Double? = null,
    val hrv: Double? = null, val hrvMin: Double? = null, val hrvMax: Double? = null,
    val steps: Double? = null, val stepsUsual: Double? = null,
    val strain: Double? = null, val strainUsual: Double? = null,
    val skinTemp: Double? = null, val rhr: Double? = null, val rhrUsual: Double? = null,
    val onsetUsual: Double? = null, val nights: Int? = null
)

/** Number formatting the prototype's sentences use (JavaScript's), so the app says exactly what the boards showed. */
internal object JsText {
    /** A number as JavaScript prints it: 90, 685.5, 0.3. */
    fun num(x: Double): String =
        if (x == floor(x) && abs(x) < 1e15) x.toLong().toString() else x.toString()

    /** Math.round(x) then toLocaleString('en-US'): 8,420. */
    fun grouped(x: Double): String = String.format(Locale.US, "%,d", jsRound(x))

    /** JavaScript's Math.round: half rounds up (toward +∞). */
    fun jsRound(x: Double): Long = floor(x + 0.5).toLong()

    /** Number.prototype.toFixed(1). */
    fun fixed1(x: Double): String = String.format(Locale.US, "%.1f", x)
}
