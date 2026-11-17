package com.jcwear.coach.notify

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.jcwear.coach.data.AppSettings
import com.jcwear.coach.domain.LabelKind
import com.jcwear.coach.domain.Provenance
import com.jcwear.coach.domain.SleepLabel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BedtimeReminderTest {
    private val now = LocalDateTime.parse("2026-11-16T15:00")
    private fun t(s: String) = LocalDateTime.parse(s)

    /** Onsets on consecutive nights at the given clock times. */
    private fun onsets(vararg hm: String) = hm.mapIndexed { i, x ->
        val time = java.time.LocalTime.parse(x)
        val evening = LocalDate.of(2026, 11, 15).minusDays(i.toLong())
        if (time.hour < 12) evening.plusDays(1).atTime(time) else evening.atTime(time)
    }

    @Test
    fun needsFiveTrustedNights() {
        assertNull(BedtimeReminder.reminderTime(onsets("00:00", "00:10", "00:20", "00:30"), now))
    }

    @Test
    fun medianAcrossMidnightMinus30() {
        // Median 00:00 (23:40, 23:50, 00:00, 00:10, 00:20) -> 23:30 tonight.
        assertEquals(t("2026-11-16T23:30"), BedtimeReminder.reminderTime(onsets("23:40", "00:20", "00:00", "23:50", "00:10"), now))
    }

    @Test
    fun cappedByLatestBedtime() {
        val late = onsets("03:00", "03:10", "02:50", "03:20", "03:00")
        assertEquals(t("2026-11-17T01:30"), BedtimeReminder.reminderTime(late, now)) // default 01:30
        assertEquals(t("2026-11-17T00:00"), BedtimeReminder.reminderTime(late, now, latestBedtimeMfn = 720)) // custom 00:00
    }

    @Test
    fun afterTonightsTimeMovesToTomorrow() {
        val o = onsets("00:00", "00:00", "00:00", "00:00", "00:00")
        // 00:45 belongs to the evening of the 16th; 23:30 that evening has passed -> 23:30 on the 17th.
        assertEquals(t("2026-11-17T23:30"), BedtimeReminder.reminderTime(o, t("2026-11-17T00:45")))
        assertEquals(LocalDate.of(2026, 11, 16), BedtimeReminder.eveningOf(t("2026-11-17T00:45")))
    }

    @Test
    fun onlyTrustedMainSleepsCount() {
        fun label(kind: LabelKind, shown: Provenance, onset: String) = LocalDateTime.parse(onset).atOffset(ZoneOffset.UTC).let {
            SleepLabel(onset, it, it.plusHours(7), kind, it, it.plusHours(7), shown, 0)
        }
        val trusted = BedtimeReminder.trustedOnsets(listOf(
            label(LabelKind.EDITED, Provenance.ADJUSTED, "2026-11-15T23:00"),
            label(LabelKind.CONFIRMED, Provenance.BAND, "2026-11-14T23:30"),
            label(LabelKind.CONFIRMED, Provenance.ADJUSTED, "2026-11-13T23:30"), // circular: not trusted
            label(LabelKind.EDITED, Provenance.BAND, "2026-11-13T14:00") // nap
        ), ZoneOffset.UTC)
        assertEquals(listOf(t("2026-11-15T23:00"), t("2026-11-14T23:30")), trusted)
    }

    @Test
    fun receiverFiresOncePerEvening() {
        val context: Context = ApplicationProvider.getApplicationContext()
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val settings = AppSettings(context)
        settings.bedtimeReminderEnabled = true
        settings.nextBedtimeReminder = "23:30"
        val nm = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
        val at = t("2026-11-17T00:45")
        assertEquals(true, BedtimeReminderReceiver.fireOnce(context, settings, at))
        assertEquals(false, BedtimeReminderReceiver.fireOnce(context, settings, t("2026-11-17T01:10"))) // late re-plan, same evening
        assertEquals(1, nm.allNotifications.size)
        assertEquals("2026-11-16", settings.lastBedtimeReminderDate)
        assertEquals("Bed by 23:30 keeps your timing score up.", nm.allNotifications.single().extras.getString("android.text"))
    }

    @After
    fun tearDown() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }
}
