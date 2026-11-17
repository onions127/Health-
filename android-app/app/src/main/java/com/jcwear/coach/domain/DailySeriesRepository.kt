package com.jcwear.coach.domain

import com.jcwear.coach.ble.J2208Protocol.HrRecord
import com.jcwear.coach.ble.J2208Protocol.HrvRecord
import com.jcwear.coach.ble.J2208Protocol.SleepBlockRecord
import com.jcwear.coach.ble.J2208Protocol.Spo2Record
import com.jcwear.coach.ble.J2208Protocol.StepEpochRecord
import com.jcwear.coach.ble.J2208Protocol.TempRecord
import com.jcwear.coach.data.LocalHealthDatabase
import com.jcwear.coach.domain.SleepAndReadinessEngine.StitchedSleepSession
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** One day built from real band records only. Every metric is null when there is no data: never a default. */
data class DayMetrics(
    val date: LocalDate,
    val sleep: StitchedSleepSession? = null,
    val sleepScore: Int? = null,
    /** Parts behind sleepScore / recovery / daily (S3); each score is its breakdown's score. */
    val sleepParts: ScoreBreakdown? = null,
    val recoveryParts: ScoreBreakdown? = null,
    val dailyParts: ScoreBreakdown? = null,
    val readinessParts: ScoreBreakdown? = null,
    /** Overnight average only: the HRV trend and every baseline use this, never the morning check. */
    val hrvMs: Double? = null,
    /** That day's stored 60-second check (R1), if one was done. */
    val morningCheck: MorningCheck? = null,
    val rhrBpm: Double? = null,
    val spo2Pct: Double? = null,
    /** R3: the day's average wrist skin temperature. */
    val skinTempC: Double? = null,
    /** Mean of the day averages in the prior 30 days; null until [MIN_SKIN_TEMP_BASE_DAYS] of them have a reading. */
    val skinTempBaseC: Double? = null,
    /** Days with a reading in the prior 30 days (drives "2 of 3 nights" and "from N days"). */
    val skinTempBaseDays: Int = 0,
    /** skinTempC - skinTempBaseC; null without a baseline. Readiness uses this same number. */
    val skinTempDeltaC: Double? = null,
    val steps: Int? = null,
    val distanceKm: Double? = null,
    val caloriesKcal: Double? = null,
    /** True when calories come from the weight-based estimate, not the band (D10). */
    val caloriesEstimated: Boolean = false,
    /** Whoop-style strain, 0-21. */
    val strain: Double? = null,
    val readiness: Int? = null,
    val recovery: Int? = null,
    val daily: Int? = null,
    /** R5: that day's logged weight; null without a log (never carried forward). */
    val weightLog: WeightLog? = null
) {
    val weightKg: Double? get() = weightLog?.weightKg
    val sleepMin: Int? get() = sleep?.totalSleepMin
    val morningRmssdMs: Double? get() = morningCheck?.rmssdMs
    /** HRV that feeds this day's Recovery and Readiness: the morning check when there is one, else overnight. */
    val scoreHrvMs: Double? get() = morningRmssdMs ?: hrvMs
    val strain100: Int? get() = strain?.let { (it / 21.0 * 100.0).roundToInt() }
    val hasData: Boolean
        get() = listOf(sleep, hrvMs, morningCheck, rhrBpm, spo2Pct, skinTempC, steps, weightKg).any { it != null }
}

const val MIN_SKIN_TEMP_BASE_DAYS = 3

class DailySeriesRepository(private val db: LocalHealthDatabase) {
    /** [profileWeightKg] is only the calorie fallback for when there is no weight log. */
    fun load(today: LocalDate, heightCm: Double? = null, profileWeightKg: Double? = null): List<DayMetrics> = buildDaySeries(
        db.getAllSleepBlocks(), db.getAllHrvRecords(), db.getAllHrRecords(),
        db.getAllSpo2Records(), db.getAllTempRecords(), db.getAllStepRecords(),
        today, db.getAllMorningChecks(), heightCm, profileWeightKg, db.getAllSleepLabels(), db.getAllWeightLogs()
    )
}

/**
 * Consecutive days, oldest first, ending with [today]: at least 90 days, more if older data exists.
 * Days without band data are all-null gaps. A morning check feeds only its own day's scores (R1, T1a).
 */
fun buildDaySeries(
    sleepBlocks: List<SleepBlockRecord>,
    hrvRecords: List<HrvRecord>,
    hrRecords: List<HrRecord>,
    spo2Records: List<Spo2Record>,
    tempRecords: List<TempRecord>,
    stepRecords: List<StepEpochRecord>,
    today: LocalDate,
    morningChecks: Map<LocalDate, MorningCheck> = emptyMap(),
    heightCm: Double? = null,
    weightKg: Double? = null,
    sleepLabels: List<SleepLabel> = emptyList(),
    weightLogs: Map<LocalDate, WeightLog> = emptyMap()
): List<DayMetrics> {
    val logsByDate = java.util.TreeMap(weightLogs)
    fun <T> byDate(items: List<T>, time: (T) -> String, ok: (T) -> Boolean) = items
        .filter(ok)
        .mapNotNull { r -> SleepAndReadinessEngine.parseDateTime(time(r))?.let { it.toLocalDate() to r } }
        .groupBy({ it.first }, { it.second })

    val hrvByDay = byDate(hrvRecords, { it.deviceTime }, { it.hrvMs in 15..200 })
    val hrByDay = byDate(hrRecords, { it.deviceTime }, { it.bpm in 38..205 })
    val spo2ByDay = byDate(spo2Records, { it.deviceTime }, { it.spo2Pct in 85..100 })
    val tempByDay = byDate(tempRecords, { it.deviceTime }, { it.tempC in 31.0..41.0 })
    val stepsByDay = byDate(stepRecords, { it.deviceTime }, { true })
    val tempDayAvg = tempByDay.mapValues { (_, r) -> r.map { it.tempC }.average() }

    // Order: stitch -> labels -> (S2 refiner) -> main pick.
    val sessions = applyLabels(SleepAndReadinessEngine.stitchSleepSessions(sleepBlocks), sleepLabels)
    val mainSleepByDay = sessions
        .filter { SleepAndReadinessEngine.isMainSleepCandidate(it) }
        .groupBy { LocalDate.parse(it.wakeDate) }
        .mapValues { (_, s) -> s.maxBy { it.totalSleepMin } }

    val firstDataDay = (hrvByDay.keys + hrByDay.keys + spo2ByDay.keys + tempByDay.keys + stepsByDay.keys + mainSleepByDay.keys)
        .minOrNull()
    val start = listOfNotNull(firstDataDay, today.minusDays(89)).min()
    val dates = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()

    // Pass 1: raw per-day values.
    val raw = dates.map { d ->
        val dayHr = hrByDay[d].orEmpty().map { it.bpm.toDouble() }
        val steps = stepsByDay[d]?.sumOf { it.steps }
        val kcal = caloriesKcal(steps, stepsByDay[d]?.sumOf { it.caloriesKcal }, calorieWeightKg(d, logsByDate, weightKg))
        val strain = if (steps == null && dayHr.isEmpty()) null else {
            val rawLoad = (steps ?: 0) / 650.0 + dayHr.count { it >= 105.0 } * 1.8
            Math.round((ln(1.0 + rawLoad) * 5.2).coerceIn(0.0, 21.0) * 10.0) / 10.0
        }
        DayMetrics(
            date = d,
            sleep = mainSleepByDay[d],
            hrvMs = hrvByDay[d]?.map { it.hrvMs.toDouble() }?.average()?.round1(),
            morningCheck = morningChecks[d]?.takeIf { it.rmssdMs in MorningCheck.VALID_RMSSD_MS },
            rhrBpm = dayHr.takeIf { it.isNotEmpty() }?.sorted()
                ?.let { s -> s.take((s.size * 0.15).roundToInt().coerceAtLeast(1)).average() }?.round1(),
            spo2Pct = spo2ByDay[d]?.map { it.spo2Pct.toDouble() }?.average()?.round1(),
            steps = steps,
            distanceKm = distanceKm(steps, stepsByDay[d]?.sumOf { it.distanceKm }, heightCm),
            caloriesKcal = kcal?.first,
            caloriesEstimated = kcal?.second == true,
            strain = strain,
            weightLog = weightLogs[d]
        )
    }

    // Pass 2: values that need history (sleep regularity, temp baseline, readiness baselines).
    return raw.mapIndexed { i, day ->
        val prior14 = raw.subList((i - 14).coerceAtLeast(0), i)
        val prior30 = raw.subList((i - 30).coerceAtLeast(0), i)
        val sleepParts = day.sleep?.let { s ->
            SleepAndReadinessEngine.sleepScoreParts(
                s, prior14.mapNotNull { p -> p.sleep?.let { SleepAndReadinessEngine.minutesFromNoon(SleepAndReadinessEngine.midpoint(it).toLocalTime()) } }
            )
        }
        val sleepScore = sleepParts?.score
        val todayTemp = tempDayAvg[day.date]
        val baseTemps = prior30.mapNotNull { tempDayAvg[it.date] }
        val tempBase = baseTemps.takeIf { it.size >= MIN_SKIN_TEMP_BASE_DAYS }?.average()
        val tempDelta = if (todayTemp != null && tempBase != null) Math.round((todayTemp - tempBase) * 100.0) / 100.0 else null

        val readinessParts = readinessParts(
            hrv = day.scoreHrvMs, rhr = day.rhrBpm,
            hrvBase = prior30.mapNotNull { it.hrvMs }.takeIf { it.size >= MIN_READINESS_BASE_DAYS }?.average(),
            rhrBase = prior30.mapNotNull { it.rhrBpm }.takeIf { it.size >= MIN_READINESS_BASE_DAYS }?.average(),
            sleepScore = sleepScore, spo2 = day.spo2Pct, tempDelta = tempDelta,
            hrvNote = day.morningCheck?.let { if (it.estimated) "morning check, estimated" else "morning check" }
        )
        val readiness = readinessParts?.score
        val scoreHrv = day.scoreHrvMs
        val recoveryParts = if (scoreHrv != null && day.rhrBpm != null) recoveryParts(scoreHrv, day.rhrBpm) else null
        val strain100 = day.strain100
        val dailyParts = if (readiness != null && sleepScore != null && strain100 != null) dailyParts(readiness, sleepScore, strain100) else null
        day.copy(
            sleepScore = sleepScore, readiness = readiness,
            skinTempC = todayTemp?.round2(), skinTempBaseC = tempBase?.round2(),
            skinTempBaseDays = baseTemps.size, skinTempDeltaC = tempDelta,
            recovery = recoveryParts?.score, daily = dailyParts?.score,
            sleepParts = sleepParts, recoveryParts = recoveryParts, dailyParts = dailyParts, readinessParts = readinessParts
        )
    }
}

private fun Double.round1() = Math.round(this * 10.0) / 10.0
private fun Double.round2() = Math.round(this * 100.0) / 100.0
