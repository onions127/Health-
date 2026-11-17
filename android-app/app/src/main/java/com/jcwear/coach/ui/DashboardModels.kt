package com.jcwear.coach.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcwear.coach.ai.ClaudeCoachRepository
import com.jcwear.coach.ble.J2208BleManager
import com.jcwear.coach.data.LocalHealthDatabase
import com.jcwear.coach.domain.SleepAndReadinessEngine
import com.jcwear.coach.domain.DailySeriesRepository
import com.jcwear.coach.domain.DayMetrics
import com.jcwear.coach.domain.Status
import com.jcwear.coach.ble.SyncFailureReason
import com.jcwear.coach.ble.SyncState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Health++ colour tokens (DESIGN.md: graphite, flat, no borders on panels).
 */
object HealthColors {
    val BgDeep = Color(0xFF0D0F13)
    val BgCard = Color(0xFF181B21)
    val BgCardElevated = Color(0xFF20242C)
    val BorderHairline = Color(0x10FFFFFF) // white at 6% opacity

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFFA6ADBA)
    val TextInverse = Color(0xFF0D0F13)

    val AccentAmber = Color(0xFFFFB86B)
    val ScoreGreen = Color(0xFF34D399)
    val ScoreAmber = Color(0xFFFBBF24)
    val ScoreCoral = Color(0xFFFB7185)
    val AlertRed = Color(0xFFEF4444)
    val AlertBg = Color(0xFF2A121B)

    // R6.1: the former inline literals, values unchanged. White overlays are named by job.
    val FillFaint = Color(0x0DFFFFFF)
    val FillSubtle = Color(0x12FFFFFF)
    val Track = Color(0x14FFFFFF)
    val TrackStrong = Color(0x18FFFFFF)
    val NormalBand = Color(0x29FFFFFF)
    val NavBar = Color(0xF50D0F13)
    val Scrim = Color(0xB8060709)
    val GoodText = Color(0xFF6EE7B7)
    val WarnBg = Color(0x26FBBF24)
    val BadText = Color(0xFFFDA4AF)
    val AlertTextStrong = Color(0xFFFECACA)
    val AlertText = Color(0xFFFCA5A5)

    val StageDeep = Color(0xFF3D63DD) // deep blue, not indigo: no violet in the palette
    val StageRem = Color(0xFF38BDF8)
    val StageLight = Color(0xFF34D399)
    val StageAwake = Color(0xFFFB7185)

    fun forScore(score: Int): Color = when {
        score >= 70 -> ScoreGreen
        score >= 40 -> ScoreAmber
        else -> ScoreCoral
    }
}

enum class MainTab { TODAY, TRENDS, COACH, SETTINGS }
enum class ActiveSheet { NONE, SYNC, VERDICT, CALENDAR, SERVER_CONFIG }
enum class CheckFlowStep { NONE, INTRO, MEASURING, RESULT }

fun formatSleepHm(totalMin: Int): String {
    val hrs = totalMin / 60
    val mins = totalMin % 60
    return "${hrs}h ${mins.toString().padStart(2, '0')}m"
}

/** Short day label, e.g. "Thu 12 Nov". */
val DayMetrics.label: String get() = date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))

fun statusColor(st: Status): Color = when (st) {
    Status.GOOD -> HealthColors.ScoreGreen
    Status.BAD -> HealthColors.ScoreCoral
    else -> HealthColors.TextSecondary
}

/** Real days, oldest first, ending today. Pure apart from the DB read, so it can be golden-tested. */
fun buildDashboardState(
    database: LocalHealthDatabase,
    today: LocalDate,
    heightCm: Double? = null,
    profileWeightKg: Double? = null
): List<DayMetrics> = DailySeriesRepository(database).load(today, heightCm, profileWeightKg)

/** "just now", "12 min ago", "3 h ago", "2 days ago". */
fun agoText(thenMs: Long, nowMs: Long): String {
    val min = (nowMs - thenMs).coerceAtLeast(0) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 48 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }
}

/** Today's sync pill (plan 1.5): text and whether it needs attention (amber). */
fun syncPillText(state: SyncState, nowMs: Long): Pair<String, Boolean> = when (state) {
    SyncState.Idle -> "Not synced yet · Sync" to true
    SyncState.Connecting, is SyncState.Syncing -> "Syncing…" to false
    is SyncState.Synced ->
        if (nowMs - state.at > 6 * 3_600_000L) "Last sync ${agoText(state.at, nowMs)}" to true
        else "Synced ${agoText(state.at, nowMs)}" to false
    is SyncState.Failed -> when (state.reason) {
        SyncFailureReason.BLUETOOTH_OFF -> "Bluetooth off · Turn on"
        SyncFailureReason.PERMISSION_MISSING -> "Allow Bluetooth"
        SyncFailureReason.BAND_NOT_FOUND -> "Band not found · Retry"
        SyncFailureReason.GATT_ERROR -> "Couldn't connect · Retry"
        SyncFailureReason.HISTORY_TIMEOUT -> "Sync incomplete · Retry"
    } to true
}

/** One "Recent syncs" line from a SyncCoordinator event, e.g. "13 Nov 09:12 · OPEN · OK · 1240 rows · 48 s · backup OK". */
fun recentSyncLine(e: org.json.JSONObject): String = listOfNotNull(
    java.time.Instant.ofEpochMilli(e.optLong("at")).atZone(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)),
    e.optString("trigger"),
    e.optString("result").takeIf { it.isNotEmpty() }?.let { r -> e.optString("reason").takeIf { it.isNotEmpty() }?.let { "$r ($it)" } ?: r },
    e.optInt("rows", -1).takeIf { it >= 0 }?.let { "$it rows" },
    e.optLong("ms", -1).takeIf { it >= 0 }?.let { "${it / 1000} s" },
    e.optString("backup").takeIf { it.isNotEmpty() }?.let { "backup $it" },
    e.optString("morning").takeIf { it.isNotEmpty() }?.let { "morning $it" },
    e.optLong("drift_s", 0).takeIf { kotlin.math.abs(it) > 120 }?.let { "clock ${it / 60} min off" }
).joinToString(" · ")
