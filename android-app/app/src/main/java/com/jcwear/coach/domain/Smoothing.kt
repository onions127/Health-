package com.jcwear.coach.domain

typealias Segment = List<Pair<Int, Double>>

/** Plan 2.2: smoothed lines over real points only. A segment is a list of (dayIndex, value); segments never bridge gaps. */
object Smoothing {
    /** Week = raw only, Month = 3-day, 3M = 7-day, Year = weekly averages. */
    fun forRange(values: List<Double?>, range: String): List<Segment> = when (range) {
        "Week" -> emptyList()
        "Month" -> rolling(values, 3)
        "3M" -> rolling(values, 7)
        else -> weekly(values)
    }

    fun label(range: String): String? = when (range) {
        "Week" -> null
        "Month" -> "3-day average"
        "3M" -> "7-day average"
        else -> "Weekly average"
    }

    /** Trailing mean on each real day whose window holds >= 2 real values; breaks across > 1 missing day. */
    fun rolling(values: List<Double?>, window: Int): List<Segment> = split(
        values.indices.mapNotNull { i ->
            if (values[i] == null) return@mapNotNull null
            val w = values.subList((i - window + 1).coerceAtLeast(0), i + 1).filterNotNull()
            if (w.size >= 2) i to w.average() else null
        },
        maxStep = 2
    )

    /** 7-day buckets ending on the last day; a point at each bucket's middle; a bucket with < 2 values breaks the line. */
    fun weekly(values: List<Double?>): List<Segment> = split(
        (values.lastIndex downTo 0 step 7).mapNotNull { end ->
            val w = values.subList((end - 6).coerceAtLeast(0), end + 1).filterNotNull()
            if (w.size >= 2) (end - 3).coerceAtLeast(0) to w.average() else null
        }.reversed(),
        maxStep = 7
    )

    private fun split(points: List<Pair<Int, Double>>, maxStep: Int): List<Segment> {
        val out = mutableListOf<MutableList<Pair<Int, Double>>>()
        for (p in points) {
            val last = out.lastOrNull()?.last()
            if (last == null || p.first - last.first > maxStep) out += mutableListOf(p) else out.last() += p
        }
        return out
    }
}

/** Plan 2.4: band distance; if missing or 0 while steps > 0, steps x stride (height x 0.414); null without height. */
fun distanceKm(steps: Int?, bandKm: Double?, heightCm: Double?): Double? = when {
    bandKm != null && bandKm > 0 -> bandKm
    steps == null || steps <= 0 || heightCm == null -> null
    else -> steps * heightCm * 0.414 / 100_000.0
}
