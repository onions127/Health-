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
// SLEEP DETAIL VIEW (Hypnogram + "You slept 7h 20m" + Stage Minutes + Score)
// ============================================================================
@Composable
internal fun SleepDetailView(
    dayPoint: MetricSeriesPoint,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Text("←", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Text("Sleep Detail", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(48.dp))
    }

    HealthCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You slept", color = HealthColors.TextSecondary, fontSize = 13.sp)
                Text(
                    text = formatSleepHm(dayPoint.sleepMin),
                    color = HealthColors.TextPrimary,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold
                )
                ContextChip("↑ 20m vs normal", score = 82)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ScoreRingGauge(score = dayPoint.sleepScore, size = 68.dp, showRadialGlow = false)
                Text("${dayPoint.sleepScore} · ↑ 4 vs normal", color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
        }
    }

    // Hypnogram Card
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Sleep Stages", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("23:10 – 06:30", color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier.height(120.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Awake", color = HealthColors.TextSecondary, fontSize = 13.sp)
                    Text("REM", color = HealthColors.TextSecondary, fontSize = 13.sp)
                    Text("Light", color = HealthColors.TextSecondary, fontSize = 13.sp)
                    Text("Deep", color = HealthColors.TextSecondary, fontSize = 13.sp)
                }
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(120.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val yLevels = listOf(h * 0.14f, h * 0.40f, h * 0.66f, h * 0.90f)
                    val colors = listOf(
                        HealthColors.StageAwake,
                        HealthColors.StageRem,
                        HealthColors.StageLight,
                        HealthColors.StageDeep
                    )
                    yLevels.forEach { y ->
                        drawLine(
                            color = HealthColors.BorderHairline,
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    val segments = listOf(
                        Triple(0f, 12f, 0),
                        Triple(12f, 38f, 2),
                        Triple(50f, 50f, 3),
                        Triple(100f, 30f, 2),
                        Triple(130f, 25f, 1),
                        Triple(155f, 40f, 2),
                        Triple(195f, 45f, 3),
                        Triple(240f, 10f, 0),
                        Triple(250f, 45f, 2),
                        Triple(295f, 45f, 1),
                        Triple(340f, 52f, 2),
                        Triple(392f, 40f, 1),
                        Triple(432f, 30f, 2)
                    )
                    val total = 462f
                    segments.forEach { (start, dur, lvl) ->
                        val x = (start / total) * w
                        val bw = ((dur / total) * w).coerceAtLeast(3.dp.toPx())
                        val y = yLevels[lvl]
                        drawRoundRect(
                            color = colors[lvl],
                            topLeft = Offset(x, y - 5.dp.toPx()),
                            size = Size(bw, 10.dp.toPx()),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                        )
                    }
                }
            }
        }
    }

    // Stage Minutes Breakdown
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Time in each stage", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            StageDurationRow("Deep", "1h 35m", 0.25f, "↑ 12m vs normal", HealthColors.StageDeep)
            StageDurationRow("REM", "1h 50m", 0.28f, "Within normal", HealthColors.StageRem)
            StageDurationRow("Light", "3h 55m", 0.52f, "Within normal", HealthColors.StageLight)
            StageDurationRow("Awake", "22m", 0.08f, "↓ 8m vs normal", HealthColors.StageAwake)
        }
    }
}

@Composable
internal fun StageDurationRow(
    stage: String,
    duration: String,
    fraction: Float,
    contextText: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.width(120.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(stage, color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(duration, color = HealthColors.TextSecondary, fontSize = 13.sp)
        }
        Text(contextText, color = HealthColors.TextSecondary, fontSize = 13.sp)
    }
}
