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
import java.time.format.DateTimeFormatter
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
        val sleepScore: Int,
        val epochs: List<SleepEpochMinute>
    )

    data class DailyReadinessReport(
        val date: String,
        val readinessScore: Int,
        val sleepScore: Int,
        val strainScore: Double,
        val overnightHrvMs: Double,
        val hrvBaseline30dMs: Double,
        val morningRmssdMs: Double?,
        val restingHrBpm: Double,
        val rhrBaseline30dBpm: Double,
        val spo2AvgPct: Double,
        val skinTempDeltaC: Double,
        val totalSleepMin: Int,
        val deepSleepMin: Int,
        val remSleepMin: Int,
        val lightSleepMin: Int,
        val post1200NoonExtendedMin: Int,
        val steps: Int,
        val headlineInsight: String,
        val coachingBullets: List<String>
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
            val start = parseDateTime(block.deviceTime) ?: continue
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

            // Sleep score: Duration vs 450 min (7.5h) target (55%), Deep+REM restorative share (30%), Efficiency (15%)
            val durationScore = ((totalAsleep / 450.0) * 100.0).coerceIn(0.0, 100.0)
            val restorativePct = if (totalAsleep > 0) ((deepMin + remMin).toDouble() / totalAsleep) else 0.0
            val restorativeScore = ((restorativePct / 0.38) * 100.0).coerceIn(0.0, 100.0)
            val sleepScore = (0.55 * durationScore + 0.30 * restorativeScore + 0.15 * efficiency)
                .roundToInt()
                .coerceIn(0, 100)

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
                sleepScore = sleepScore,
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

    /**
     * Computes daily Readiness (0-100), Sleep Score (0-100), and Strain (0-21) with rolling 30d baselines.
     */
    fun buildDailyReadinessReport(
        targetDate: LocalDate,
        sleepSessions: List<StitchedSleepSession>,
        hrvRecords: List<HrvRecord>,
        hrRecords: List<HrRecord>,
        spo2Records: List<Spo2Record>,
        tempRecords: List<TempRecord>,
        stepRecords: List<StepEpochRecord>,
        morningRmssdOverride: Double? = null
    ): DailyReadinessReport {
        val dateStr = targetDate.toString()
        val mainSleep = sleepSessions
            .filter { it.wakeDate == dateStr }
            .maxByOrNull { it.totalSleepMin }

        val sleepScore = mainSleep?.sleepScore ?: 70

        // Filter 30-day window for baselines
        val cutoff30d = targetDate.minusDays(30).atStartOfDay()
        val endOfTargetDay = targetDate.plusDays(1).atStartOfDay()

        val validHrv30d = hrvRecords.mapNotNull { rec ->
            val dt = parseDateTime(rec.deviceTime) ?: return@mapNotNull null
            if (!dt.isBefore(cutoff30d) && dt.isBefore(endOfTargetDay) && rec.hrvMs in 15..200) {
                dt to rec.hrvMs.toDouble()
            } else null
        }

        val todayHrvSamples = validHrv30d
            .filter { it.first.toLocalDate() == targetDate }
            .map { it.second }
        val hrvBaseline30d = if (validHrv30d.isNotEmpty()) {
            validHrv30d.map { it.second }.average()
        } else 55.0

        val overnightHrv = when {
            morningRmssdOverride != null && morningRmssdOverride > 0.0 -> morningRmssdOverride
            todayHrvSamples.isNotEmpty() -> todayHrvSamples.average()
            else -> hrvBaseline30d
        }

        val validHr30d = hrRecords.mapNotNull { rec ->
            val dt = parseDateTime(rec.deviceTime) ?: return@mapNotNull null
            if (!dt.isBefore(cutoff30d) && dt.isBefore(endOfTargetDay) && rec.bpm in 38..205) {
                dt to rec.bpm.toDouble()
            } else null
        }

        val todayHrSamples = validHr30d
            .filter { it.first.toLocalDate() == targetDate }
            .map { it.second }

        // Resting HR = average of lowest 15% of HR readings for the day
        fun lowestPercentileAvg(values: List<Double>, fraction: Double = 0.15): Double {
            if (values.isEmpty()) return 58.0
            val sorted = values.sorted()
            val takeCount = (sorted.size * fraction).roundToInt().coerceAtLeast(1)
            return sorted.take(takeCount).average()
        }

        val restingHrToday = lowestPercentileAvg(todayHrSamples)
        val rhrBaseline30d = lowestPercentileAvg(validHr30d.map { it.second }, 0.20)

        // SpO2 and Skin Temperature deviation
        val todaySpo2 = spo2Records
            .filter { it.deviceTime.startsWith(dateStr) && it.spo2Pct in 85..100 }
            .map { it.spo2Pct.toDouble() }
            .average()
            .let { if (it.isNaN()) 97.5 else it }

        val validTemp30d = tempRecords.filter { it.tempC in 31.0..41.0 }
        val tempBaseline = validTemp30d.map { it.tempC }.average().let { if (it.isNaN()) 36.2 else it }
        val todayTemp = validTemp30d
            .filter { it.deviceTime.startsWith(dateStr) }
            .map { it.tempC }
            .average()
            .let { if (it.isNaN()) tempBaseline else it }
        val tempDeltaC = Math.round((todayTemp - tempBaseline) * 100.0) / 100.0

        // Component scores (0..100)
        val hrvRatio = overnightHrv / hrvBaseline30d.coerceAtLeast(1.0)
        val hrvScore = (75.0 + (hrvRatio - 1.0) * 125.0).coerceIn(20.0, 100.0)

        val rhrDiff = rhrBaseline30d - restingHrToday // positive when today's RHR is lower than baseline
        val rhrScore = (78.0 + rhrDiff * 4.5).coerceIn(20.0, 100.0)

        val vitalsPenalty = (if (abs(tempDeltaC) > 0.5) 25.0 else 0.0) +
            (if (todaySpo2 < 95.0) (95.0 - todaySpo2) * 8.0 else 0.0)
        val vitalsScore = (95.0 - vitalsPenalty).coerceIn(20.0, 100.0)

        val readinessScore = (
            0.40 * hrvScore +
            0.25 * rhrScore +
            0.25 * sleepScore +
            0.10 * vitalsScore
        ).roundToInt().coerceIn(1, 100)

        // Daily steps & Whoop-style logarithmic Strain (0.0 - 21.0)
        val todaySteps = stepRecords
            .filter { it.deviceTime.startsWith(dateStr) }
            .sumOf { it.steps }
        val elevatedHrSamples = todayHrSamples.count { it >= 105.0 }
        val rawLoad = (todaySteps / 650.0) + (elevatedHrSamples * 1.8)
        val strainScore = Math.round((ln(1.0 + rawLoad) * 5.2).coerceIn(0.0, 21.0) * 10.0) / 10.0

        val hrvDeltaPct = ((hrvRatio - 1.0) * 100.0).roundToInt()
        val headline = when {
            readinessScore >= 80 -> "Peak Readiness ($readinessScore%) — Primed for High Strain"
            readinessScore >= 60 -> "Moderate Readiness ($readinessScore%) — Balanced Training Day"
            else -> "Recovery Priority ($readinessScore%) — Autonomic Nervous System Fatigued"
        }

        val bullets = buildList {
            add(
                "HRV is ${overnightHrv.roundToInt()} ms (${if (hrvDeltaPct >= 0) "+$hrvDeltaPct%" else "$hrvDeltaPct%"} vs 30d baseline of ${hrvBaseline30d.roundToInt()} ms)."
            )
            add(
                "Resting HR is ${restingHrToday.roundToInt()} bpm (30d baseline ${rhrBaseline30d.roundToInt()} bpm) | SpO2 ${String.format("%.1f", todaySpo2)}% | Temp delta ${String.format("%+.2f", tempDeltaC)}°C."
            )
            if (mainSleep != null) {
                val hrs = mainSleep.totalSleepMin / 60
                val mins = mainSleep.totalSleepMin % 60
                val noonNote = if (mainSleep.post1200NoonExtendedMin > 0) {
                    " (includes +${mainSleep.post1200NoonExtendedMin}m stitched past 12:00 noon!)"
                } else ""
                add(
                    "Sleep: ${hrs}h ${mins}m$noonNote — Deep ${mainSleep.deepMin}m, REM ${mainSleep.remMin}m, Light ${mainSleep.lightMin}m (Score ${mainSleep.sleepScore}%)."
                )
            }
        }

        return DailyReadinessReport(
            date = dateStr,
            readinessScore = readinessScore,
            sleepScore = sleepScore,
            strainScore = strainScore,
            overnightHrvMs = Math.round(overnightHrv * 10.0) / 10.0,
            hrvBaseline30dMs = Math.round(hrvBaseline30d * 10.0) / 10.0,
            morningRmssdMs = morningRmssdOverride,
            restingHrBpm = Math.round(restingHrToday * 10.0) / 10.0,
            rhrBaseline30dBpm = Math.round(rhrBaseline30d * 10.0) / 10.0,
            spo2AvgPct = Math.round(todaySpo2 * 10.0) / 10.0,
            skinTempDeltaC = tempDeltaC,
            totalSleepMin = mainSleep?.totalSleepMin ?: 0,
            deepSleepMin = mainSleep?.deepMin ?: 0,
            remSleepMin = mainSleep?.remMin ?: 0,
            lightSleepMin = mainSleep?.lightMin ?: 0,
            post1200NoonExtendedMin = mainSleep?.post1200NoonExtendedMin ?: 0,
            steps = todaySteps,
            headlineInsight = headline,
            coachingBullets = bullets
        )
    }
}
