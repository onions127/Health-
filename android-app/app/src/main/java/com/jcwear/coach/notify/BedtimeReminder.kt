package com.jcwear.coach.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jcwear.coach.HealthApp
import com.jcwear.coach.data.AppSettings
import com.jcwear.coach.domain.LabelKind
import com.jcwear.coach.domain.Provenance
import com.jcwear.coach.domain.SleepAndReadinessEngine
import com.jcwear.coach.domain.SleepLabel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Bedtime reminder (plan S6): pure timing rules + AlarmManager planning. */
object BedtimeReminder {
    const val MIN_TRUSTED = 5
    const val DEFAULT_LATEST_MFN = 810 // 01:30 as minutes from noon
    val LATEST_RANGE_MFN = 660..840 // 23:00..02:00
    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    /** Trusted onsets, newest first: EDITED, or CONFIRMED while the band's own window was shown. Main sleeps only. */
    fun trustedOnsets(labels: List<SleepLabel>, zone: ZoneId = ZoneId.systemDefault()): List<LocalDateTime> = labels
        .filter { it.kind == LabelKind.EDITED || it.shownSource == Provenance.BAND }
        .map { it.onset.atZoneSameInstant(zone).toLocalDateTime() }
        .filter { SleepAndReadinessEngine.isMainSleepCandidate(it.toLocalTime()) }
        .sortedDescending()

    /** The evening a moment belongs to: 00:45 counts as the previous evening. */
    fun eveningOf(t: LocalDateTime): LocalDate = if (t.hour < 12) t.toLocalDate().minusDays(1) else t.toLocalDate()

    /** Next reminder after [now], or null with fewer than [MIN_TRUSTED] trusted nights. */
    fun reminderTime(trustedOnsets: List<LocalDateTime>, now: LocalDateTime, latestBedtimeMfn: Int = DEFAULT_LATEST_MFN): LocalDateTime? {
        if (trustedOnsets.size < MIN_TRUSTED) return null
        val sorted = trustedOnsets.take(14).map { SleepAndReadinessEngine.minutesFromNoon(it.toLocalTime()) }.sorted()
        val median = sorted[sorted.size / 2]
        val target = minOf(median - 30, latestBedtimeMfn).toLong()
        var at = eveningOf(now).atTime(12, 0).plusMinutes(target)
        if (!at.isAfter(now)) at = at.plusDays(1)
        return at
    }

    fun format(mfn: Int): String = LocalTime.NOON.plusMinutes(mfn.toLong()).format(hm)

    /**
     * Re-plans the alarm (same PendingIntent, so this replaces any earlier one). Called on app open and after every
     * background sync, which also restores it after a reboot. Returns the Settings status line.
     */
    fun plan(context: Context): String {
        val c = (context.applicationContext as HealthApp).container
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, "bedtime".hashCode(), Intent(context, BedtimeReminderReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val trusted = trustedOnsets(c.database.getAllSleepLabels())
        val at = reminderTime(trusted, LocalDateTime.now(), c.settings.latestBedtimeMfn)
        if (!c.settings.bedtimeReminderEnabled || at == null) {
            am.cancel(pi)
            return when {
                at == null -> "Needs $MIN_TRUSTED checked nights (${trusted.size}/$MIN_TRUSTED). Tap Looks right on the morning notification or in Sleep Detail."
                else -> "Off"
            }
        }
        c.settings.nextBedtimeReminder = at.format(hm)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pi)
        return "Next reminder ${at.format(DateTimeFormatter.ofPattern("EEE HH:mm"))}"
    }
}

class BedtimeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        fireOnce(context, (context.applicationContext as HealthApp).container.settings)
        BedtimeReminder.plan(context) // tomorrow's
    }

    companion object {
        /** Posts at most one reminder per evening, so a late re-plan can't double-fire. */
        fun fireOnce(context: Context, s: AppSettings, now: LocalDateTime = LocalDateTime.now()): Boolean {
            val evening = BedtimeReminder.eveningOf(now).toString()
            if (!s.bedtimeReminderEnabled || s.lastBedtimeReminderDate == evening) return false
            if (!Notifications.postBedtime(context, "Bed by ${s.nextBedtimeReminder} keeps your timing score up.")) return false
            s.lastBedtimeReminderDate = evening
            return true
        }
    }
}
