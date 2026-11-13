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

object SleepAndReadinessEngine {

    data class SleepEpochMinute(
        val timestamp: LocalDateTime,
        val stageCode: Int, // 1=Deep, 2=Light, 3=REM, 5=Awake
        val inferredPostNoon: Boolean = false
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
        val post1200NoonExtendedMin: Int,
        val sleepEfficiencyPct: Int,
        val epochs: List<SleepEpochMinute>
    )

    private val isoFormat: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun parseDateTime(str: String): LocalDateTime? = try {
        LocalDateTime.parse(str, isoFormat)
    } catch (_: Exception) {
        null
    }

    /**
     * Reconstructs continuous sleep sessions without the 12:00 (12:00 PM noon military time) cutoff.
     *
     * 1. Expands all raw 0x53 sleep blocks into 1-minute epochs and merges blocks separated by < 45 mins.
     * 2. If a session ends near 12:00 (e.g., 11:45..12:05 military time) because the band firmware's
     *    internal sleep window stops at 12:00 noon, inspects post-12:00 0x52 step records and 0x55/0x56
     *    heart rate records. As long as steps remain 0 (or <= 3 steps/10min) and HR stays below the
     *    user's sleeping HR threshold, appends post-12:00 sleep epochs until actual wake-up!
     */
    fun stitchSleepSessions(
        sleepBlocks: List<SleepBlockRecord>,
        stepRecords: List<StepEpochRecord>,
        hrRecords: List<HrRecord>
    ): List<StitchedSleepSession> {
        val rawMinutes = mutableListOf<SleepEpochMinute>()
        for (block in sleepBlocks.sortedBy { it.deviceTime }) {
            // Truncate seconds: overlapping blocks (02:24:01 vs 02:23:01) must collapse to the same minute, not count twice.
            val start = parseDateTime(block.deviceTime)?.truncatedTo(ChronoUnit.MINUTES) ?: continue
            val unit = block.unitMinutes.coerceAtLeast(1)
            for (idx in block.stages.indices) {
                val rawStage = block.stages[idx]
                val stage = if (rawStage == 30) J2208Protocol.STAGE_LIGHT else rawStage
                for (m in 0 until unit) {
                    val ts = start.plusMinutes((idx * unit + m).toLong())
                    rawMinutes.add(SleepEpochMinute(ts, stage, inferredPostNoon = false))
                }
            }
        }
        if (rawMinutes.isEmpty()) return emptyList()

        val deduped = rawMinutes
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }

        // Group into contiguous sessions allowing up to 45 minutes gap
        val sessions = mutableListOf<MutableList<SleepEpochMinute>>()
        for (epoch in deduped) {
            if (sessions.isEmpty()) {
                sessions.add(mutableListOf(epoch))
            } else {
                val current = sessions.last()
                val gapMin = Duration.between(current.last().timestamp, epoch.timestamp).toMinutes()
                if (gapMin <= 45) {
                    current.add(epoch)
                } else {
                    sessions.add(mutableListOf(epoch))
                }
            }
        }

        val parsedSteps = stepRecords.mapNotNull { rec ->
            parseDateTime(rec.deviceTime)?.let { it to rec }
        }.sortedBy { it.first }

        val parsedHrs = hrRecords.mapNotNull { rec ->
            if (rec.bpm in 35..210) parseDateTime(rec.deviceTime)?.let { it to rec.bpm } else null
        }.sortedBy { it.first }

        return sessions.map { sessionEpochs ->
            val firstTs = sessionEpochs.first().timestamp
            val lastRawTs = sessionEpochs.last().timestamp

            // Compute sleeping HR baseline during this session
            val sessionHrs = parsedHrs
                .filter { !it.first.isBefore(firstTs) && !it.first.isAfter(lastRawTs) }
                .map { it.second }
            val sleepHrBaseline = if (sessionHrs.isNotEmpty()) sessionHrs.average() else 58.0
            val wakeHrThreshold = (sleepHrBaseline + 12.0).coerceAtLeast(68.0)

            // Check if the session was cut off near 12:00 (12:00 PM noon military time)
            val cutOffNearNoon = lastRawTs.hour == 11 && lastRawTs.minute >= 40 ||
                (lastRawTs.hour == 12 && lastRawTs.minute <= 10)

            if (cutOffNearNoon) {
                val noonStart = lastRawTs.plusMinutes(1)
                val maxExtendUntil = lastRawTs.toLocalDate().atTime(17, 0)
                var cursor = noonStart
                var consecutiveActiveMinutes = 0

                while (!cursor.isAfter(maxExtendUntil)) {
                    // Find nearest step epoch within 15 minutes
                    val stepRec = parsedSteps.firstOrNull {
                        abs(Duration.between(it.first, cursor).toMinutes()) <= 10
                    }?.second
                    val nearbyHr = parsedHrs
                        .filter { abs(Duration.between(it.first, cursor).toMinutes()) <= 12 }
                        .map { it.second }
                        .average()
                        .takeIf { !it.isNaN() }

                    val lowMotion = stepRec == null || stepRec.steps <= 4
                    val lowHr = nearbyHr == null || nearbyHr <= wakeHrThreshold

                    if (lowMotion && lowHr) {
                        val stage = if (nearbyHr != null && nearbyHr <= sleepHrBaseline - 2.0) {
                            J2208Protocol.STAGE_DEEP
                        } else if ((Duration.between(firstTs, cursor).toMinutes() % 90L) in 60L..85L) {
                            J2208Protocol.STAGE_REM
                        } else {
                            J2208Protocol.STAGE_LIGHT
                        }
                        sessionEpochs.add(
                            SleepEpochMinute(
                                timestamp = cursor,
                                stageCode = stage,
                                inferredPostNoon = true
                            )
                        )
                        consecutiveActiveMinutes = 0
                    } else {
                        consecutiveActiveMinutes++
                        if (consecutiveActiveMinutes >= 10) break
                    }
                    cursor = cursor.plusMinutes(1)
                }
            }

            val deepMin = sessionEpochs.count { it.stageCode == J2208Protocol.STAGE_DEEP }
            val lightMin = sessionEpochs.count { it.stageCode == J2208Protocol.STAGE_LIGHT }
            val remMin = sessionEpochs.count { it.stageCode == J2208Protocol.STAGE_REM }
            val awakeMin = sessionEpochs.count { it.stageCode == J2208Protocol.STAGE_AWAKE }
            val postNoonMin = sessionEpochs.count {
                it.inferredPostNoon || (it.timestamp.hour in 12..17 && it.stageCode != J2208Protocol.STAGE_AWAKE)
            }
            val totalAsleep = deepMin + lightMin + remMin
            val totalInBed = (totalAsleep + awakeMin).coerceAtLeast(1)
            val efficiency = ((totalAsleep * 100.0) / totalInBed).roundToInt().coerceIn(0, 100)

            val endTs = sessionEpochs.last().timestamp
            StitchedSleepSession(
                wakeDate = endTs.toLocalDate().toString(),
                startTime = firstTs,
                endTime = endTs,
                totalSleepMin = totalAsleep,
                deepMin = deepMin,
                lightMin = lightMin,
                remMin = remMin,
                awakeMin = awakeMin,
                post1200NoonExtendedMin = postNoonMin,
                sleepEfficiencyPct = efficiency,
                epochs = sessionEpochs
            )
        }
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
    fun isMainSleepCandidate(s: StitchedSleepSession): Boolean = minutesFromNoon(s.startTime.toLocalTime()) >= 360

    /**
     * Sleep score v2 (plan 1.4), 0-100: duration 40%, restorative 20%, efficiency 10%,
     * regularity 15% (midpoint vs median of [priorMidpointsMfn]; none yet = full marks),
     * timing 15% (asleep by 02:00 full, 0 by 04:00). Onset after 03:00 caps the score at 70.
     */
    fun sleepScore(s: StitchedSleepSession, priorMidpointsMfn: List<Int>): Int {
        val asleep = s.totalSleepMin
        val duration = (asleep / 450.0 * 100.0).coerceIn(0.0, 100.0)
        val restorativeShare = if (asleep > 0) (s.deepMin + s.remMin).toDouble() / asleep else 0.0
        val restorative = (restorativeShare / 0.38 * 100.0).coerceIn(0.0, 100.0)
        val regularity = if (priorMidpointsMfn.isEmpty()) 100.0 else {
            val sorted = priorMidpointsMfn.sorted()
            val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2].toDouble()
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            val off = abs(minutesFromNoon(midpoint(s).toLocalTime()) - median)
            ((180.0 - off) / 150.0 * 100.0).coerceIn(0.0, 100.0)
        }
        val onset = minutesFromNoon(s.startTime.toLocalTime())
        val timing = ((960.0 - onset) / 120.0 * 100.0).coerceIn(0.0, 100.0) // 02:00 = 840, 04:00 = 960
        val score = (0.40 * duration + 0.20 * restorative + 0.10 * s.sleepEfficiencyPct +
            0.15 * regularity + 0.15 * timing).roundToInt().coerceIn(0, 100)
        return if (onset >= 900) score.coerceAtMost(70) else score // 03:00 = 900
    }
}
