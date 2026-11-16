package com.jcwear.coach.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.util.Log
import com.jcwear.coach.HealthApp
import com.jcwear.coach.data.LocalHealthDatabase
import com.jcwear.coach.domain.LabelKind
import com.jcwear.coach.domain.SleepAndReadinessEngine
import com.jcwear.coach.domain.applyLabels
import com.jcwear.coach.domain.labelFor
import java.time.LocalDateTime
import kotlin.concurrent.thread

/** "Looks right" on the morning notification: one CONFIRMED label, then the notification goes away. */
class SleepLabelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(Notifications.EXTRA_SESSION_KEY) ?: return
        val onset = intent.getStringExtra(Notifications.EXTRA_ONSET) ?: return
        val wake = intent.getStringExtra(Notifications.EXTRA_WAKE) ?: return
        val app = context.applicationContext as HealthApp
        val pending = goAsync()
        thread(name = "sleep-label") {
            try {
                when (confirmShownNight(app.container.database, key, onset, wake)) {
                    true -> {
                        Notifications.cancelMorning(context)
                        app.container.bleManager.onDataUpdated?.invoke() // refresh an open dashboard
                    }
                    false -> Log.w(TAG, "Ignored label for $key: no such night or times changed")
                }
            } catch (e: SQLiteException) {
                Log.w(TAG, "Label write failed for $key; notification kept for retry", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SleepLabelReceiver"

        /**
         * Writes CONFIRMED only when [key] is a stored night still showing exactly [onset]–[wake]
         * (extras are never trusted). Insert-or-ignore, so a double tap or an EDITED label is safe.
         */
        fun confirmShownNight(db: LocalHealthDatabase, key: String, onset: String, wake: String): Boolean {
            val on = runCatching { LocalDateTime.parse(onset) }.getOrNull() ?: return false
            val off = runCatching { LocalDateTime.parse(wake) }.getOrNull() ?: return false
            val night = applyLabels(SleepAndReadinessEngine.stitchSleepSessions(db.getAllSleepBlocks()), db.getAllSleepLabels())
                .firstOrNull { it.sessionKey == key } ?: return false
            if (night.startTime != on || night.endTime != off) return false
            db.putLabel(labelFor(night, LabelKind.CONFIRMED, on, off))
            return true
        }
    }
}
