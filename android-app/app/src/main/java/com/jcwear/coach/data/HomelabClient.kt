package com.jcwear.coach.data

import com.jcwear.coach.domain.DayMetrics
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Talks to the home server (sterver). URL and token come from [AppSettings] on every call. */
class HomelabClient(private val db: LocalHealthDatabase, private val settings: AppSettings) {

    class HttpError(val code: Int, body: String) : IOException("HTTP $code: ${body.take(120)}")

    val isConfigured: Boolean get() = settings.homelabBaseUrl.isNotBlank()

    /** Uploads every not-yet-sent row of every table (500 per request), then [summaries]. Returns rows sent. */
    fun upload(summaries: List<DayMetrics>): Int {
        var sent = 0
        for (table in LocalHealthDatabase.HISTORY_TABLES) {
            while (true) {
                val rows = db.pendingUpload(table)
                if (rows.isEmpty()) break
                call("POST", "/api/v1/sync", JSONObject().put(table, JSONArray(rows)))
                db.markUploaded(table, rows.map { it.getString("device_time") })
                sent += rows.size
            }
        }
        if (summaries.isNotEmpty()) {
            call("POST", "/api/v1/sync", JSONObject().put("daily_summaries", JSONArray(summaries.map(::summaryJson))))
        }
        return sent
    }

    /** Pulls every table back, inserting only keys missing locally (seed rows never come back). Returns rows added. */
    fun restoreAll(): Int {
        var added = 0
        for (table in LocalHealthDatabase.HISTORY_TABLES) {
            var after = ""
            while (true) {
                val page = call("GET", "/api/v1/restore?all=true&table=$table&limit=500&after=${enc(after)}")
                val arr = page.getJSONArray("rows")
                if (arr.length() == 0) break
                added += db.insertRestored(table, (0 until arr.length()).map { arr.getJSONObject(it) })
                after = page.getString("next_after")
            }
        }
        return added
    }

    fun purgeSeeded(): JSONObject = call("POST", "/api/v1/purge-seeded", JSONObject())

    fun deleteSummaries(dates: List<String>) {
        if (dates.isNotEmpty()) call("DELETE", "/api/v1/summaries?dates=${enc(dates.joinToString(","))}")
    }

    private fun summaryJson(d: DayMetrics) = JSONObject().apply {
        fun opt(k: String, v: Any?) = put(k, v ?: JSONObject.NULL)
        put("date", d.date.toString())
        opt("readiness_score", d.readiness)
        opt("sleep_score", d.sleepScore)
        opt("strain_score", d.strain)
        opt("overnight_hrv_ms", d.hrvMs)
        opt("morning_rmssd_ms", d.morningRmssdMs)
        opt("resting_hr_bpm", d.rhrBpm)
        opt("total_sleep_min", d.sleep?.totalSleepMin)
        opt("deep_sleep_min", d.sleep?.deepMin)
        opt("rem_sleep_min", d.sleep?.remMin)
        opt("light_sleep_min", d.sleep?.lightMin)
        opt("spo2_avg_pct", d.spo2Pct)
        opt("skin_temp_delta_c", d.skinTempDeltaC)
        opt("steps", d.steps)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val base = settings.homelabBaseUrl.trimEnd('/')
        if (base.isBlank()) throw IOException("No server URL set")
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000
            readTimeout = 20000
            setRequestProperty("Content-Type", "application/json")
            settings.homelabApiToken.takeIf { it.isNotBlank() }?.let { setRequestProperty("X-Api-Token", it) }
            doOutput = body != null
        }
        try {
            if (body != null) OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw HttpError(code, text)
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
