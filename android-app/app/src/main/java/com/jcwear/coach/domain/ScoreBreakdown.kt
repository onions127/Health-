package com.jcwear.coach.domain

import kotlin.math.roundToInt

enum class PartUnit { PERCENT, POINTS }

/** One input of a score: contributes weight × value. PERCENT parts are 0-100 values with a % weight. */
data class ScorePart(val label: String, val weight: Double, val value: Double, val unit: PartUnit = PartUnit.PERCENT) {
    val points: Double get() = weight * value
}

/** A score and the parts it is made of, so the "how is this calculated" sheet can never disagree with the number. */
data class ScoreBreakdown(val parts: List<ScorePart>, val cap: Int? = null, val basis: String) {
    val score: Int get() = parts.sumOf { it.points }.roundToInt().coerceIn(0, cap ?: 100)
}

fun recoveryParts(hrv: Double, rhr: Double) = ScoreBreakdown(
    listOf(
        ScorePart("HRV ${hrv.roundToInt()} ms × 0.92", 0.92, hrv, PartUnit.POINTS),
        ScorePart("Resting HR: (90 − ${rhr.roundToInt()}) × 0.72", 0.72, 90 - rhr, PartUnit.POINTS)
    ),
    basis = "HRV and resting HR, today"
)

fun dailyParts(readiness: Int, sleepScore: Int, strain100: Int) = ScoreBreakdown(
    listOf(
        ScorePart("Readiness", 0.42, readiness.toDouble()),
        ScorePart("Sleep", 0.36, sleepScore.toDouble()),
        ScorePart("Strain", 0.22, strain100.toDouble())
    ),
    basis = "Readiness, sleep and strain"
)
