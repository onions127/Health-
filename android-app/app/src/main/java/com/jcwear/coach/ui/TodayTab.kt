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
import com.jcwear.coach.domain.DayMetrics
import com.jcwear.coach.domain.MetricDef
import com.jcwear.coach.domain.MetricInsights
import com.jcwear.coach.domain.Metrics
import com.jcwear.coach.domain.Status
import com.jcwear.coach.domain.greetingFor
import com.jcwear.coach.domain.verdictFor
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================================
// TODAY SCREEN
// ============================================================================
@Composable
internal fun TodayTabView(
    day: DayMetrics,
    days: List<DayMetrics>,
    isHistoricalView: Boolean,
    morningCheckDone: Boolean,
    dailyExpanded: Boolean,
    recoveryExpanded: Boolean,
    syncPill: Pair<String, Boolean>,
    syncing: Boolean,
    batteryPct: Int?,
    useMetricUnits: Boolean,
    userName: String,
    onSyncPillClick: () -> Unit,
    onExitHistory: () -> Unit,
    onStartMorningCheck: () -> Unit,
    onOpenVerdictSheet: () -> Unit,
    onToggleDailyExpand: () -> Unit,
    onToggleRecoveryExpand: () -> Unit,
    onOpenMetricDetail: (String) -> Unit
,
    lastSyncAt: Long = 0L
) {
    val spo2Low = day.spo2Pct?.let { it < 95.0 } == true

    // 1. Greeting and date + sync status dot (48dp tap target)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isHistoricalView) day.label else greetingFor(LocalTime.now(), userName),
                color = HealthColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (isHistoricalView) "Historical snapshot" else day.label,
                color = HealthColors.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isHistoricalView) {
                AmberActionButton(text = "Back to today", onClick = onExitHistory)
            }
        }
    }

    // Sync status pill + band battery (always visible, plan 1.5)
    if (!isHistoricalView) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SyncPill(text = syncPill.first, attention = syncPill.second, syncing = syncing, onClick = onSyncPillClick)
            BatteryBadge(batteryPct)
        }
    }

    // 2. Morning Check prompt (until done that day)
    if (!morningCheckDone && !isHistoricalView) {
        HealthCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Morning check", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                AmberActionButton(text = "Start 60-sec check", onClick = onStartMorningCheck)
            }
        }
    }

    // Empty state: fewer than 3 days of band data (DS3)
    if (days.count { it.hasData } < 3) {
        HealthCard {
            Text(
                "Wear your band tonight. Your first scores appear tomorrow morning.",
                color = HealthColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        return
    }

    // 4. Alert when overnight blood oxygen is low (real value)
    if (spo2Low) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable { onOpenMetricDetail("spo2") },
            color = HealthColors.AlertBg,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, HealthColors.AlertRed.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Blood oxygen low overnight: ${Metrics.spo2.format(day.spo2Pct!!)}",
                        color = Color(0xFFFECACA),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${MetricInsights.chip(Metrics.spo2, days, day.date).first} · Tap for details",
                        color = Color(0xFFFCA5A5),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text("›", color = Color(0xFFFCA5A5), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // 5. Daily Score (expands in place to Readiness / Sleep / Strain)
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScoreHeaderRow("Daily Score", day.daily, Metrics.daily, days, day, onToggleDailyExpand)
            ScoreSource("Daily Score", day.dailyParts, lastSyncAt)
            AnimatedVisibility(
                visible = dailyExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = HealthColors.BorderHairline)
                    listOf(
                        Triple("Readiness", Metrics.readiness, "readiness"),
                        Triple("Sleep", Metrics.sleepScore, "sleep"),
                        Triple("Strain", Metrics.strain, "strain")
                    ).forEach { (title, def, target) ->
                        val base = MetricInsights.baseline(def, days, day.date)
                        SubMetricBarRow(
                            title = title,
                            contextText = MetricInsights.chip(def, days, day.date).first,
                            score = def.extract(day)?.roundToInt(),
                            normalMin = base?.low?.roundToInt(),
                            normalMax = base?.high?.roundToInt(),
                            barColor = if (def == Metrics.strain) HealthColors.AccentAmber else HealthColors.ScoreGreen,
                            onClick = { onOpenMetricDetail(target) }
                        )
                    }
                }
            }
        }
    }

    // 6. Recovery (expands to HRV & Resting HR with 7-day sparklines)
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScoreHeaderRow("Recovery", day.recovery, Metrics.recovery, days, day, onToggleRecoveryExpand)
            ScoreSource("Recovery", day.recoveryParts, lastSyncAt)
            AnimatedVisibility(
                visible = recoveryExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = HealthColors.BorderHairline)
                    listOf(Metrics.hrv to "ms", Metrics.rhr to "bpm").forEach { (def, unit) ->
                        val base = MetricInsights.baseline(def, days, day.date)
                        val upTo = days.filter { !it.date.isAfter(day.date) }
                        SparklineVitalRow(
                            title = "${def.label} · ${def.extract(day)?.roundToInt() ?: "—"}",
                            unit = unit,
                            contextText = MetricInsights.extremeLabel(def, days, day.date)
                                ?: MetricInsights.chip(def, days, day.date).first,
                            values = upTo.takeLast(7).mapNotNull(def.extract).map { it.toFloat() },
                            normalMin = base?.low?.toFloat(),
                            normalMax = base?.high?.toFloat(),
                            lineColor = HealthColors.TextSecondary,
                            onClick = { onOpenMetricDetail(def.id) }
                        )
                    }
                }
            }
        }
    }

    // 7. Activity: steps · distance, ring = progress toward your normal (plan 2.4)
    val stepsBase = MetricInsights.baseline(Metrics.steps, days, day.date)
    HealthCard(onClick = { onOpenMetricDetail("steps") }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val distance = day.distanceKm?.let {
                    if (useMetricUnits) String.format(Locale.US, " · %.1f km", it)
                    else String.format(Locale.US, " · %.1f mi", it * 0.621371)
                } ?: ""
                val kcal = day.caloriesKcal?.let { String.format(Locale.US, " · %.0f kcal", it) + if (day.caloriesEstimated) " est." else "" } ?: ""
                Text(
                    text = day.steps?.let { "${String.format(Locale.US, "%,d", it)} steps$distance$kcal" } ?: "No steps yet today",
                    color = HealthColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (day.steps != null) InsightChip(Metrics.steps, days, day)
            }
            if (day.steps != null && stepsBase != null) {
                val pct = (day.steps * 100.0 / stepsBase.mean).roundToInt()
                ScoreRingGauge(score = pct.coerceAtMost(100), size = 56.dp, showRadialGlow = false, text = "$pct%")
            }
        }
    }

    // 8. Recovery summary, last and full width (plan 3.2; tapping opens the reasons sheet)
    HealthCard(onClick = onOpenVerdictSheet) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = verdictFor(day.recovery),
                color = HealthColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text("›", color = HealthColors.TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Title, big number (or "—"), status chip and ring; tapping toggles the expanded detail. */
@Composable
private fun ScoreHeaderRow(
    title: String,
    score: Int?,
    def: MetricDef,
    days: List<DayMetrics>,
    day: DayMetrics,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable { onToggle() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (score != null) InsightChip(def, days, day)
        }
        ScoreRingGauge(score = score, size = 72.dp, showRadialGlow = false)
    }
}
