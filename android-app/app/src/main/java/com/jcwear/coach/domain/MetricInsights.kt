package com.jcwear.coach.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Direction { HIGHER_IS_BETTER, LOWER_IS_BETTER, CLOSER_TO_ZERO_IS_BETTER, NEUTRAL }
enum class Status { GOOD, BAD, NORMAL, ABOVE, BELOW }

/** One metric's rules, shared by Today, Trends, Detail and the coach (plan 1.2). */
data class MetricDef(
    val id: String,
    val label: String,
    val unit: String,
    val direction: Direction,
    val decimals: Int,
    /** D14: the normal range is never narrower than this (given the baseline mean). */
    val minNormalHalfWidth: (mean: Double) -> Double,
    val extract: (DayMetrics) -> Double?
) {
    fun format(v: Double): String = when {
        id == "sleep" -> "${v.roundToInt() / 60}h ${(v.roundToInt() % 60).toString().padStart(2, '0')}m"
        id == "skinTemp" -> String.format(Locale.US, "%+.1f °C", v)
        id == "steps" -> String.format(Locale.US, "%,d", v.roundToInt())
        unit == "%" -> String.format(Locale.US, "%.${decimals}f%%", v)
        unit.isEmpty() -> String.format(Locale.US, "%.${decimals}f", v)
        else -> String.format(Locale.US, "%.${decimals}f %s", v, unit)
    }
}

object Metrics {
    private val score3: (Double) -> Double = { 3.0 }
    val daily = MetricDef("daily", "Daily score", "", Direction.HIGHER_IS_BETTER, 0, score3) { it.daily?.toDouble() }
    val recovery = MetricDef("recovery", "Recovery", "", Direction.HIGHER_IS_BETTER, 0, score3) { it.recovery?.toDouble() }
    val readiness = MetricDef("readiness", "Readiness", "", Direction.HIGHER_IS_BETTER, 0, score3) { it.readiness?.toDouble() }
    val sleepScore = MetricDef("sleepScore", "Sleep score", "", Direction.HIGHER_IS_BETTER, 0, score3) { it.sleepScore?.toDouble() }
    val sleep = MetricDef("sleep", "Sleep", "min", Direction.HIGHER_IS_BETTER, 0, { 20.0 }) { it.sleepMin?.toDouble() }
    val hrv = MetricDef("hrv", "HRV", "ms", Direction.HIGHER_IS_BETTER, 0, { 3.0 }) { it.hrvMs }
    val rhr = MetricDef("rhr", "Resting HR", "bpm", Direction.LOWER_IS_BETTER, 0, { 2.0 }) { it.rhrBpm }
    val steps = MetricDef("steps", "Steps", "steps", Direction.HIGHER_IS_BETTER, 0, { m -> m * 0.10 }) { it.steps?.toDouble() }
    val spo2 = MetricDef("spo2", "Blood oxygen", "%", Direction.HIGHER_IS_BETTER, 0, { 1.0 }) { it.spo2Pct }
    val skinTemp = MetricDef("skinTemp", "Skin temp", "°C", Direction.CLOSER_TO_ZERO_IS_BETTER, 1, { 0.2 }) { it.skinTempDeltaC }
    val strain = MetricDef("strain", "Strain", "", Direction.NEUTRAL, 0, score3) { it.strain100?.toDouble() }

    val all = listOf(daily, recovery, readiness, sleepScore, sleep, hrv, rhr, steps, spo2, skinTemp, strain)
    fun byId(id: String): MetricDef = all.firstOrNull { it.id == id } ?: hrv
}

data class Baseline(val mean: Double, val halfWidth: Double, val n: Int) {
    val low get() = mean - halfWidth
    val high get() = mean + halfWidth

    /** Normal band in the metric's own units (closer-to-zero metrics are centred on 0). */
    fun band(def: MetricDef): Pair<Double, Double> =
        if (def.direction == Direction.CLOSER_TO_ZERO_IS_BETTER) -high to high else low to high
}

const val MIN_BASELINE_DAYS = 7

object MetricInsights {
    /** Values of [def] on the days before [day] (newest last), at most [window] days back. */
    private fun priorValues(def: MetricDef, days: List<DayMetrics>, day: LocalDate, window: Long = 30) =
        days.filter { it.date.isBefore(day) && !it.date.isBefore(day.minusDays(window)) }.mapNotNull(def.extract)

    /** Days with a value in the 30 days before [day]: drives "Building your baseline: n of 7". */
    fun baselineDays(def: MetricDef, days: List<DayMetrics>, day: LocalDate) = priorValues(def, days, day).size

    /** Mean and normal half-width of the 30 days before [day] (today excluded); needs >= 7 values. */
    fun baseline(def: MetricDef, days: List<DayMetrics>, day: LocalDate): Baseline? {
        val raw = priorValues(def, days, day)
        if (raw.size < MIN_BASELINE_DAYS) return null
        val v = if (def.direction == Direction.CLOSER_TO_ZERO_IS_BETTER) raw.map { abs(it) } else raw
        val mean = v.average()
        val sd = sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
        return Baseline(mean, max(sd, def.minNormalHalfWidth(mean)), v.size)
    }

    fun status(def: MetricDef, value: Double, base: Baseline): Status {
        val v = if (def.direction == Direction.CLOSER_TO_ZERO_IS_BETTER) abs(value) else value
        if (abs(v - base.mean) <= base.halfWidth) return Status.NORMAL
        val above = v > base.mean
        return when (def.direction) {
            Direction.HIGHER_IS_BETTER -> if (above) Status.GOOD else Status.BAD
            Direction.LOWER_IS_BETTER, Direction.CLOSER_TO_ZERO_IS_BETTER -> if (above) Status.BAD else Status.GOOD
            Direction.NEUTRAL -> if (above) Status.ABOVE else Status.BELOW
        }
    }

    /** "Highest in 5 weeks" / "Lowest in 9 days" / "Highest on record", only for out-of-normal values. */
    fun extremeLabel(def: MetricDef, days: List<DayMetrics>, day: LocalDate): String? {
        val today = days.firstOrNull { it.date == day } ?: return null
        val v = def.extract(today) ?: return null
        val base = baseline(def, days, day) ?: return null
        if (status(def, v, base) == Status.NORMAL) return null
        val high = (if (def.direction == Direction.CLOSER_TO_ZERO_IS_BETTER) abs(v) else v) > base.mean
        val word = if (high) "Highest" else "Lowest"
        val beaten = days.filter { it.date.isBefore(day) }.lastOrNull { d ->
            val x = def.extract(d) ?: return@lastOrNull false
            if (def.direction == Direction.CLOSER_TO_ZERO_IS_BETTER) {
                if (high) abs(x) >= abs(v) else abs(x) <= abs(v)
            } else if (high) x >= v else x <= v
        } ?: return "$word on record"
        val gap = ChronoUnit.DAYS.between(beaten.date, day)
        return if (gap < 14) "$word in $gap days" else "$word in ${gap / 7} weeks"
    }

    /** Chip under a value: "↑ 12% vs your normal", "Within normal", or the baseline progress. */
    fun chip(def: MetricDef, days: List<DayMetrics>, day: LocalDate): Pair<String, Status> {
        val v = days.firstOrNull { it.date == day }?.let(def.extract) ?: return "No data" to Status.NORMAL
        val base = baseline(def, days, day)
            ?: return "Building your baseline: ${baselineDays(def, days, day)} of $MIN_BASELINE_DAYS days" to Status.NORMAL
        val st = status(def, v, base)
        if (st == Status.NORMAL) return "Within normal" to st
        val text = if (def.direction == Direction.CLOSER_TO_ZERO_IS_BETTER) {
            String.format(Locale.US, "%+.1f °C vs your normal", v)
        } else {
            val pct = ((v - base.mean) / base.mean.coerceAtLeast(1e-6) * 100).roundToInt()
            "${if (pct >= 0) "↑" else "↓"} ${abs(pct)}% vs your normal"
        }
        return text to st
    }
}

/** Plan 1.6: time-of-day greeting; [name] blank = no name. */
fun greetingFor(time: LocalTime, name: String = ""): String {
    val base = when (time.hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Hi"
    }
    return if (name.isBlank()) base else "$base, $name"
}

/** One-line coach verdict from the real recovery score. */
fun verdictFor(recovery: Int?): String = when {
    recovery == null -> "Not enough data for advice yet"
    recovery >= 70 -> "Well recovered. Good day for hard training."
    recovery >= 40 -> "Moderate recovery. Keep effort steady."
    else -> "Low recovery. Keep effort light."
}
