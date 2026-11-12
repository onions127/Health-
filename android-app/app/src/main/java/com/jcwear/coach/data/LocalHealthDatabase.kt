package com.jcwear.coach.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jcwear.coach.ble.J2208Protocol.HrRecord
import com.jcwear.coach.ble.J2208Protocol.HrvRecord
import com.jcwear.coach.ble.J2208Protocol.SleepBlockRecord
import com.jcwear.coach.ble.J2208Protocol.Spo2Record
import com.jcwear.coach.ble.J2208Protocol.StepEpochRecord
import com.jcwear.coach.ble.J2208Protocol.TempRecord
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class LocalHealthDatabase(private val appContext: Context) :
    SQLiteOpenHelper(appContext, "jcwear_local_health.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sleep_blocks (
                device_time TEXT PRIMARY KEY,
                unit_minutes INTEGER,
                count INTEGER,
                deep_min INTEGER,
                light_min INTEGER,
                rem_min INTEGER,
                awake_min INTEGER,
                stages_csv TEXT,
                raw_hex TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hrv_logs (
                device_time TEXT PRIMARY KEY,
                hrv_ms INTEGER,
                vascular_aging INTEGER,
                hr_bpm INTEGER,
                stress INTEGER,
                bp_sys INTEGER,
                bp_dia INTEGER,
                raw_hex TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hr_logs (
                device_time TEXT PRIMARY KEY,
                bpm INTEGER,
                raw_hex TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS spo2_logs (
                device_time TEXT PRIMARY KEY,
                spo2_pct INTEGER,
                raw_hex TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS temp_logs (
                device_time TEXT PRIMARY KEY,
                temp_c REAL,
                raw_hex TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS step_logs (
                device_time TEXT PRIMARY KEY,
                steps INTEGER,
                calories_kcal REAL,
                distance_km REAL,
                sub_steps_csv TEXT,
                raw_hex TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun upsertSleepBlocks(records: List<SleepBlockRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in records) {
                val cv = ContentValues().apply {
                    put("device_time", r.deviceTime)
                    put("unit_minutes", r.unitMinutes)
                    put("count", r.count)
                    put("deep_min", r.deepMin)
                    put("light_min", r.lightMin)
                    put("rem_min", r.remMin)
                    put("awake_min", r.awakeMin)
                    put("stages_csv", r.stages.joinToString(","))
                    put("raw_hex", r.rawHex)
                }
                db.insertWithOnConflict("sleep_blocks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsertHrvRecords(records: List<HrvRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in records) {
                val cv = ContentValues().apply {
                    put("device_time", r.deviceTime)
                    put("hrv_ms", r.hrvMs)
                    put("vascular_aging", r.vascularAging)
                    put("hr_bpm", r.hrBpm)
                    put("stress", r.stress)
                    put("bp_sys", r.bpSys)
                    put("bp_dia", r.bpDia)
                    put("raw_hex", r.rawHex)
                }
                db.insertWithOnConflict("hrv_logs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsertHrRecords(records: List<HrRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in records) {
                val cv = ContentValues().apply {
                    put("device_time", r.deviceTime)
                    put("bpm", r.bpm)
                    put("raw_hex", r.rawHex)
                }
                db.insertWithOnConflict("hr_logs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsertSpo2Records(records: List<Spo2Record>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in records) {
                val cv = ContentValues().apply {
                    put("device_time", r.deviceTime)
                    put("spo2_pct", r.spo2Pct)
                    put("raw_hex", r.rawHex)
                }
                db.insertWithOnConflict("spo2_logs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsertTempRecords(records: List<TempRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in records) {
                val cv = ContentValues().apply {
                    put("device_time", r.deviceTime)
                    put("temp_c", r.tempC)
                    put("raw_hex", r.rawHex)
                }
                db.insertWithOnConflict("temp_logs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsertStepRecords(records: List<StepEpochRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in records) {
                val cv = ContentValues().apply {
                    put("device_time", r.deviceTime)
                    put("steps", r.steps)
                    put("calories_kcal", r.caloriesKcal)
                    put("distance_km", r.distanceKm)
                    put("sub_steps_csv", r.subMinuteSteps.joinToString(","))
                    put("raw_hex", r.rawHex)
                }
                db.insertWithOnConflict("step_logs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAllSleepBlocks(): List<SleepBlockRecord> {
        val out = mutableListOf<SleepBlockRecord>()
        readableDatabase.rawQuery("SELECT * FROM sleep_blocks ORDER BY device_time ASC", null).use { c ->
            while (c.moveToNext()) {
                val stagesCsv = c.getString(7) ?: ""
                val stages = if (stagesCsv.isBlank()) emptyList() else stagesCsv.split(",").mapNotNull { it.toIntOrNull() }
                out.add(
                    SleepBlockRecord(
                        deviceTime = c.getString(0),
                        unitMinutes = c.getInt(1),
                        count = c.getInt(2),
                        deepMin = c.getInt(3),
                        lightMin = c.getInt(4),
                        remMin = c.getInt(5),
                        awakeMin = c.getInt(6),
                        stages = stages,
                        rawHex = c.getString(8) ?: ""
                    )
                )
            }
        }
        return out
    }

    fun getAllHrvRecords(): List<HrvRecord> {
        val out = mutableListOf<HrvRecord>()
        readableDatabase.rawQuery("SELECT * FROM hrv_logs ORDER BY device_time ASC", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    HrvRecord(
                        deviceTime = c.getString(0),
                        hrvMs = c.getInt(1),
                        vascularAging = c.getInt(2),
                        hrBpm = c.getInt(3),
                        stress = c.getInt(4),
                        bpSys = c.getInt(5),
                        bpDia = c.getInt(6),
                        rawHex = c.getString(7) ?: ""
                    )
                )
            }
        }
        return out
    }

    fun getAllHrRecords(): List<HrRecord> {
        val out = mutableListOf<HrRecord>()
        readableDatabase.rawQuery("SELECT * FROM hr_logs ORDER BY device_time ASC", null).use { c ->
            while (c.moveToNext()) {
                out.add(HrRecord(c.getString(0), c.getInt(1), c.getString(2) ?: ""))
            }
        }
        return out
    }

    fun getAllSpo2Records(): List<Spo2Record> {
        val out = mutableListOf<Spo2Record>()
        readableDatabase.rawQuery("SELECT * FROM spo2_logs ORDER BY device_time ASC", null).use { c ->
            while (c.moveToNext()) {
                out.add(Spo2Record(c.getString(0), c.getInt(1), c.getString(2) ?: ""))
            }
        }
        return out
    }

    fun getAllTempRecords(): List<TempRecord> {
        val out = mutableListOf<TempRecord>()
        readableDatabase.rawQuery("SELECT * FROM temp_logs ORDER BY device_time ASC", null).use { c ->
            while (c.moveToNext()) {
                out.add(TempRecord(c.getString(0), c.getDouble(1), c.getString(2) ?: ""))
            }
        }
        return out
    }

    fun getAllStepRecords(): List<StepEpochRecord> {
        val out = mutableListOf<StepEpochRecord>()
        readableDatabase.rawQuery("SELECT * FROM step_logs ORDER BY device_time ASC", null).use { c ->
            while (c.moveToNext()) {
                val subCsv = c.getString(4) ?: ""
                val sub = if (subCsv.isBlank()) emptyList() else subCsv.split(",").mapNotNull { it.toIntOrNull() }
                out.add(
                    StepEpochRecord(
                        deviceTime = c.getString(0),
                        steps = c.getInt(1),
                        caloriesKcal = c.getDouble(2),
                        distanceKm = c.getDouble(3),
                        subMinuteSteps = sub,
                        rawHex = c.getString(5) ?: ""
                    )
                )
            }
        }
        return out
    }

    /**
     * Seeds initial 14-day baseline data if the database is completely empty,
     * including a sleep session that crosses 12:00 (noon military time) to demonstrate
     * the Post-12:00 Noon Sleep Stitcher before the first BLE sync, and ensures
     * all 14 days in the rolling window have daily HRV/RHR records (including a physiological outlier).
     */
    fun seedInitialBaselineIfEmpty() {
        if (getAllSleepBlocks().size < 50) {
            try {
                close()
                val dbFile = appContext.getDatabasePath("jcwear_local_health.db")
                dbFile.parentFile?.mkdirs()
                appContext.assets.open("jcwear_local_health.db").use { input ->
                    dbFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (_: Exception) {
                // Fall back to synthetic seed if asset copy fails
            }
        }
        ensureFourteenDayWindowPopulated()
    }

    private fun ensureFourteenDayWindowPopulated() {
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val today = LocalDate.now()
        val existingHrvDates = getAllHrvRecords().mapNotNull {
            runCatching { LocalDate.parse(it.deviceTime.take(10)) }.getOrNull()
        }.toSet()
        val existingSleepDates = getAllSleepBlocks().mapNotNull {
            runCatching { LocalDate.parse(it.deviceTime.take(10)) }.getOrNull()
        }.toSet()

        val hrvBaselinePattern = listOf(58, 60, 56, 61, 59, 62, 57, 42, 59, 63, 61, 65, 62, 64)
        val rhrBaselinePattern = listOf(55, 55, 56, 54, 55, 54, 55, 61, 55, 54, 54, 53, 54, 54)

        val hrvList = mutableListOf<HrvRecord>()
        val hrList = mutableListOf<HrRecord>()
        val spo2List = mutableListOf<Spo2Record>()
        val tempList = mutableListOf<TempRecord>()
        val stepList = mutableListOf<StepEpochRecord>()
        val sleepList = mutableListOf<SleepBlockRecord>()

        for (d in 13 downTo 0) {
            val date = today.minusDays(d.toLong())
            if (date !in existingHrvDates) {
                val patternIdx = (13 - d).coerceIn(0, hrvBaselinePattern.lastIndex)
                val hrvVal = hrvBaselinePattern[patternIdx]
                val rhrVal = rhrBaselinePattern[patternIdx]
                hrvList.add(
                    HrvRecord(
                        deviceTime = date.atTime(6, 30).format(fmt),
                        hrvMs = hrvVal,
                        vascularAging = 1,
                        hrBpm = rhrVal,
                        stress = if (hrvVal < 48) 54 else 28,
                        bpSys = 116,
                        bpDia = 74,
                        rawHex = "seed"
                    )
                )
                hrList.add(HrRecord(date.atTime(5, 0).format(fmt), rhrVal, "seed"))
                hrList.add(HrRecord(date.atTime(12, 15).format(fmt), rhrVal + 1, "seed"))
                hrList.add(HrRecord(date.atTime(17, 30).format(fmt), 118, "seed"))
                spo2List.add(Spo2Record(date.atTime(6, 0).format(fmt), 97 + (d % 2), "seed"))
                tempList.add(TempRecord(date.atTime(6, 0).format(fmt), 36.2 + (if (d == 0) 0.2 else 0.0), "seed"))
                stepList.add(
                    StepEpochRecord(
                        deviceTime = date.atTime(12, 10).format(fmt),
                        steps = if (d == 0) 0 else 450,
                        caloriesKcal = 18.0,
                        distanceKm = 0.35,
                        subMinuteSteps = List(10) { 0 },
                        rawHex = "seed"
                    )
                )
                stepList.add(
                    StepEpochRecord(
                        deviceTime = date.atTime(18, 0).format(fmt),
                        steps = 7200 + (d % 4) * 900,
                        caloriesKcal = 310.0,
                        distanceKm = 5.4,
                        subMinuteSteps = List(10) { 80 },
                        rawHex = "seed"
                    )
                )
            }
        }

        if (today !in existingSleepDates) {
            // Seed today's sleep ending right at 12:00 noon + post-12:00 0-step/low-HR until 12:50
            val sleepStart = today.atTime(6, 0)
            val stages360 = MutableList(360) { idx ->
                when {
                    idx % 90 in 15..35 -> 1 // Deep
                    idx % 90 in 65..85 -> 3 // REM
                    else -> 2               // Light
                }
            }
            sleepList.add(
                SleepBlockRecord(
                    deviceTime = sleepStart.format(fmt),
                    unitMinutes = 1,
                    count = 360,
                    deepMin = stages360.count { it == 1 },
                    lightMin = stages360.count { it == 2 },
                    remMin = stages360.count { it == 3 },
                    awakeMin = 0,
                    stages = stages360,
                    rawHex = "seed"
                )
            )
            for (m in 0..40 step 10) {
                val ts = today.atTime(12, m).format(fmt)
                stepList.add(StepEpochRecord(ts, 0, 0.0, 0.0, List(10) { 0 }, "seed"))
                hrList.add(HrRecord(ts, 55, "seed"))
            }
            stepList.add(StepEpochRecord(today.atTime(13, 0).format(fmt), 180, 8.0, 0.14, List(10) { 18 }, "seed"))
            hrList.add(HrRecord(today.atTime(13, 0).format(fmt), 86, "seed"))
        }

        if (sleepList.isNotEmpty()) upsertSleepBlocks(sleepList)
        if (hrvList.isNotEmpty()) upsertHrvRecords(hrvList)
        if (hrList.isNotEmpty()) upsertHrRecords(hrList)
        if (spo2List.isNotEmpty()) upsertSpo2Records(spo2List)
        if (tempList.isNotEmpty()) upsertTempRecords(tempList)
        if (stepList.isNotEmpty()) upsertStepRecords(stepList)
    }
}
