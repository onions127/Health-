package com.jcwear.coach.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.jcwear.coach.ble.J2208Protocol.HrRecord
import com.jcwear.coach.ble.J2208Protocol.HrvRecord
import com.jcwear.coach.ble.J2208Protocol.SleepBlockRecord
import com.jcwear.coach.ble.J2208Protocol.Spo2Record
import com.jcwear.coach.ble.J2208Protocol.StepEpochRecord
import com.jcwear.coach.ble.J2208Protocol.TempRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class LocalHealthDatabase(private val appContext: Context) :
    SQLiteOpenHelper(appContext, "jcwear_local_health.db", null, 3) {

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
        addUploadedColumns(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) addUploadedColumns(db)
    }

    /** v3: per-row "sent to the home server" flag, so band backfills with old timestamps still upload. */
    private fun addUploadedColumns(db: SQLiteDatabase) {
        for (table in HISTORY_TABLE_COLUMNS.keys) {
            db.execSQL("ALTER TABLE $table ADD COLUMN uploaded INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * UPDATE-then-INSERT for band data (ON CONFLICT DO UPDATE needs API 30; minSdk is 26).
     * `uploaded` is reset only when the row's data actually changed, so re-fetching history doesn't re-upload it.
     */
    private fun writeBandRow(db: SQLiteDatabase, table: String, cv: ContentValues) {
        if (db.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) return
        val cols = cv.keySet().filter { it != "device_time" }
        val changed = ContentValues(cv).apply { remove("device_time"); put("uploaded", 0) }
        db.update(
            table, changed,
            "device_time = ? AND (" + cols.joinToString(" OR ") { "$it IS NOT ?" } + ")",
            arrayOf(cv.getAsString("device_time")) + cols.map { cv.getAsString(it) }
        )
    }

    /** Up to [limit] rows not yet on the server, as JSON in the server's field names. */
    fun pendingUpload(table: String, limit: Int = 500): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        readableDatabase.rawQuery("SELECT * FROM $table WHERE uploaded = 0 ORDER BY device_time LIMIT $limit", null).use { c ->
            while (c.moveToNext()) {
                val o = JSONObject()
                for (i in 0 until c.columnCount) {
                    val col = c.getColumnName(i)
                    if (col == "uploaded") continue
                    val name = TO_SERVER[col] ?: col
                    when {
                        col.endsWith("_csv") -> o.put(name, JSONArray((c.getString(i) ?: "").split(",").mapNotNull { it.toIntOrNull() }))
                        c.getType(i) == Cursor.FIELD_TYPE_INTEGER -> o.put(name, c.getLong(i))
                        c.getType(i) == Cursor.FIELD_TYPE_FLOAT -> o.put(name, c.getDouble(i))
                        c.isNull(i) -> o.put(name, JSONObject.NULL)
                        else -> o.put(name, c.getString(i))
                    }
                }
                out += o
            }
        }
        return out
    }

    fun markUploaded(table: String, deviceTimes: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            deviceTimes.forEach { db.execSQL("UPDATE $table SET uploaded = 1 WHERE device_time = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Inserts server rows whose key is missing locally (a local row always wins); returns rows added. */
    fun insertRestored(table: String, rows: List<JSONObject>): Int {
        val columns = HISTORY_TABLE_COLUMNS[table] ?: return 0
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            for (o in rows) {
                if (o.optString("raw") == "seed") continue // belt and braces: the server already filters these
                val cv = ContentValues().apply { put("uploaded", 1) }
                for (col in columns) {
                    val key = TO_SERVER[col] ?: col
                    if (!o.has(key) || o.isNull(key)) continue
                    if (col.endsWith("_csv")) {
                        val arr = o.getJSONArray(key)
                        cv.put(col, (0 until arr.length()).joinToString(",") { arr.get(it).toString() }) // column affinity converts numeric text
                    } else {
                        cv.put(col, o.get(key).toString()) // column affinity converts numeric text
                    }
                }
                if (db.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }


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
                writeBandRow(db, "sleep_blocks", cv)
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
                writeBandRow(db, "hrv_logs", cv)
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
                writeBandRow(db, "hr_logs", cv)
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
                writeBandRow(db, "spo2_logs", cv)
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
                writeBandRow(db, "temp_logs", cv)
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
                writeBandRow(db, "step_logs", cv)
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

    /**
     * Consistent copy of the live DB to [target]: VACUUM INTO (SQLite 3.27+), else WAL checkpoint + file copy.
     * Throws if the copy can't be made, so callers never delete without a backup.
     */
    fun backupTo(target: File) {
        if (target.exists() && !target.delete()) throw IOException("Can't replace ${target.path}")
        val db = writableDatabase
        try {
            db.execSQL("VACUUM INTO ?", arrayOf(target.absolutePath))
        } catch (e: SQLiteException) {
            Log.i(TAG, "VACUUM INTO unavailable (${e.message}); checkpoint + copy instead")
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            File(db.path).copyTo(target, overwrite = true)
        }
        if (!target.isFile || target.length() == 0L) throw IOException("Backup missing at ${target.path}")
    }

    /** Deletes the old seeder's fake rows (raw_hex = 'seed') in one transaction; returns counts per table. */
    fun deleteSeedRows(): Map<String, Int> {
        val db = writableDatabase
        val counts = linkedMapOf<String, Int>()
        db.beginTransaction()
        try {
            for (table in HISTORY_TABLE_COLUMNS.keys) {
                counts[table] = db.delete(table, "raw_hex = ?", arrayOf("seed"))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return counts
    }

    companion object {
        private const val TAG = "LocalHealthDatabase"
        const val BUNDLED_HISTORY_ASSET = "jcwear_local_health.db"

        /** Local column -> server field name, where they differ. */
        private val TO_SERVER = mapOf(
            "hrv_ms" to "hrv", "hr_bpm" to "hr", "raw_hex" to "raw",
            "stages_csv" to "stages", "sub_steps_csv" to "step_series"
        )

        val HISTORY_TABLES: Set<String> get() = HISTORY_TABLE_COLUMNS.keys

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
