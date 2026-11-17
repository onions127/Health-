package com.jcwear.coach.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** One stored 60-second morning check (R1). [source] says whether the band measured HRV or only heart rate. */
data class MorningCheck(val rmssdMs: Double, val measuredAtMs: Long, val source: String) {
    val estimated: Boolean get() = source == SOURCE_HR_ESTIMATE

    companion object {
        const val SOURCE_HRV = "hrv"
        const val SOURCE_HR_ESTIMATE = "hr_estimate"

        /** Same range as overnight HRV records; anything outside is a failed check. */
        val VALID_RMSSD_MS = 15.0..200.0
    }
}

/** The day a check belongs to: the phone's local date at the moment it finished. */
fun localDateOf(measuredAtMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(measuredAtMs).atZone(zone).toLocalDate()

/** Today's done line, e.g. "Morning check · 07:12 · HRV 62 ms" (plus "estimated" when the band sent heart rate only). */
fun morningCheckDoneLine(check: MorningCheck, zone: ZoneId): String {
    val time = Instant.ofEpochMilli(check.measuredAtMs).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))
    return "Morning check · $time · HRV ${check.rmssdMs.roundToInt()} ms" + if (check.estimated) " · estimated" else ""
}

/** HRV detail header for a day with a check; null when there is none. */
fun hrvCheckHeader(day: DayMetrics): String? {
    val check = day.morningRmssdMs?.roundToInt() ?: return null
    val overnight = day.hrvMs?.let { "Overnight ${it.roundToInt()} ms · " } ?: ""
    return "${overnight}Morning check $check ms (used for that day's scores)"
}
