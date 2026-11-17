package com.jcwear.coach.domain

/** Plan 3.3: profile entry in the user's units (cm / in); null when blank, not a number or out of range. */
fun parseHeightCm(text: String, metric: Boolean): Double? =
    text.trim().toDoubleOrNull()?.let { if (metric) it else it * 2.54 }?.takeIf { it in 100.0..250.0 }

/** kg / lb in; kg out. */
fun parseWeightKg(text: String, metric: Boolean): Double? {
    val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    // The range is checked in the unit that was typed, so 66.1 lb is not lost to rounding.
    return if (metric) v.takeIf { it in 30.0..300.0 } else v.takeIf { it in 66.1..661.4 }?.let { it * 0.45359237 }
}

/** R5: one logged weight for a day, kept as a full double in kg. */
data class WeightLog(val weightKg: Double, val loggedAtMs: Long)

/**
 * R5: the weight behind [day]'s estimated calories: the newest log on or before the day, else the earliest log,
 * else the profile weight. Older days do not move when today's weight changes.
 */
fun calorieWeightKg(day: java.time.LocalDate, logs: java.util.TreeMap<java.time.LocalDate, WeightLog>, profileKg: Double?): Double? =
    (logs.floorEntry(day) ?: logs.firstEntry())?.value?.weightKg ?: profileKg

/** D10: band calories when present, else steps x weight (kg) x 0.0005; second = estimated. */
fun caloriesKcal(steps: Int?, bandKcal: Double?, weightKg: Double?): Pair<Double, Boolean>? = when {
    bandKcal != null && bandKcal > 0 -> bandKcal to false
    steps == null || steps <= 0 || weightKg == null -> null
    else -> steps * weightKg * 0.0005 to true
}
