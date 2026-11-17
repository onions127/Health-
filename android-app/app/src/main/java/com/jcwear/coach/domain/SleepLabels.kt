package com.jcwear.coach.domain

import com.jcwear.coach.domain.SleepAndReadinessEngine.StitchedSleepSession
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

enum class LabelKind { CONFIRMED, EDITED }

/**
 * User ground truth for one night. [sessionKey] = band session's first epoch (minute ISO).
 * [shownSource] = what the user saw when labeling (BAND or ADJUSTED), so the S2 gate isn't circular.
 */
data class SleepLabel(
    val sessionKey: String,
    val onset: OffsetDateTime,
    val wake: OffsetDateTime,
    val kind: LabelKind,
    val bandOnset: OffsetDateTime,
    val bandWake: OffsetDateTime,
    val shownSource: Provenance,
    val createdAt: Long,
    /** W2: periods the user was awake inside an EDITED night (sorted, merged, inside onset..wake). */
    val awake: List<Pair<OffsetDateTime, OffsetDateTime>> = emptyList()
)

/** A label for the night as the user saw it; [onset]/[wake] are the user's times (the shown ones for CONFIRMED). */
fun labelFor(
    s: StitchedSleepSession,
    kind: LabelKind,
    onset: LocalDateTime,
    wake: LocalDateTime,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Long = System.currentTimeMillis(),
    awake: List<Pair<LocalDateTime, LocalDateTime>> = emptyList()
): SleepLabel = SleepLabel(
    sessionKey = s.sessionKey,
    onset = onset.atZone(zone).toOffsetDateTime(),
    wake = wake.atZone(zone).toOffsetDateTime(),
    kind = kind,
    bandOnset = s.bandStartTime.atZone(zone).toOffsetDateTime(),
    bandWake = s.bandEndTime.atZone(zone).toOffsetDateTime(),
    shownSource = if (s.provenance == Provenance.ADJUSTED) Provenance.ADJUSTED else Provenance.BAND,
    createdAt = now,
    awake = awake.map { (a, b) -> a.atZone(zone).toOffsetDateTime() to b.atZone(zone).toOffsetDateTime() }
)

/** Exact key match first; else the session the label overlaps most (a stitch-rule change can move a start). */
fun matchSession(sessions: List<StitchedSleepSession>, key: String, onset: LocalDateTime, wake: LocalDateTime): StitchedSleepSession? =
    sessions.firstOrNull { it.sessionKey == key } ?: sessions
        .map { it to overlapMin(it.bandStartTime, it.bandEndTime, onset, wake) }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }?.first

private fun overlapMin(a0: LocalDateTime, a1: LocalDateTime, b0: LocalDateTime, b1: LocalDateTime): Long {
    val start = maxOf(a0, b0)
    val end = minOf(a1, b1)
    return if (end.isAfter(start)) Duration.between(start, end).toMinutes() else 0
}

/** EDITED sets the night to the user's times; CONFIRMED only marks it. Unlabeled sessions pass through. */
fun applyLabels(sessions: List<StitchedSleepSession>, labels: List<SleepLabel>, zone: ZoneId = ZoneId.systemDefault()): List<StitchedSleepSession> {
    if (labels.isEmpty()) return sessions
    val out = sessions.toMutableList()
    for (label in labels) {
        val onset = label.onset.atZoneSameInstant(zone).toLocalDateTime()
        val wake = label.wake.atZoneSameInstant(zone).toLocalDateTime()
        val target = matchSession(out, label.sessionKey, onset, wake) ?: continue
        val i = out.indexOf(target)
        out[i] = when (label.kind) {
            LabelKind.EDITED -> SleepAndReadinessEngine.applyEditedWindow(
                target, onset, wake,
                label.awake.map { (a, b) -> a.atZoneSameInstant(zone).toLocalDateTime() to b.atZoneSameInstant(zone).toLocalDateTime() }
            )
            LabelKind.CONFIRMED -> target.copy(confirmed = true)
        }
    }
    return out
}

/**
 * Pickers give times only. Onset goes on the date within ±12 h of [bandOnset];
 * wake is the first instant after onset with the picked time.
 */
fun anchorEdit(bandOnset: LocalDateTime, onset: LocalTime, wake: LocalTime): Pair<LocalDateTime, LocalDateTime> {
    val d = bandOnset.toLocalDate()
    val onsetAt = listOf(d.minusDays(1), d, d.plusDays(1)).map { it.atTime(onset) }
        .minBy { Duration.between(bandOnset, it).abs() }
    var wakeAt = onsetAt.toLocalDate().atTime(wake)
    if (!wakeAt.isAfter(onsetAt)) wakeAt = wakeAt.plusDays(1)
    return onsetAt to wakeAt
}

/** W2: an awake period's picked times, placed in the night: from = first instant at or after [onset], to = first after from. */
fun anchorAwake(onset: LocalDateTime, from: LocalTime, to: LocalTime): Pair<LocalDateTime, LocalDateTime> {
    var a = onset.toLocalDate().atTime(from)
    if (a.isBefore(onset)) a = a.plusDays(1)
    var b = a.toLocalDate().atTime(to)
    if (!b.isAfter(a)) b = b.plusDays(1)
    return a to b
}

/** Sorted, with overlapping or touching periods joined. */
fun mergeAwake(periods: List<Pair<LocalDateTime, LocalDateTime>>): List<Pair<LocalDateTime, LocalDateTime>> {
    val out = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
    for (p in periods.sortedBy { it.first }) {
        val last = out.lastOrNull()
        if (last != null && !p.first.isAfter(last.second)) out[out.lastIndex] = last.first to maxOf(last.second, p.second)
        else out += p
    }
    return out
}

sealed interface EditCheck {
    data object Ok : EditCheck
    /** Saveable after the user confirms. */
    data class Confirm(val message: String) : EditCheck
    data class Invalid(val message: String) : EditCheck
}

const val MAX_NIGHT_MIN = 16 * 60

/** [awake] must already be anchored ([anchorAwake]) and merged ([mergeAwake]). */
fun checkEdit(onset: LocalDateTime, wake: LocalDateTime, awake: List<Pair<LocalDateTime, LocalDateTime>> = emptyList()): EditCheck {
    if (!wake.isAfter(onset)) return EditCheck.Invalid("Woke must be after Fell asleep.")
    val min = Duration.between(onset, wake).toMinutes()
    if (min > MAX_NIGHT_MIN) return EditCheck.Invalid("Night is ${min / 60}h ${min % 60}m; maximum is 16h.")
    if (awake.any { it.second.isAfter(wake) }) return EditCheck.Invalid("Awake times must be between Fell asleep and Woke.")
    if (awake.sumOf { Duration.between(it.first, it.second).toMinutes() } >= min) return EditCheck.Invalid("No sleep left in this night.")
    if (!SleepAndReadinessEngine.isMainSleepCandidate(onset.toLocalTime())) {
        return EditCheck.Confirm("Outside usual night hours. Save anyway?")
    }
    return EditCheck.Ok
}
