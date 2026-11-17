package com.jcwear.coach.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jcwear.coach.MainActivity
import com.jcwear.coach.R
import com.jcwear.coach.domain.SleepAndReadinessEngine.StitchedSleepSession

/** Channels, permission check and posting, shared by the morning summary (S5) and the bedtime reminder (S6). */
object Notifications {
    const val CHANNEL_MORNING = "morning_summary"
    const val ID_MORNING = 1001
    const val CHANNEL_BEDTIME = "bedtime_reminder"
    const val ID_BEDTIME = 1002

    const val EXTRA_SESSION_KEY = "session_key"
    const val EXTRA_ONSET = "onset"
    const val EXTRA_WAKE = "wake"
    const val EXTRA_OPEN_EDITOR = "open_editor"

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_MORNING, "Morning summary", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_BEDTIME, "Bedtime reminder", NotificationManager.IMPORTANCE_DEFAULT)
        ))
    }

    fun postBedtime(context: Context, text: String): Boolean {
        if (!canPost(context)) return false
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, "open".hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL_BEDTIME)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Time to wind down")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission") // checked by canPost above
        NotificationManagerCompat.from(context).notify(ID_BEDTIME, n)
        return true
    }

    /** False when the permission is missing (re-checked here, at post time). */
    fun postMorning(context: Context, text: String, night: StitchedSleepSession): Boolean {
        if (!canPost(context)) return false
        ensureChannel(context)
        val key = night.sessionKey
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            context, "open".hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )
        val confirm = PendingIntent.getBroadcast(
            context, "confirm:$key".hashCode(),
            Intent(context, SleepLabelReceiver::class.java)
                .putExtra(EXTRA_SESSION_KEY, key)
                .putExtra(EXTRA_ONSET, night.startTime.toString())
                .putExtra(EXTRA_WAKE, night.endTime.toString()),
            flags
        )
        val edit = PendingIntent.getActivity(
            context, "edit:$key".hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SESSION_KEY, key)
                .putExtra(EXTRA_OPEN_EDITOR, true),
            flags
        )
        val hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        val notification = NotificationCompat.Builder(context, CHANNEL_MORNING)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Your scores are ready")
            .setContentText(text)
            .setSubText("Slept ${night.startTime.format(hm)}–${night.endTime.format(hm)}")
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Looks right", confirm)
            .addAction(0, "Edit", edit)
            .build()
        @Suppress("MissingPermission") // checked by canPost above
        NotificationManagerCompat.from(context).notify(ID_MORNING, notification)
        return true
    }

    fun cancelMorning(context: Context) = NotificationManagerCompat.from(context).cancel(ID_MORNING)
}
