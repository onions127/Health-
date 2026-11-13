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
    val hrvMs: Double? = null,
    val morningRmssdMs: Double? = null,
    val rhrBpm: Double? = null,
    val spo2Pct: Double? = null,
    val skinTempDeltaC: Double? = null,
    val steps: Int? = null,
    val distanceKm: Double? = null,
    /** Whoop-style strain, 0-21. */
    val strain: Double? = null,
    val readiness: Int? = null,
    val recovery: Int? = null,
    val daily: Int? = null
) {
    val sleepMin: Int? get() = sleep?.totalSleepMin
    val strain100: Int? get() = strain?.let { (it / 21.0 * 100.0).roundToInt() }
    val hasData: Boolean
        get() = listOf(sleep, hrvMs, rhrBpm, spo2Pct, skinTempDeltaC, steps).any { it != null }
}

class DailySeriesRepository(private val db: LocalHealthDatabase) {
    fun load(today: LocalDate, morningRmssd: Double?): List<DayMetrics> = buildDaySeries(
        db.getAllSleepBlocks(), db.getAllHrvRecords(), db.getAllHrRecords(),
        db.getAllSpo2Records(), db.getAllTempRecords(), db.getAllStepRecords(),
        today, morningRmssd
    )
}

/**
 * Consecutive days, oldest first, ending with [today]: at least 90 days, more if older data exists.
 * Days without band data are all-null gaps.
 */
fun buildDaySeries(
    sleepBlocks: List<SleepBlockRecord>,
    hrvRecords: List<HrvRecord>,
    hrRecords: List<HrRecord>,
    spo2Records: List<Spo2Record>,
    tempRecords: List<TempRecord>,
    stepRecords: List<StepEpochRecord>,
    today: LocalDate,
    morningRmssd: Double?,
    heightCm: Double? = null
): List<DayMetrics> {
    fun <T> byDate(items: List<T>, time: (T) -> String, ok: (T) -> Boolean) = items
        .filter(ok)
        .mapNotNull { r -> SleepAndReadinessEngine.parseDateTime(time(r))?.let { it.toLocalDate() to r } }
        .groupBy({ it.first }, { it.second })

    val hrvByDay = byDate(hrvRecords, { it.deviceTime }, { it.hrvMs in 15..200 })
    val hrByDay = byDate(hrRecords, { it.deviceTime }, { it.bpm in 38..205 })
    val spo2ByDay = byDate(spo2Records, { it.deviceTime }, { it.spo2Pct in 85..100 })
    val tempByDay = byDate(tempRecords, { it.deviceTime }, { it.tempC in 31.0..41.0 })
    val stepsByDay = byDate(stepRecords, { it.deviceTime }, { true })

    val sessions = SleepAndReadinessEngine.stitchSleepSessions(sleepBlocks, stepRecords, hrRecords)
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
        val rmssd = morningRmssd?.takeIf { d == today && it > 0.0 }
        val dayHr = hrByDay[d].orEmpty().map { it.bpm.toDouble() }
        val steps = stepsByDay[d]?.sumOf { it.steps }
        val strain = if (steps == null && dayHr.isEmpty()) null else {
            val rawLoad = (steps ?: 0) / 650.0 + dayHr.count { it >= 105.0 } * 1.8
            Math.round((ln(1.0 + rawLoad) * 5.2).coerceIn(0.0, 21.0) * 10.0) / 10.0
        }
        DayMetrics(
            date = d,
            sleep = mainSleepByDay[d],
            hrvMs = rmssd ?: hrvByDay[d]?.map { it.hrvMs.toDouble() }?.average()?.round1(),
            morningRmssdMs = rmssd,
            rhrBpm = dayHr.takeIf { it.isNotEmpty() }?.sorted()
                ?.let { s -> s.take((s.size * 0.15).roundToInt().coerceAtLeast(1)).average() }?.round1(),
            spo2Pct = spo2ByDay[d]?.map { it.spo2Pct.toDouble() }?.average()?.round1(),
            steps = steps,
            distanceKm = distanceKm(steps, stepsByDay[d]?.sumOf { it.distanceKm }, heightCm),
            strain = strain
        )
    }

    // Pass 2: values that need history (sleep regularity, temp baseline, readiness baselines).
    return raw.mapIndexed { i, day ->
        val prior14 = raw.subList((i - 14).coerceAtLeast(0), i)
        val prior30 = raw.subList((i - 30).coerceAtLeast(0), i)
        val sleepScore = day.sleep?.let { s ->
            SleepAndReadinessEngine.sleepScore(
                s, prior14.mapNotNull { p -> p.sleep?.let { SleepAndReadinessEngine.minutesFromNoon(SleepAndReadinessEngine.midpoint(it).toLocalTime()) } }
            )
        }
        val todayTemp = tempByDay[day.date]?.map { it.tempC }?.average()
        val tempBase = prior30.flatMap { p -> tempByDay[p.date].orEmpty().map { it.tempC } }.takeIf { it.isNotEmpty() }?.average()
        val tempDelta = if (todayTemp != null && tempBase != null) Math.round((todayTemp - tempBase) * 100.0) / 100.0 else null

        val readiness = readinessScore(
            hrv = day.hrvMs, rhr = day.rhrBpm,
            hrvBase = prior30.mapNotNull { it.hrvMs }.takeIf { it.size >= 3 }?.average(),
            rhrBase = prior30.mapNotNull { it.rhrBpm }.takeIf { it.size >= 3 }?.average(),
            sleepScore = sleepScore, spo2 = day.spo2Pct, tempDelta = tempDelta
        )
        val recovery = if (day.hrvMs != null && day.rhrBpm != null)
            (day.hrvMs * 0.92 + (90 - day.rhrBpm) * 0.72).roundToInt().coerceIn(0, 100) else null
        val strain100 = day.strain100
        val daily = if (readiness != null && sleepScore != null && strain100 != null)
            (readiness * 0.42 + sleepScore * 0.36 + strain100 * 0.22).roundToInt().coerceIn(0, 100) else null
        day.copy(sleepScore = sleepScore, skinTempDeltaC = tempDelta, readiness = readiness, recovery = recovery, daily = daily)
    }
}

/** Needs HRV and resting HR against at least 3 prior days; sleep and vitals count when present. */
internal fun readinessScore(
    hrv: Double?, rhr: Double?, hrvBase: Double?, rhrBase: Double?,
    sleepScore: Int?, spo2: Double?, tempDelta: Double?
): Int? {
    if (hrv == null || rhr == null || hrvBase == null || rhrBase == null) return null
    val parts = mutableListOf(
        0.40 to (75.0 + (hrv / hrvBase.coerceAtLeast(1.0) - 1.0) * 125.0).coerceIn(20.0, 100.0),
        0.25 to (78.0 + (rhrBase - rhr) * 4.5).coerceIn(20.0, 100.0)
    )
    if (sleepScore != null) parts += 0.25 to sleepScore.toDouble()
    if (spo2 != null || tempDelta != null) {
        val penalty = (if (tempDelta != null && abs(tempDelta) > 0.5) 25.0 else 0.0) +
            (if (spo2 != null && spo2 < 95.0) (95.0 - spo2) * 8.0 else 0.0)
        parts += 0.10 to (95.0 - penalty).coerceIn(20.0, 100.0)
    }
    return (parts.sumOf { it.first * it.second } / parts.sumOf { it.first }).roundToInt().coerceIn(1, 100)
}

private fun Double.round1() = Math.round(this * 10.0) / 10.0
