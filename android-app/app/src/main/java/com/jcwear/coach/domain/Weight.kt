package com.jcwear.coach.domain

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "3 Nov". */
fun dayMonth(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))

/** The Material date picker reports the picked day as UTC midnight, whatever the phone's zone is. */
fun pickerMillisToLocalDate(utcMillis: Long): LocalDate = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

/**
 * R5 (T4a): the smoothed weight line. One point per logged day (index into [days]); its value is the mean of all logs
 * in the 7 calendar days ending that day. [days] must be consecutive, as buildDaySeries returns them. Bridges gaps.
 */
fun weightLine(days: List<DayMetrics>): Segment = days.mapIndexedNotNull { i, d ->
    if (d.weightKg == null) null else i to days.subList((i - 6).coerceAtLeast(0), i + 1).mapNotNull { it.weightKg }.average()
}

/** Latest line value minus the line value at the newest log [minDaysBack] or more days earlier, with that log's date. */
fun weightChange(days: List<DayMetrics>, minDaysBack: Int): Pair<Double, LocalDate>? {
    val line = weightLine(days)
    val last = line.lastOrNull() ?: return null
    val anchor = line.lastOrNull { last.first - it.first >= minDaysBack } ?: return null
    return last.second - anchor.second to days[anchor.first].date
}

/** "-0.6 kg since 3 Oct"; null without a log 7 or more days before the latest one. */
fun weightChangeText(days: List<DayMetrics>, units: UnitSystem): String? =
    weightChange(days, 7)?.let { (kg, since) -> "${Units.weightDiff(kg, units)} since ${dayMonth(since)}" }

/** Coach context (always kg): latest weight and the change against the newest log 30 or more days earlier. */
fun weightCoachLine(days: List<DayMetrics>): String? {
    val latest = days.lastOrNull { it.weightKg != null } ?: return null
    val change = weightChange(days, 30)?.let { (kg, since) -> " (${Units.weightDiff(kg, UnitSystem.SI)} since $since, 7-day averages)" } ?: ""
    return "Weight: ${Units.weight(latest.weightKg!!, UnitSystem.SI)} logged ${latest.date}$change."
}

/**
 * R5: everything the log sheet decides, without any UI. The unit system is captured when the sheet opens,
 * so toggling units elsewhere never reinterprets a number that is being typed.
 */
class WeightLogSheetState(val units: UnitSystem, days: List<DayMetrics>, initialDate: LocalDate, profileKg: Double?) {
    val logs: Map<LocalDate, WeightLog> = days.mapNotNull { d -> d.weightLog?.let { d.date to it } }.toMap()
    val firstDay: LocalDate = days.first().date
    val today: LocalDate = days.last().date
    private val latestKg: Double? = days.lastOrNull { it.weightLog != null }?.weightKg ?: profileKg

    var date by mutableStateOf(initialDate.coerceIn(firstDay, today)); private set
    var text by mutableStateOf(prefill(date))
    var saveError by mutableStateOf<String?>(null); private set

    val parsedKg: Double? get() = parseWeightKg(text, units == UnitSystem.SI)
    val canSave: Boolean get() = parsedKg != null
    val rangeError: String?
        get() = if (text.isBlank() || canSave) null else if (units == UnitSystem.SI) "Enter 30.0 to 300.0 kg" else "Enter 66.1 to 661.4 lb"
    val replacesLine: String? get() = logs[date]?.let { "Replaces ${Units.weight(it.weightKg, units)}" }
    val button: String get() = if (logs[date] != null) "Update" else "Save"
    val dateLabel: String get() = if (date == today) "Today" else dayMonth(date)

    fun pickDate(d: LocalDate) {
        date = d.coerceIn(firstDay, today)
        text = prefill(date)
        saveError = null
    }

    /** The typed text stays; the sheet stays open. */
    fun saveFailed(kg: Double) {
        saveError = "Couldn't save ${Units.weight(kg, units)} for ${dayMonth(date)}"
    }

    private fun prefill(d: LocalDate) = (logs[d]?.weightKg ?: latestKg)?.let { Units.weightNumber(it, units) } ?: ""
}

/**
 * R5: save, delete and undo for weight logs. Storage and messages are lambdas so this runs in plain unit tests.
 * [notify] shows a message and returns true when its action was tapped.
 */
class WeightLogger(
    private val put: suspend (LocalDate, WeightLog) -> Boolean,
    private val delete: suspend (LocalDate) -> Boolean,
    private val reload: () -> Unit,
    private val notify: suspend (message: String, action: String?) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis
) {
    var days: List<DayMetrics> = emptyList()
    var units: UnitSystem = UnitSystem.SI
    var profileKg: Double? = null
    var sheet by mutableStateOf<WeightLogSheetState?>(null); private set

    fun open(date: LocalDate? = null) {
        if (days.isNotEmpty()) sheet = WeightLogSheetState(units, days, date ?: days.last().date, profileKg)
    }

    fun close() { sheet = null }

    suspend fun save() {
        val s = sheet ?: return
        val kg = s.parsedKg ?: return
        val date = s.date
        val prior = s.logs[date]
        if (!put(date, WeightLog(kg, now()))) return s.saveFailed(kg)
        sheet = null
        if (notify("Saved ${Units.weight(kg, s.units)} for ${dayMonth(date)}", "Undo")) undo(date, prior)
    }

    suspend fun remove(date: LocalDate) {
        val prior = days.firstOrNull { it.date == date }?.weightLog ?: return
        if (!delete(date)) {
            if (notify("Couldn't delete", "Retry")) remove(date)
            return
        }
        if (notify("Deleted ${Units.weight(prior.weightKg, units)} for ${dayMonth(date)}", "Undo")) undo(date, prior)
    }

    /** Puts back the row that was there before (same logged_at), or removes the new one. */
    private suspend fun undo(date: LocalDate, prior: WeightLog?) {
        val ok = if (prior != null) put(date, prior) else delete(date)
        if (!ok) {
            reload()
            notify("Couldn't undo", null)
        }
    }
}
