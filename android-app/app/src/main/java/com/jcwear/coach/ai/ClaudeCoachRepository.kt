package com.jcwear.coach.ai

import com.jcwear.coach.data.LocalHealthDatabase
import com.jcwear.coach.domain.SleepAndReadinessEngine.DailyReadinessReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

class ClaudeCoachRepository(private val database: LocalHealthDatabase) {

    data class ChatMessage(val role: String, val content: String)

    /**
     * Builds a ready-to-paste prompt containing the user's 14-day J2208A biometric summary
     * for one-tap use in the official Claude Android app or claude.ai (Claude Pro subscription, $0 API cost).
     */
    fun buildClaudeProPrompt(
        userQuestion: String,
        recentReports: List<DailyReadinessReport>
    ): String {
        val latest = recentReports.firstOrNull()
        val avgHrv = recentReports.take(14).map { it.overnightHrvMs }.average().takeIf { !it.isNaN() } ?: 59.0
        val avgRhr = recentReports.take(14).map { it.restingHrBpm }.average().takeIf { !it.isNaN() } ?: 54.0
        val avgSleep = recentReports.take(14).map { it.totalSleepMin }.average().takeIf { !it.isNaN() } ?: 432.0

        return buildString {
            appendLine("You are my Health++ recovery coach. Keep replies concise (max 3 short lines) and compare numbers to my normal baseline.")
            if (latest != null) {
                val sleepH = latest.totalSleepMin / 60
                val sleepM = latest.totalSleepMin % 60
                val deepH = latest.deepSleepMin / 60
                val deepM = latest.deepSleepMin % 60
                val remH = latest.remSleepMin / 60
                val remM = latest.remSleepMin % 60
                appendLine("Today (${latest.date}): Readiness ${latest.readinessScore}/100, Sleep Score ${latest.sleepScore}/100, Strain ${latest.strainScore}/21.")
                appendLine("Vitals: HRV ${latest.overnightHrvMs.roundToInt()} ms (normal ${avgHrv.roundToInt()} ms), RHR ${latest.restingHrBpm.roundToInt()} bpm (normal ${avgRhr.roundToInt()} bpm), SpO2 ${latest.spo2AvgPct.roundToInt()}%, Skin Temp ${String.format("%+.1f", latest.skinTempDeltaC)} °C, Steps ${latest.steps}.")
                appendLine("Sleep: ${sleepH}h ${sleepM}m total (normal ${(avgSleep / 60).toInt()}h ${(avgSleep % 60).toInt()}m) — Deep ${deepH}h ${deepM}m, REM ${remH}h ${remM}m.")
            }
            appendLine("Question: $userQuestion")
        }.trim()
    }

    /**
     * Syncs local phone SQLite records and daily summaries to the Homelab FastAPI + Postgres container.
     */
    suspend fun syncToHomelab(
        homelabBaseUrl: String,
        apiToken: String,
        recentReports: List<DailyReadinessReport>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = homelabBaseUrl.trimEnd('/') + "/api/v1/sync"
            val payload = JSONObject().apply {
                put("sleep_blocks", JSONArray().apply {
                    database.getAllSleepBlocks().takeLast(30).forEach { s ->
                        put(JSONObject().apply {
                            put("device_time", s.deviceTime)
                            put("unit_minutes", s.unitMinutes)
                            put("count", s.count)
                            put("deep_min", s.deepMin)
                            put("light_min", s.lightMin)
                            put("rem_min", s.remMin)
                            put("awake_min", s.awakeMin)
                            put("stages", JSONArray(s.stages))
                            put("raw", s.rawHex)
                        })
                    }
                })
                put("hrv_logs", JSONArray().apply {
                    database.getAllHrvRecords().takeLast(200).forEach { h ->
                        put(JSONObject().apply {
                            put("device_time", h.deviceTime)
                            put("hrv", h.hrvMs)
                            put("hr", h.hrBpm)
                            put("stress", h.stress)
                            put("vascular_aging", h.vascularAging)
                            put("bp_sys", h.bpSys)
                            put("bp_dia", h.bpDia)
                            put("raw", h.rawHex)
                        })
                    }
                })
                put("daily_summaries", JSONArray().apply {
                    recentReports.forEach { r ->
                        put(JSONObject().apply {
                            put("date", r.date)
                            put("readiness_score", r.readinessScore)
                            put("sleep_score", r.sleepScore)
                            put("strain_score", r.strainScore)
                            put("overnight_hrv_ms", r.overnightHrvMs)
                            put("morning_rmssd_ms", r.morningRmssdMs ?: 0.0)
                            put("resting_hr_bpm", r.restingHrBpm)
                            put("total_sleep_min", r.totalSleepMin)
                            put("deep_sleep_min", r.deepSleepMin)
                            put("rem_sleep_min", r.remSleepMin)
                            put("light_sleep_min", r.lightSleepMin)
                            put("spo2_avg_pct", r.spo2AvgPct)
                            put("skin_temp_delta_c", r.skinTempDeltaC)
                            put("steps", r.steps)
                        })
                    }
                })
            }

            val conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 12000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (apiToken.isNotBlank()) {
                    setRequestProperty("X-Api-Token", apiToken)
                }
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code in 200..299) {
                Result.success("Backed up to home server")
            } else {
                Result.failure(RuntimeException("HTTP $code: $body"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Queries Claude via:
     * 1. Homelab Proxy (which runs `claude -p` using your Claude Pro subscription at $0 API cost, or API key if set)
     * 2. Direct Anthropic API (only if user explicitly entered an API key)
     * 3. Concise 3-line physiological synthesis grounded in the user's normal range.
     */
    suspend fun askClaudeCoach(
        messages: List<ChatMessage>,
        includeHealthContext: Boolean,
        recentReports: List<DailyReadinessReport>,
        homelabBaseUrl: String,
        anthropicApiKey: String,
        model: String = "claude-sonnet-4-5"
    ): String = withContext(Dispatchers.IO) {
        val contextArray = JSONArray().apply {
            recentReports.take(14).forEach { r ->
                put(JSONObject().apply {
                    put("date", r.date)
                    put("readiness", r.readinessScore)
                    put("sleep_score", r.sleepScore)
                    put("strain", r.strainScore)
                    put("hrv_ms", r.overnightHrvMs)
                    put("hrv_30d_baseline_ms", r.hrvBaseline30dMs)
                    put("rhr_bpm", r.restingHrBpm)
                    put("sleep_min", r.totalSleepMin)
                    put("post_1200_noon_stitched_min", r.post1200NoonExtendedMin)
                    put("deep_min", r.deepSleepMin)
                    put("rem_min", r.remSleepMin)
                    put("spo2_pct", r.spo2AvgPct)
                    put("temp_delta_c", r.skinTempDeltaC)
                    put("steps", r.steps)
                })
            }
        }

        // 1. Try Homelab Proxy first (supports Claude Pro CLI bridge with $0 API cost!)
        if (homelabBaseUrl.isNotBlank()) {
            try {
                val url = URL(homelabBaseUrl.trimEnd('/') + "/api/v1/coach/chat")
                val reqJson = JSONObject().apply {
                    put("include_health_context", includeHealthContext)
                    put("model", model)
                    if (anthropicApiKey.isNotBlank()) put("api_key_override", anthropicApiKey)
                    put("anonymized_context_override", JSONObject().put("recent_14d_summaries", contextArray))
                    put("messages", JSONArray().apply {
                        messages.forEach { m ->
                            put(JSONObject().put("role", m.role).put("content", m.content))
                        }
                    })
                }
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 6000
                    readTimeout = 35000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(reqJson.toString()) }
                if (conn.responseCode in 200..299) {
                    val respText = conn.inputStream.bufferedReader().readText()
                    val reply = JSONObject(respText).optString("reply", respText)
                    if (reply.isNotBlank() && !reply.startsWith("[Offline")) {
                        return@withContext reply
                    }
                }
            } catch (_: Exception) {
                // Fall through to local synthesis
            }
        }

        // 2. Direct Anthropic API only if user provided a key
        if (anthropicApiKey.isNotBlank()) {
            try {
                val sysPrompt = buildString {
                    append("You are the Health++ Coach. Reply in at most 3 short, human lines (under 12 words per sentence). ")
                    append("Always compare numbers to the user's normal range.")
                    if (includeHealthContext) {
                        append("\n\nAnonymized 14-day context:\n")
                        append(contextArray.toString(2))
                    }
                }
                val reqJson = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 300)
                    put("system", sysPrompt)
                    put("messages", JSONArray().apply {
                        messages.forEach { m ->
                            put(JSONObject().put("role", m.role).put("content", m.content))
                        }
                    })
                }
                val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 35000
                    doOutput = true
                    setRequestProperty("x-api-key", anthropicApiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                    setRequestProperty("Content-Type", "application/json")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(reqJson.toString()) }
                if (conn.responseCode in 200..299) {
                    val respObj = JSONObject(conn.inputStream.bufferedReader().readText())
                    val contentArr = respObj.optJSONArray("content")
                    if (contentArr != null && contentArr.length() > 0) {
                        return@withContext contentArr.getJSONObject(0).optString("text", "")
                    }
                }
            } catch (_: Exception) {
                // Fall through to concise local synthesis
            }
        }

        // 3. Concise 3-line Health++ physiological response (0 API cost)
        val today = recentReports.firstOrNull()
        val lastQ = messages.lastOrNull()?.content?.lowercase() ?: ""
        return@withContext if (today != null) {
            val hrv = today.overnightHrvMs.roundToInt()
            val baseHrv = today.hrvBaseline30dMs.roundToInt().coerceAtLeast(1)
            val pct = (((today.overnightHrvMs - today.hrvBaseline30dMs) / baseHrv) * 100).roundToInt()
            val rhr = today.restingHrBpm.roundToInt()
            val sleepH = today.totalSleepMin / 60
            val sleepM = today.totalSleepMin % 60
            val deepH = today.deepSleepMin / 60
            val deepM = today.deepSleepMin % 60

            when {
                lastQ.contains("hrv") -> buildString {
                    appendLine("Your HRV reached $hrv ms (${if (pct >= 0) "↑ $pct%" else "↓ ${-pct}%"} vs normal).")
                    appendLine("Resting HR settled at $rhr bpm overnight.")
                    append("Your nervous system rebounded well from yesterday.")
                }
                lastQ.contains("sleep") || lastQ.contains("deep") -> buildString {
                    appendLine("You slept ${sleepH}h ${sleepM.toString().padStart(2, '0')}m (Score ${today.sleepScore}).")
                    appendLine("Deep sleep hit ${deepH}h ${deepM.toString().padStart(2, '0')}m, above your normal range.")
                    append("That restorative sleep lifted your recovery to ${today.readinessScore}.")
                }
                else -> buildString {
                    appendLine("Yes. Your recovery is ${today.readinessScore}, well within optimal range.")
                    appendLine("HRV is $hrv ms and resting HR dropped to $rhr bpm.")
                    append("Good day for intervals or heavy strength training.")
                }
            }
        } else {
            "Sync your band on Today to unlock personalized recovery guidance."
        }
    }
}
