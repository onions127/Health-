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
import com.jcwear.coach.domain.SleepAndReadinessEngine.DailyReadinessReport
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
    dayPoint: MetricSeriesPoint,
    series90: List<MetricSeriesPoint>,
    isHistoricalView: Boolean,
    morningCheckDone: Boolean,
    dailyExpanded: Boolean,
    recoveryExpanded: Boolean,
    spo2AlertActive: Boolean,
    onOpenSyncSheet: () -> Unit,
    onExitHistory: () -> Unit,
    onStartMorningCheck: () -> Unit,
    onOpenVerdictSheet: () -> Unit,
    onToggleDailyExpand: () -> Unit,
    onToggleRecoveryExpand: () -> Unit,
    onOpenMetricDetail: (String) -> Unit
) {
    // 1. Greeting and date + small sync status dot (48dp tap target)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = if (isHistoricalView) dayPoint.label else "Morning, Alex",
                color = HealthColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (isHistoricalView) "Historical snapshot" else dayPoint.label,
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
                AmberActionButton(
                    text = "Back to today",
                    onClick = onExitHistory
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background( Color(0x0AFFFFFF))
                    .clickable { onOpenSyncSheet() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(HealthColors.ScoreGreen)
                )
            }
        }
    }

    // 2. Morning Check prompt card (shown only in morning until done that day)
    if (!morningCheckDone && !isHistoricalView && !spo2AlertActive) {
        HealthCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Morning check",
                    color = HealthColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                AmberActionButton(
                    text = "Start 60-sec check",
                    onClick = onStartMorningCheck
                )
            }
        }
    }

    // 3. Coach verdict card (one line + one action; tapping opens 2-3 reasons sheet)
    HealthCard(onClick = onOpenVerdictSheet) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isHistoricalView) "Low recovery. Keep effort light."
                else "Well recovered. Good for hard training.",
                color = HealthColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "›",
                color = HealthColors.TextSecondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    // 7. Out-of-bounds Alert card (above Daily Score when SpO2 is low)
    if (spo2AlertActive) {
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
                        text = "Blood oxygen low overnight: 91%",
                        color = Color(0xFFFECACA),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "↓ 7% vs normal · Tap for details",
                        color = Color(0xFFFCA5A5),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = "›",
                    color = Color(0xFFFCA5A5),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    // 4. Daily Score card (1 big score with ring + ONLY soft radial glow + trend chip; expands in place)
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clickable { onToggleDailyExpand() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Daily Score",
                        color = HealthColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "${dayPoint.dailyScore}",
                            color = HealthColors.TextPrimary,
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold
                        )
                        ContextChip(
                            text = if (isHistoricalView) "↓ 22 vs normal" else "↑ 6 vs normal",
                            score = dayPoint.dailyScore
                        )
                    }
                }
                ScoreRingGauge(
                    score = dayPoint.dailyScore,
                    size = 72.dp,
                    showRadialGlow = true
                )
            }

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
                    SubMetricBarRow(
                        title = "Readiness",
                        contextText = "↑ 5 vs normal",
                        score = dayPoint.readiness,
                        normalMin = 72,
                        normalMax = 85,
                        barColor = HealthColors.ScoreGreen,
                        onClick = { onOpenMetricDetail("readiness") }
                    )
                    SubMetricBarRow(
                        title = "Sleep",
                        contextText = "↑ 4 vs normal",
                        score = dayPoint.sleepScore,
                        normalMin = 70,
                        normalMax = 85,
                        barColor = HealthColors.ScoreGreen,
                        onClick = { onOpenMetricDetail("sleep") }
                    )
                    SubMetricBarRow(
                        title = "Strain",
                        contextText = "Within normal",
                        score = dayPoint.strainScore100,
                        normalMin = 65,
                        normalMax = 82,
                        barColor = HealthColors.AccentAmber,
                        onClick = { onOpenMetricDetail("strain") }
                    )
                }
            }
        }
    }

    // 5. Recovery Score card (expands to show HRV & Resting HR with 7-day sparklines)
    val last7Hrv = remember(series90) { series90.takeLast(7).map { it.hrvMs.toFloat() } }
    val last7Rhr = remember(series90) { series90.takeLast(7).map { it.rhrBpm.toFloat() } }

    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable { onToggleRecoveryExpand() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Recovery",
                        color = HealthColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "${dayPoint.recoveryScore}",
                            color = HealthColors.TextPrimary,
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold
                        )
                        ContextChip(
                            text = if (isHistoricalView) "Lowest in 2 weeks" else "↑ 8% vs normal",
                            score = dayPoint.recoveryScore
                        )
                    }
                }
                ScoreRingGauge(
                    score = dayPoint.recoveryScore,
                    size = 60.dp,
                    showRadialGlow = false
                )
            }

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
                    SparklineVitalRow(
                        title = "HRV · ${dayPoint.hrvMs}",
                        unit = "ms",
                        contextText = if (isHistoricalView) "Lowest in 2 weeks" else "↑ 8% vs normal",
                        values = last7Hrv,
                        normalMin = 55f,
                        normalMax = 63f,
                        lineColor = HealthColors.ScoreGreen,
                        onClick = { onOpenMetricDetail("hrv") }
                    )
                    SparklineVitalRow(
                        title = "Resting HR · ${dayPoint.rhrBpm}",
                        unit = "bpm",
                        contextText = if (isHistoricalView) "↑ 4 bpm vs normal" else "↓ 2 bpm vs normal",
                        values = last7Rhr,
                        normalMin = 51f,
                        normalMax = 56f,
                        lineColor = HealthColors.ScoreGreen,
                        onClick = { onOpenMetricDetail("rhr") }
                    )
                }
            }
        }
    }

    // 6. Activity card: steps compared with normal range shaded band
    HealthCard(onClick = { onOpenMetricDetail("steps") }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Steps  ${String.format(Locale.US, "%,d", dayPoint.steps)}",
                    color = HealthColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                ContextChip(
                    text = if (isHistoricalView) "Below normal · 7k–10k" else "Within normal · 7k–10k",
                    score = if (isHistoricalView) 55 else 80
                )
            }
            StepsNormalBandBar(
                steps = dayPoint.steps,
                normalMin = 7000,
                normalMax = 10000
            )
        }
    }
}
