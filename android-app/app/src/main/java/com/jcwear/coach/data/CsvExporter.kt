package com.jcwear.coach.data

import com.jcwear.coach.domain.DayMetrics
import java.io.File
import java.io.Writer
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Settings → Export all data (plan S4): one zip, one CSV per table plus a daily summary. */
class CsvExporter(private val db: LocalHealthDatabase, private val dir: File) {

    /**
     * Streams every table row by row into `health-plus-<date>.zip`. Written to a tmp file first and renamed,
     * so a failed export never touches the previous zip; older exports go only after the rename.
     */
    fun export(days: List<DayMetrics>, date: LocalDate, now: Long = System.currentTimeMillis()): File {
        dir.mkdirs()
        val out = File(dir, "health-plus-$date.zip")
        val tmp = File(dir, "${out.name}.tmp-$now")
        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                val w = zip.writer(Charsets.UTF_8)
                for (table in TABLES) {
                    zip.putNextEntry(ZipEntry("$table.csv"))
                    db.readableDatabase.rawQuery("SELECT * FROM $table ORDER BY 1", null).use { c ->
                        w.row(c.columnNames.asList())
                        while (c.moveToNext()) w.row((0 until c.columnCount).map { c.getString(it) })
                    }
                    w.flush()
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("daily_summary.csv"))
                w.row(SUMMARY_HEADER)
                days.filter { it.hasData }.forEach { d ->
                    val pts = d.readinessParts?.shownPoints() ?: List(4) { null }
                    w.row(listOf(
                        d.date, d.sleep?.startTime, d.sleep?.endTime, d.sleepMin, d.sleep?.deepMin, d.sleep?.remMin,
                        d.sleep?.provenance, d.sleepScore, d.hrvMs, d.rhrBpm, d.spo2Pct, d.skinTempDeltaC, d.steps,
                        d.distanceKm, d.caloriesKcal, d.strain, d.readiness, d.recovery, d.daily, d.morningRmssdMs,
                        d.skinTempC, d.skinTempBaseC, d.weightKg, pts[0], pts[1], pts[2], pts[3]
                    ).map { it?.toString() })
                }
                w.flush()
                zip.closeEntry()
            }
            if (!tmp.renameTo(out) && !(out.delete() && tmp.renameTo(out))) throw java.io.IOException("Couldn't save ${out.name}")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        dir.listFiles()?.filter { it != out && it.name.startsWith("health-plus-") }?.forEach { it.delete() }
        return out
    }

    companion object {
        val TABLES = LocalHealthDatabase.HISTORY_TABLES + "sleep_labels" + "morning_checks" + "weight_logs"
        val SUMMARY_HEADER = listOf(
            "date", "sleep_start", "sleep_end", "sleep_min", "deep_min", "rem_min", "sleep_source", "sleep_score",
            "hrv_ms", "rhr_bpm", "spo2_pct", "skin_temp_delta_c", "steps", "distance_km", "calories_kcal",
            "strain", "readiness", "recovery", "daily",
            // R1 onward: new columns are appended, never inserted. hrv_ms is the overnight average.
            "morning_rmssd_ms",
            // R3: skin_temp_delta_c is skin_temp_c - skin_temp_base_c; all three stay in °C.
            "skin_temp_c", "skin_temp_base_c", "weight_kg",
            // R4: whole points that add up to readiness; empty when the part had no data (vitals = SpO2 and skin temp).
            "readiness_hrv_pts", "readiness_rhr_pts", "readiness_sleep_pts", "readiness_vitals_pts"
        )

        /** RFC 4180: quote fields with comma, quote or line break; double inner quotes; null = empty. */
        fun csvField(v: String?): String = when {
            v == null -> ""
            v.any { it == ',' || it == '"' || it == '\n' || it == '\r' } -> "\"" + v.replace("\"", "\"\"") + "\""
            else -> v
        }

        private fun Writer.row(fields: List<String?>) {
            write(fields.joinToString(",") { csvField(it) })
            write("\r\n")
        }
    }
}
