package com.jcwear.coach.domain

import com.jcwear.coach.ble.J2208Protocol
import com.jcwear.coach.ble.J2208Protocol.HrRecord
import com.jcwear.coach.ble.J2208Protocol.HrvRecord
import com.jcwear.coach.ble.J2208Protocol.SleepBlockRecord
import com.jcwear.coach.ble.J2208Protocol.Spo2Record
import com.jcwear.coach.ble.J2208Protocol.StepEpochRecord
import com.jcwear.coach.ble.J2208Protocol.TempRecord
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Where a night's times come from. EDITED iff the night has an EDITED label. */
enum class Provenance { BAND, ADJUSTED, EDITED }

object SleepAndReadinessEngine {

    /** Asleep, but the band has no stage for this minute (inside a user-edited window). Not a band code. */
    const val STAGE_UNSTAGED = 0

    /** W1: a minute inside a night with no band data. Counts as awake; an edit treats it as asleep. Not a band code. */
    const val STAGE_NO_DATA = -1

    /** W1: blocks up to this far apart are one night; 90 joins every real wake-up in history but no evening doze. */
    const val MAX_NIGHT_GAP_MIN = 90L

    data class SleepEpochMinute(
        val timestamp: LocalDateTime,
        val stageCode: Int // 0=Unstaged (ours), 1=Deep, 2=Light, 3=REM, 5=Awake
    )

    data class StitchedSleepSession(
        val wakeDate: String,
        val startTime: LocalDateTime,
        val endTime: LocalDateTime,
        val totalSleepMin: Int,
        val deepMin: Int,
        val lightMin: Int,
        val remMin: Int,
        val awakeMin: Int,
        val sleepEfficiencyPct: Int,
        val epochs: List<SleepEpochMinute>,
        /** False when the band recorded no deep and no REM at all (light-only night). */
        val stagesAvailable: Boolean = deepMin + remMin > 0,
        /** What the band itself reported; differs from start/end/total once a label or the refiner moves the night. */
        val bandStartTime: LocalDateTime = startTime,
        val bandEndTime: LocalDateTime = endTime,
        val bandTotalSleepMin: Int = totalSleepMin,
        val provenance: Provenance = Provenance.BAND,
        /** A CONFIRMED label exists for this night. */
        val confirmed: Boolean = false,
        /** W2: the awake periods of the user's edit, so a re-edit starts from them. */
        val userAwake: List<Pair<LocalDateTime, LocalDateTime>> = emptyList()
    ) {
        /** Band's first epoch, minute precision: the stable key for sleep_labels. */
        val sessionKey: String get() = sessionKeyOf(bandStartTime)
        val unstagedMin: Int get() = totalSleepMin - deepMin - lightMin - remMin
    }

    fun sessionKeyOf(t: LocalDateTime): String = t.truncatedTo(ChronoUnit.MINUTES).format(isoFormat)

    private val isoFormat: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun parseDateTime(str: String): LocalDateTime? = try {
        LocalDateTime.parse(str, isoFormat)
    } catch (_: Exception) {
        null
    }

    /** Builds a session from minute epochs (sorted, distinct). */
    fun sessionOf(epochs: List<SleepEpochMinute>): StitchedSleepSession {
        val deep = epochs.count { it.stageCode == J2208Protocol.STAGE_DEEP }
        val light = epochs.count { it.stageCode == J2208Protocol.STAGE_LIGHT }
        val rem = epochs.count { it.stageCode == J2208Protocol.STAGE_REM }
        val unstaged = epochs.count { it.stageCode == STAGE_UNSTAGED }
        val awake = epochs.count { it.stageCode == J2208Protocol.STAGE_AWAKE || it.stageCode == STAGE_NO_DATA }
        val asleep = deep + light + rem + unstaged
        val efficiency = ((asleep * 100.0) / (asleep + awake).coerceAtLeast(1)).roundToInt().coerceIn(0, 100)
        val end = epochs.last().timestamp
        return StitchedSleepSession(
            wakeDate = end.toLocalDate().toString(),
            startTime = epochs.first().timestamp,
            endTime = end,
            totalSleepMin = asleep,
            deepMin = deep,
            lightMin = light,
            remMin = rem,
            awakeMin = awake,
            sleepEfficiencyPct = efficiency,
            epochs = epochs
        )
    }

    /**
     * Expands raw 0x53 blocks into 1-minute epochs and joins blocks up to [MAX_NIGHT_GAP_MIN] apart.
     * The minutes between joined blocks become [STAGE_NO_DATA], so asleep + awake = time in bed.
     */
    fun stitchSleepSessions(sleepBlocks: List<SleepBlockRecord>): List<StitchedSleepSession> {
        val rawMinutes = mutableListOf<SleepEpochMinute>()
        for (block in sleepBlocks.sortedBy { it.deviceTime }) {
            // shortcut: 5-min records (34-byte stride guess) are not sleep stages: 231 of 233 on the phone hold codes
            // like 82/109/215 and dates 2000-2035, and they padded nights by up to 2 h. Revisit if that packet is decoded.
            if (block.unitMinutes != 1) continue
            // Truncate seconds: overlapping blocks (02:24:01 vs 02:23:01) must collapse to the same minute, not count twice.
            val start = parseDateTime(block.deviceTime)?.truncatedTo(ChronoUnit.MINUTES) ?: continue
            val unit = block.unitMinutes.coerceAtLeast(1)
            for (idx in block.stages.indices) {
                val rawStage = block.stages[idx]
                val stage = if (rawStage == 30) J2208Protocol.STAGE_LIGHT else rawStage
                for (m in 0 until unit) {
                    rawMinutes.add(SleepEpochMinute(start.plusMinutes((idx * unit + m).toLong()), stage))
                }
            }
        }
        if (rawMinutes.isEmpty()) return emptyList()

        val sessions = mutableListOf<MutableList<SleepEpochMinute>>()
        for (epoch in rawMinutes.distinctBy { it.timestamp }.sortedBy { it.timestamp }) {
            val current = sessions.lastOrNull()
            if (current != null && Duration.between(current.last().timestamp, epoch.timestamp).toMinutes() <= MAX_NIGHT_GAP_MIN) {
                var gap = current.last().timestamp.plusMinutes(1)
                while (gap.isBefore(epoch.timestamp)) {
                    current.add(SleepEpochMinute(gap, STAGE_NO_DATA))
                    gap = gap.plusMinutes(1)
                }
                current.add(epoch)
            } else {
                sessions.add(mutableListOf(epoch))
            }
        }
        return sessions.map { sessionOf(it) }
    }

    /**
     * The night set to exactly [onset, wake] (user edit): band epochs outside are dropped, band stages
     * inside kept, minutes with no band data count as asleep but unstaged (no stage is invented), and
     * minutes inside the user's [awake] periods are awake.
     * Keeps the band's stagesAvailable so an edit never turns a light-only night into a staged one.
     */
    fun applyEditedWindow(
        s: StitchedSleepSession,
        onset: LocalDateTime,
        wake: LocalDateTime,
        awake: List<Pair<LocalDateTime, LocalDateTime>> = emptyList()
    ): StitchedSleepSession {
        val from = onset.truncatedTo(ChronoUnit.MINUTES)
        val to = wake.truncatedTo(ChronoUnit.MINUTES)
        val byTs = s.epochs.filter { it.stageCode != STAGE_NO_DATA }.associateBy { it.timestamp }
        fun isAwake(t: LocalDateTime) = awake.any { (a, b) -> !t.isBefore(a.truncatedTo(ChronoUnit.MINUTES)) && t.isBefore(b.truncatedTo(ChronoUnit.MINUTES)) }
        val epochs = generateSequence(from) { it.plusMinutes(1) }.takeWhile { it.isBefore(to) }
            .map { if (isAwake(it)) SleepEpochMinute(it, J2208Protocol.STAGE_AWAKE) else byTs[it] ?: SleepEpochMinute(it, STAGE_UNSTAGED) }
            .toList()
        if (epochs.isEmpty()) return s
        return sessionOf(epochs).copy(
            stagesAvailable = s.stagesAvailable,
            bandStartTime = s.bandStartTime, bandEndTime = s.bandEndTime, bandTotalSleepMin = s.bandTotalSleepMin,
            provenance = Provenance.EDITED,
            userAwake = awake
        )
    }

    /**
     * Computes RMSSD (ms) from beat-to-beat Pulse-to-Pulse Intervals (ms) or consecutive live HR readings.
     */
    fun calculateRmssdFromPpi(ppiIntervalsMs: List<Int>): Double {
        val valid = ppiIntervalsMs.filter { it in 330..1800 }
        if (valid.size < 2) return 0.0
        var sumSqDiff = 0.0
        var count = 0
        for (i in 1 until valid.size) {
            val diff = (valid[i] - valid[i - 1]).toDouble()
            if (abs(diff) < 300.0) { // artifact filter
                sumSqDiff += diff * diff
                count++
            }
        }
        if (count == 0) return 0.0
        return Math.round(sqrt(sumSqDiff / count) * 10.0) / 10.0
    }

    /** Clock time as minutes since 12:00 noon, so 23:50 (710) and 00:10 (730) sit next to each other. */
    fun minutesFromNoon(t: LocalTime): Int = (t.hour * 60 + t.minute - 720 + 1440) % 1440

    fun midpoint(s: StitchedSleepSession): LocalDateTime =
        s.startTime.plusMinutes(Duration.between(s.startTime, s.endTime).toMinutes() / 2)

    /** Main sleep: onset 18:00-12:00 (naps at 12:00-18:00 never count), longest wins. */
    fun isMainSleepCandidate(s: StitchedSleepSession): Boolean = isMainSleepCandidate(s.startTime.toLocalTime())

    fun isMainSleepCandidate(onset: LocalTime): Boolean = minutesFromNoon(onset) >= 360

    /**
     * Sleep score S2 (design round BD5, adopted at lock 2026-11-24), 0-100: duration 40%, restorative 20%,
     * efficiency 10%, regularity 15% (midpoint vs median of [priorMidpointsMfn]; none yet = full marks),
     * timing 15% (asleep by 02:00 full, 0 by 04:00). Onset after 03:00 caps the score at 70.
     * S2 stops three parts giving credit for a poor night: duration 0 at 4h30 → 100 at 7h30, deep + REM
     * 0 at 20% → 100 at 38% of staged time, efficiency 0 at 75% → 100 at 95%.
     * No stages recorded: restorative is dropped and the rest reweighted (50 / 12.5 / 18.75 / 18.75).
     */
    fun sleepScore(s: StitchedSleepSession, priorMidpointsMfn: List<Int>): Int = sleepScoreParts(s, priorMidpointsMfn).score

    fun sleepScoreParts(s: StitchedSleepSession, priorMidpointsMfn: List<Int>): ScoreBreakdown {
        val staged = s.deepMin + s.lightMin + s.remMin // unstaged minutes stay out of the share
        val restorativeShare = if (staged > 0) (s.deepMin + s.remMin).toDouble() / staged else 0.0
        val offMin = if (priorMidpointsMfn.isEmpty()) null else {
            val sorted = priorMidpointsMfn.sorted()
            val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2].toDouble()
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            abs(minutesFromNoon(midpoint(s).toLocalTime()) - median)
        }
        return sleepScoreParts(
            asleepMin = s.totalSleepMin.toDouble(), restorativeShare = restorativeShare,
            efficiencyPct = s.sleepEfficiencyPct.toDouble(), offMin = offMin,
            onsetMfn = minutesFromNoon(s.startTime.toLocalTime()), stagesAvailable = s.stagesAvailable,
            priorNights = priorMidpointsMfn.size
        )
    }

    /** S2 on a night's numbers ([offMin] = how far the midpoint is from your usual; null = no history yet). */
    fun sleepScoreParts(
        asleepMin: Double, restorativeShare: Double, efficiencyPct: Double, offMin: Double?,
        onsetMfn: Int, stagesAvailable: Boolean, priorNights: Int
    ): ScoreBreakdown {
        val duration = ((asleepMin - 270.0) / 180.0 * 100.0).coerceIn(0.0, 100.0)
        val restorative = ((restorativeShare - 0.20) / 0.18 * 100.0).coerceIn(0.0, 100.0)
        val efficiency = ((efficiencyPct - 75.0) / 20.0 * 100.0).coerceIn(0.0, 100.0)
        val regularity = if (offMin == null) 100.0 else ((180.0 - offMin) / 150.0 * 100.0).coerceIn(0.0, 100.0)
        val onset = onsetMfn
        val timing = ((960.0 - onset) / 120.0 * 100.0).coerceIn(0.0, 100.0) // 02:00 = 840, 04:00 = 960
        val parts = if (stagesAvailable) listOf(
            ScorePart("Duration", 0.40, duration),
            ScorePart("Deep + REM share", 0.20, restorative),
            ScorePart("Efficiency", 0.10, efficiency),
            ScorePart("Regular timing", 0.15, regularity),
            ScorePart("Asleep by 02:00", 0.15, timing)
        ) else listOf(
            ScorePart("Duration", 0.50, duration),
            ScorePart("Efficiency", 0.125, efficiency),
            ScorePart("Regular timing", 0.1875, regularity),
            ScorePart("Asleep by 02:00", 0.1875, timing)
        )
        return ScoreBreakdown(
            parts,
            cap = 70.takeIf { onset >= 900 }, // 03:00 = 900
            basis = "Last night + $priorNights nights for your routine"
        )
    }
}
