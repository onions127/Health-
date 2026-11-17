package com.jcwear.coach.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.jcwear.coach.ble.J2208Protocol.HrRecord
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan 1.1: `uploaded` survives a re-fetch of unchanged rows; restore never overwrites a local row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadFlagTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = LocalHealthDatabase(context)

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("jcwear_local_health.db")
        context.deleteDatabase("v2.db")
    }

    @Test
    fun refetchKeepsUploadedUnlessDataChanged() {
        db.upsertHrRecords(listOf(HrRecord("2026-11-12T04:00:00", 50, "a"), HrRecord("2026-11-12T05:00:00", 52, "b")))
        val pending = db.pendingUpload("hr_logs")
        assertEquals(2, pending.size)
        assertEquals(50L, pending[0].getLong("bpm"))
        assertEquals("a", pending[0].getString("raw"))
        db.markUploaded("hr_logs", pending.map { it.getString("device_time") })

        db.upsertHrRecords(listOf(HrRecord("2026-11-12T04:00:00", 50, "a"), HrRecord("2026-11-12T05:00:00", 60, "b")))
        assertEquals(listOf("2026-11-12T05:00:00"), db.pendingUpload("hr_logs").map { it.getString("device_time") })
        assertEquals(60, db.getAllHrRecords().last().bpm)
    }

    @Test
    fun restoreAddsMissingRowsOnlyAndSkipsSeed() {
        db.upsertHrRecords(listOf(HrRecord("2026-11-12T04:00:00", 50, "local")))
        val added = db.insertRestored("hr_logs", listOf(
            JSONObject().put("device_time", "2026-11-12T04:00:00").put("bpm", 99).put("raw", "seed"),
            JSONObject().put("device_time", "2026-11-12T04:00:00").put("bpm", 77).put("raw", "server"),
            JSONObject().put("device_time", "2026-10-01T04:00:00").put("bpm", 55).put("raw", "server-only")
        ))
        assertEquals(1, added)
        val rows = db.getAllHrRecords().associateBy { it.deviceTime }
        assertEquals(50, rows.getValue("2026-11-12T04:00:00").bpm) // local row wins
        assertEquals(55, rows.getValue("2026-10-01T04:00:00").bpm)
        // Restored rows are already on the server, so they aren't sent back.
        assertEquals(listOf("2026-11-12T04:00:00"), db.pendingUpload("hr_logs").map { it.getString("device_time") })
    }

    @Test
    fun restoredSleepStagesRoundTrip() {
        db.insertRestored("sleep_blocks", listOf(
            JSONObject("""{"device_time":"2026-11-12T02:00:00","unit_minutes":1,"count":3,"deep_min":1,"light_min":1,"rem_min":1,"awake_min":0,"post_noon_extended_min":0,"stages":[1,2,3],"raw":"r"}""")
        ))
        assertEquals(listOf(1, 2, 3), db.getAllSleepBlocks().single().stages)
    }

    @Test
    fun upgradeFromV2AddsUploadedColumnAndKeepsRows() {
        val path = context.getDatabasePath("v2.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { v2 ->
            v2.execSQL("CREATE TABLE hr_logs (device_time TEXT PRIMARY KEY, bpm INTEGER, raw_hex TEXT)")
            v2.execSQL("INSERT INTO hr_logs VALUES ('2026-11-01T04:00:00', 51, 'old')")
            v2.version = 2
        }
        // Only hr_logs exists in this v2 file; create the rest as v2 had them so the upgrade can ALTER them.
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { v2 ->
            for (t in listOf("sleep_blocks", "hrv_logs", "spo2_logs", "temp_logs", "step_logs")) {
                v2.execSQL("CREATE TABLE $t (device_time TEXT PRIMARY KEY, raw_hex TEXT)")
            }
        }
        val upgraded = object : android.database.sqlite.SQLiteOpenHelper(context, "v2.db", null, 3) {
            override fun onCreate(db: SQLiteDatabase) = Unit
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = this@UploadFlagTest.db.onUpgrade(db, oldVersion, newVersion)
        }
        upgraded.readableDatabase.rawQuery("SELECT uploaded, bpm FROM hr_logs", null).use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
            assertEquals(51, c.getInt(1))
        }
        upgraded.close()
    }
}
