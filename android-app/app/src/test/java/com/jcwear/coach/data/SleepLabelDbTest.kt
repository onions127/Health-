package com.jcwear.coach.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.jcwear.coach.ble.J2208Protocol.HrRecord
import com.jcwear.coach.domain.LabelKind
import com.jcwear.coach.domain.Provenance
import com.jcwear.coach.domain.SleepLabel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.OffsetDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepLabelDbTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = LocalHealthDatabase(context)

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("jcwear_local_health.db")
    }

    private fun label(kind: LabelKind, wake: String) = SleepLabel(
        "2026-11-13T00:05", OffsetDateTime.parse("2026-11-13T01:30+02:00"), OffsetDateTime.parse(wake), kind,
        OffsetDateTime.parse("2026-11-13T00:05+02:00"), OffsetDateTime.parse("2026-11-13T09:44+02:00"), Provenance.BAND, 1
    )

    @Test
    fun confirmedNeverOverwritesEdited() {
        db.putLabel(label(LabelKind.EDITED, "2026-11-13T05:50+02:00"))
        db.putLabel(label(LabelKind.CONFIRMED, "2026-11-13T09:44+02:00"))
        db.putLabel(label(LabelKind.CONFIRMED, "2026-11-13T09:44+02:00")) // double tap
        val all = db.getAllSleepLabels()
        assertEquals(1, all.size)
        assertEquals(LabelKind.EDITED, all.single().kind)
        assertEquals(OffsetDateTime.parse("2026-11-13T05:50+02:00"), all.single().wake)
    }

    @Test
    fun editedReplacesConfirmed() {
        db.putLabel(label(LabelKind.CONFIRMED, "2026-11-13T09:44+02:00"))
        db.putLabel(label(LabelKind.EDITED, "2026-11-13T05:50+02:00"))
        assertEquals(LabelKind.EDITED, db.getAllSleepLabels().single().kind)
    }

    @Test
    fun v3UpgradeKeepsRowsAndAddsLabels() {
        db.upsertHrRecords(listOf(HrRecord("2026-11-12T04:00:00", 50, "a")))
        db.writableDatabase.execSQL("DROP TABLE sleep_labels")
        db.writableDatabase.version = 3
        db.close()
        val reopened = LocalHealthDatabase(context)
        assertEquals(1, reopened.getAllHrRecords().size)
        reopened.putLabel(label(LabelKind.EDITED, "2026-11-13T05:50+02:00"))
        assertEquals(1, reopened.getAllSleepLabels().size)
        assertEquals(LocalHealthDatabase.DB_VERSION, reopened.readableDatabase.version)
        reopened.close()
    }
}
