package com.jcwear.coach.domain

/** Plan 3.3: profile entry in the user's units (cm / in); null when blank, not a number or out of range. */
fun parseHeightCm(text: String, metric: Boolean): Double? =
    text.trim().toDoubleOrNull()?.let { if (metric) it else it * 2.54 }?.takeIf { it in 100.0..250.0 }

/** kg / lb in; kg out. */
fun parseWeightKg(text: String, metric: Boolean): Double? =
    text.trim().toDoubleOrNull()?.let { if (metric) it else it * 0.45359237 }?.takeIf { it in 30.0..300.0 }

/** D10: band calories when present, else steps x weight (kg) x 0.0005; second = estimated. */
fun caloriesKcal(steps: Int?, bandKcal: Double?, weightKg: Double?): Pair<Double, Boolean>? = when {
    bandKcal != null && bandKcal > 0 -> bandKcal to false
    steps == null || steps <= 0 || weightKg == null -> null
    else -> steps * weightKg * 0.0005 to true
}
