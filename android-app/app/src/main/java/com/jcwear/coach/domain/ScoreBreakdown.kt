package com.jcwear.coach.domain

import kotlin.math.roundToInt

enum class PartUnit { PERCENT, POINTS }

/** One input of a score: contributes weight × value. PERCENT parts are 0-100 values with a % weight. */
data class ScorePart(
    val label: String, val weight: Double, val value: Double, val unit: PartUnit = PartUnit.PERCENT,
    /** R4: the plain comparison behind the value ("Resting HR 58, your average 55"). */
    val detail: String? = null,
    /** False for a part with no data that day: shown greyed, weight 0, left out of the score. */
    val counted: Boolean = true
) {
    val points: Double get() = weight * value
}

/** A score and the parts it is made of, so the "how is this calculated" sheet can never disagree with the number. */
data class ScoreBreakdown(
    val parts: List<ScorePart>, val cap: Int? = null, val basis: String,
    /** R4: Readiness divides by the sum of the weights that are present, and never shows below 1. */
    val divisor: Double = 1.0, val floor: Int = 0
) {
    val score: Int get() = (parts.sumOf { it.points } / divisor).roundToInt().coerceIn(floor, cap ?: 100)

    /**
     * Whole points per part that always add up to [score] (largest remainder); null for parts that are not counted.
     */
    fun shownPoints(): List<Int?> {
        val exact = parts.map { if (it.counted) it.points / divisor else null }
        val total = exact.filterNotNull().sum()
        val scaled = exact.map { e -> e?.let { if (total > 0.0) it * score / total else 0.0 } }
        val floors = scaled.map { it?.let { v -> kotlin.math.floor(v).toInt() } }
        var left = score - floors.filterNotNull().sum()
        val order = scaled.indices.filter { scaled[it] != null }.sortedByDescending { scaled[it]!! - floors[it]!! }
        val out = floors.toMutableList()
        for (i in order) { if (left <= 0) break; out[i] = out[i]!! + 1; left-- }
        return out
    }
}

const val MIN_READINESS_BASE_DAYS = 3

/**
 * R4: Readiness and its parts. Null without HRV, resting HR and both averages.
 * Sleep and vitals count when present; the weights that are present are scaled up to 100%.
 * [hrvNote] labels an HRV that is not the overnight average ("morning check").
 */
fun readinessParts(
    hrv: Double?, rhr: Double?, hrvBase: Double?, rhrBase: Double?,
    sleepScore: Int?, spo2: Double?, tempDelta: Double?, hrvNote: String? = null
): ScoreBreakdown? {
    if (hrv == null || rhr == null || hrvBase == null || rhrBase == null) return null
    val parts = mutableListOf(
        ScorePart(
            "HRV vs your average", 0.40, (75.0 + (hrv / hrvBase.coerceAtLeast(1.0) - 1.0) * 125.0).coerceIn(20.0, 100.0),
            detail = "HRV ${hrv.roundToInt()} ms${hrvNote?.let { " ($it)" } ?: ""} vs your ${hrvBase.roundToInt()} ms"
        ),
        ScorePart(
            "Resting HR vs your average", 0.25, (78.0 + (rhrBase - rhr) * 4.5).coerceIn(20.0, 100.0),
            detail = "Resting HR ${rhr.roundToInt()}, your average ${rhrBase.roundToInt()}"
        )
    )
    parts += if (sleepScore != null) ScorePart("Sleep", 0.25, sleepScore.toDouble(), detail = "Sleep score $sleepScore")
    else ScorePart("Sleep", 0.0, 0.0, counted = false)
    parts += if (spo2 != null || tempDelta != null) {
        val tempFar = tempDelta != null && kotlin.math.abs(tempDelta) > 0.5
        val penalty = (if (tempFar) 25.0 else 0.0) + (if (spo2 != null && spo2 < 95.0) (95.0 - spo2) * 8.0 else 0.0)
        ScorePart(
            "Blood oxygen and skin temp", 0.10, (95.0 - penalty).coerceIn(20.0, 100.0),
            detail = listOfNotNull(
                spo2?.let { "SpO₂ ${it.roundToInt()}%" + if (it < 95.0) " (below 95%)" else "" },
                tempDelta?.let { if (tempFar) "skin temp far from your average" else "skin temp near your average" }
            ).joinToString(" · ")
        )
    } else ScorePart("Blood oxygen and skin temp", 0.0, 0.0, counted = false)
    return ScoreBreakdown(
        parts, basis = "HRV, resting HR, sleep and vitals",
        divisor = parts.filter { it.counted }.sumOf { it.weight }, floor = 1
    )
}

/** What stands in for the Readiness sheet link when there is no score; null when there is one. */
fun readinessMissingLine(day: DayMetrics, days: List<DayMetrics>): String? {
    if (day.readinessParts != null) return null
    if (day.scoreHrvMs == null || day.rhrBpm == null) return "No HRV or resting HR for this day"
    val prior = days.filter { it.date.isBefore(day.date) && !it.date.isBefore(day.date.minusDays(30)) }
    val n = minOf(prior.count { it.hrvMs != null }, prior.count { it.rhrBpm != null })
    return "Building your average · $n of $MIN_READINESS_BASE_DAYS nights"
}

/** The breakdown behind a score metric on [day]; null for metrics that have none. */
fun partsFor(metricId: String, day: DayMetrics): ScoreBreakdown? = when (metricId) {
    "daily" -> day.dailyParts
    "recovery" -> day.recoveryParts
    "readiness" -> day.readinessParts
    "sleepScore" -> day.sleepParts
    else -> null
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
