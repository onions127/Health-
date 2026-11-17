package com.jcwear.coach.notify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jcwear.coach.ble.J2208Protocol
import com.jcwear.coach.ble.J2208Protocol.SleepBlockRecord
import com.jcwear.coach.data.LocalHealthDatabase
import com.jcwear.coach.domain.LabelKind
import com.jcwear.coach.domain.Provenance
import com.jcwear.coach.domain.SleepLabel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepLabelReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = LocalHealthDatabase(context).apply {
        upsertSleepBlocks(listOf(SleepBlockRecord("2026-11-16T00:00:00", 1, 60, 0, 60, 0, 0, List(60) { J2208Protocol.STAGE_LIGHT }, "t")))
    }
    private val key = "2026-11-16T00:00:00"

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("jcwear_local_health.db")
    }

    private fun confirm(onset: String = "2026-11-16T00:00", wake: String = "2026-11-16T00:59", k: String = key) =
        SleepLabelReceiver.confirmShownNight(db, k, onset, wake)

    @Test
    fun writesOneConfirmedLabelAndIsIdempotent() {
        assertTrue(confirm())
        assertTrue(confirm()) // double tap
        val labels = db.getAllSleepLabels()
        assertEquals(1, labels.size)
        assertEquals(LabelKind.CONFIRMED, labels.single().kind)
    }

    @Test
    fun forgedOrStaleExtrasWriteNothing() {
        assertFalse(confirm(k = "2026-11-15T23:00:00"))
        assertFalse(confirm(wake = "2026-11-16T05:50"))
        assertFalse(confirm(onset = "not a time"))
        assertEquals(0, db.getAllSleepLabels().size)
    }

    @Test
    fun neverOverwritesAnEditedLabel() {
        val zone = ZoneId.systemDefault()
        fun at(s: String) = java.time.LocalDateTime.parse(s).atZone(zone).toOffsetDateTime()
        db.putLabel(SleepLabel(key, at("2026-11-16T00:10"), at("2026-11-16T00:50"), LabelKind.EDITED,
            at("2026-11-16T00:00"), at("2026-11-16T00:59"), Provenance.BAND, 1))
        // The edited night now shows 00:10-00:49, so the old notification's times are stale.
        assertFalse(confirm())
        assertTrue(confirm(onset = "2026-11-16T00:10", wake = "2026-11-16T00:49"))
        assertEquals(LabelKind.EDITED, db.getAllSleepLabels().single().kind)
    }
}
