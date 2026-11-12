package com.jcwear.coach.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.jcwear.coach.ble.J2208Protocol.HrRecord
import com.jcwear.coach.ble.J2208Protocol.HrvRecord
import com.jcwear.coach.ble.J2208Protocol.SleepBlockRecord
import com.jcwear.coach.ble.J2208Protocol.Spo2Record
import com.jcwear.coach.ble.J2208Protocol.StepEpochRecord
import com.jcwear.coach.ble.J2208Protocol.TempRecord
import java.io.File
import java.io.FileNotFoundException

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
     * Result of merging an external band-history database into this one.
     * [insertedByTable] counts only rows that were new; rows whose device_time already exists
     * locally are left untouched (the local row always wins).
     */
    data class ImportResult(val insertedByTable: Map<String, Int>) {
        val totalInserted: Int get() = insertedByTable.values.sum()
    }

    /**
     * Merges the band-history dump bundled in assets ([BUNDLED_HISTORY_ASSET], the owner's real
     * J2208 data) into the live database with INSERT OR IGNORE. Never overwrites or deletes.
     *
     * Android can't open an asset as a SQLite file directly, so it is copied to the cache dir,
     * ATTACHed, merged and then deleted. Returns null if the asset isn't packaged (it is
     * gitignored, so a fresh clone builds without it).
     */
    fun importBundledHistory(): ImportResult? {
        val tmp = File(appContext.cacheDir, "bundled_history_import.db")
        try {
            appContext.assets.open(BUNDLED_HISTORY_ASSET).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: FileNotFoundException) {
            Log.i(TAG, "No bundled history asset packaged; skipping import")
            return null
        }
        try {
            return importHistoryFrom(tmp)
        } finally {
            tmp.delete()
        }
    }

    /** Merges every known table from [source] into this DB; a local row with the same key wins. */
    fun importHistoryFrom(source: File): ImportResult {
        val db = writableDatabase
        val inserted = linkedMapOf<String, Int>()
        db.execSQL("ATTACH DATABASE ? AS src", arrayOf(source.absolutePath))
        try {
            val sourceTables = db.rawQuery(
                "SELECT name FROM src.sqlite_master WHERE type = 'table'", null
            ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

            db.beginTransaction()
            try {
                for ((table, columns) in HISTORY_TABLE_COLUMNS) {
                    if (table !in sourceTables) {
                        inserted[table] = 0
                        continue
                    }
                    val cols = columns.joinToString(", ")
                    db.execSQL("INSERT OR IGNORE INTO main.$table ($cols) SELECT $cols FROM src.$table")
                    inserted[table] = db.rawQuery("SELECT changes()", null)
                        .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.execSQL("DETACH DATABASE src")
        }
        val result = ImportResult(inserted)
        Log.i(TAG, "History import inserted ${result.totalInserted} rows: $inserted")
        return result
    }

    companion object {
        private const val TAG = "LocalHealthDatabase"
        const val BUNDLED_HISTORY_ASSET = "jcwear_local_health.db"

        /** Column lists must match onCreate; used so the merge never depends on column order. */
        private val HISTORY_TABLE_COLUMNS = linkedMapOf(
            "sleep_blocks" to listOf("device_time", "unit_minutes", "count", "deep_min", "light_min", "rem_min", "awake_min", "stages_csv", "raw_hex"),
            "hrv_logs" to listOf("device_time", "hrv_ms", "vascular_aging", "hr_bpm", "stress", "bp_sys", "bp_dia", "raw_hex"),
            "hr_logs" to listOf("device_time", "bpm", "raw_hex"),
            "spo2_logs" to listOf("device_time", "spo2_pct", "raw_hex"),
            "temp_logs" to listOf("device_time", "temp_c", "raw_hex"),
            "step_logs" to listOf("device_time", "steps", "calories_kcal", "distance_km", "sub_steps_csv", "raw_hex")
        )
    }
}
