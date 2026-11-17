package com.jcwear.coach.notify

import com.jcwear.coach.domain.DayMetrics
import com.jcwear.coach.domain.verdictFor
import java.time.Duration
import java.time.LocalDateTime

/** Pure decision for the morning "scores ready" notification (plan S5). */
object MorningNotifier {
    /** Until the on-device check shows whether the band hands over the block it is still recording. */
    const val NIGHT_OPEN_BY_BLOCK_LENGTH = true
    const val FULL_BLOCK_EPOCHS = 120

    /**
     * Null = notify; else the skip code written to Recent syncs.
     * [lastBlockEpochs] = epoch count of the band block that ends today's night (null if unknown).
     */
    fun skipReason(
        syncOk: Boolean,
        enabled: Boolean,
        now: LocalDateTime,
        lastNotifyDate: String,
        today: DayMetrics?,
        lastBlockEpochs: Int?
    ): String? {
        val sleep = today?.sleep
        return when {
            !syncOk -> "sync_failed"
            !enabled -> "toggle_off"
            now.hour !in 5..11 -> "outside_window"
            lastNotifyDate == now.toLocalDate().toString() -> "already_sent"
            sleep == null || today.date != now.toLocalDate() -> "no_sleep"
            Duration.between(sleep.bandEndTime, now).toMinutes() < 45 -> "night_open"
            NIGHT_OPEN_BY_BLOCK_LENGTH && lastBlockEpochs != null && lastBlockEpochs >= FULL_BLOCK_EPOCHS -> "night_open"
            else -> null
        }
    }

    fun text(day: DayMetrics): String {
        val daily = day.daily
        if (daily != null) return "Daily $daily · ${verdictFor(day.recovery)}"
        val s = day.sleep ?: return ""
        val score = day.sleepScore?.let { " · Score $it" } ?: ""
        return "Sleep ${s.totalSleepMin / 60}h ${s.totalSleepMin % 60}m$score"
    }
}
