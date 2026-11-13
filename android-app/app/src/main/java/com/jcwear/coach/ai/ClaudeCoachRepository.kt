package com.jcwear.coach.ai

import com.jcwear.coach.data.LocalHealthDatabase
import com.jcwear.coach.domain.DayMetrics
import com.jcwear.coach.domain.MetricInsights
import com.jcwear.coach.domain.Metrics
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
        recentReports: List<DayMetrics>
    ): String {
        val latest = recentReports.firstOrNull()
        fun avg(f: (DayMetrics) -> Double?) = recentReports.take(14).mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
        fun hm(min: Int?) = min?.let { "${it / 60}h ${it % 60}m" } ?: "not recorded"
        fun n(v: Number?, unit: String = "") = v?.let { "${(it.toDouble()).roundToInt()}$unit" } ?: "not recorded"

        return buildString {
            appendLine("You are my Health++ recovery coach. Keep replies concise (max 3 short lines) and compare numbers to my normal baseline. \"not recorded\" means the band has no data; don't guess it.")
            if (latest != null) {
                appendLine("Today (${latest.date}): Readiness ${n(latest.readiness)}/100, Sleep Score ${n(latest.sleepScore)}/100, Strain ${latest.strain ?: "not recorded"}/21.")
                appendLine("Vitals: HRV ${n(latest.hrvMs, " ms")} (normal ${n(avg { it.hrvMs }, " ms")}), RHR ${n(latest.rhrBpm, " bpm")} (normal ${n(avg { it.rhrBpm }, " bpm")}), SpO2 ${n(latest.spo2Pct, "%")}, Skin Temp ${latest.skinTempDeltaC?.let { String.format("%+.1f °C", it) } ?: "not recorded"}, Steps ${latest.steps ?: "not recorded"}.")
                appendLine("Sleep: ${hm(latest.sleepMin)} total (normal ${hm(avg { it.sleepMin?.toDouble() }?.roundToInt())}) — Deep ${hm(latest.sleep?.deepMin)}, REM ${hm(latest.sleep?.remMin)}.")
            }
            appendLine("Question: $userQuestion")
        }.trim()
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
        recentReports: List<DayMetrics>,
        homelabBaseUrl: String,
        apiToken: String,
        anthropicApiKey: String,
        model: String = "claude-sonnet-4-5"
    ): String = withContext(Dispatchers.IO) {
        val contextArray = JSONArray().apply {
            recentReports.take(14).forEach { r ->
                put(JSONObject().apply {
                    put("date", r.date.toString())
                    put("readiness", r.readiness ?: JSONObject.NULL)
                    put("sleep_score", r.sleepScore ?: JSONObject.NULL)
                    put("strain", r.strain ?: JSONObject.NULL)
                    put("hrv_ms", r.hrvMs ?: JSONObject.NULL)
                    put("hrv_30d_baseline_ms", MetricInsights.baseline(Metrics.hrv, recentReports, r.date)?.mean ?: JSONObject.NULL)
                    put("rhr_bpm", r.rhrBpm ?: JSONObject.NULL)
                    put("sleep_min", r.sleepMin ?: JSONObject.NULL)
                    put("post_1200_noon_stitched_min", r.sleep?.post1200NoonExtendedMin ?: JSONObject.NULL)
                    put("deep_min", r.sleep?.deepMin ?: JSONObject.NULL)
                    put("rem_min", r.sleep?.remMin ?: JSONObject.NULL)
                    put("spo2_pct", r.spo2Pct ?: JSONObject.NULL)
                    put("temp_delta_c", r.skinTempDeltaC ?: JSONObject.NULL)
                    put("steps", r.steps ?: JSONObject.NULL)
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
                    if (apiToken.isNotBlank()) setRequestProperty("X-Api-Token", apiToken)
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

        // 3. Local fallback from real values only (0 API cost)
        val today = recentReports.firstOrNull()
        val hrv = today?.hrvMs?.roundToInt()
        val rhr = today?.rhrBpm?.roundToInt()
        if (today == null || hrv == null || rhr == null) {
            return@withContext "Coach is offline. Your data is safe; try again later."
        }
        val base = MetricInsights.baseline(Metrics.hrv, recentReports, today.date)
        val vsNormal = base?.let {
            val pct = ((today.hrvMs - it.mean) / it.mean * 100).roundToInt()
            " (${if (pct >= 0) "↑ $pct%" else "↓ ${-pct}%"} vs normal)"
        } ?: ""
        return@withContext buildString {
            appendLine("Coach is offline, so here are your numbers.")
            appendLine("HRV $hrv ms$vsNormal, resting HR $rhr bpm.")
            append(today.sleepMin?.let { "You slept ${it / 60}h ${(it % 60).toString().padStart(2, '0')}m." } ?: "No sleep recorded last night.")
        }
    }
}
