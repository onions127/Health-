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
    val createdAt: Long
)

/** A label for the night as the user saw it; [onset]/[wake] are the user's times (the shown ones for CONFIRMED). */
fun labelFor(
    s: StitchedSleepSession,
    kind: LabelKind,
    onset: LocalDateTime,
    wake: LocalDateTime,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Long = System.currentTimeMillis()
): SleepLabel = SleepLabel(
    sessionKey = s.sessionKey,
    onset = onset.atZone(zone).toOffsetDateTime(),
    wake = wake.atZone(zone).toOffsetDateTime(),
    kind = kind,
    bandOnset = s.bandStartTime.atZone(zone).toOffsetDateTime(),
    bandWake = s.bandEndTime.atZone(zone).toOffsetDateTime(),
    shownSource = if (s.provenance == Provenance.ADJUSTED) Provenance.ADJUSTED else Provenance.BAND,
    createdAt = now
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
            LabelKind.EDITED -> SleepAndReadinessEngine.applyEditedWindow(target, onset, wake)
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

sealed interface EditCheck {
    data object Ok : EditCheck
    /** Saveable after the user confirms. */
    data class Confirm(val message: String) : EditCheck
    data class Invalid(val message: String) : EditCheck
}

const val MAX_NIGHT_MIN = 16 * 60

fun checkEdit(onset: LocalDateTime, wake: LocalDateTime): EditCheck {
    if (!wake.isAfter(onset)) return EditCheck.Invalid("Woke must be after Fell asleep.")
    val min = Duration.between(onset, wake).toMinutes()
    if (min > MAX_NIGHT_MIN) return EditCheck.Invalid("Night is ${min / 60}h ${min % 60}m; maximum is 16h.")
    if (!SleepAndReadinessEngine.isMainSleepCandidate(onset.toLocalTime())) {
        return EditCheck.Confirm("Outside usual night hours. Save anyway?")
    }
    return EditCheck.Ok
}
