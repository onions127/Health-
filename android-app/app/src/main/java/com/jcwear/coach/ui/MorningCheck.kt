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
import com.jcwear.coach.domain.Metrics
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
// MORNING CHECK FLOW (Intro -> Measuring -> Result)
// ============================================================================
@Composable
internal fun MorningCheckFlowView(
    step: CheckFlowStep,
    secondsRemaining: Int,
    liveHrBpm: Int?,
    /** Null after a check means it failed; nothing was stored. */
    resultHrv: Int?,
    today: DayMetrics,
    days: List<DayMetrics>,
    onBeginMeasuring: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit
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
                .clickable { onCancel() },
            contentAlignment = Alignment.Center
        ) {
            Text("✕", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = when (step) {
                CheckFlowStep.INTRO -> "Morning Check"
                CheckFlowStep.MEASURING -> "Measuring HRV"
                else -> "Check Complete"
            },
            color = HealthColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.size(48.dp))
    }

    when (step) {
        CheckFlowStep.INTRO -> {
            HealthCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Lie still, 60 seconds", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Rest your arm flat and breathe normally.", color = HealthColors.TextSecondary, fontSize = 13.sp)
                    AmberActionButton(
                        text = "Begin 60-sec check",
                        onClick = onBeginMeasuring,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        CheckFlowStep.MEASURING -> {
            HealthCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(168.dp)) {
                        val progress = ((60 - secondsRemaining) / 60f).coerceIn(0.05f, 1f)
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val stroke = 9.dp.toPx()
                            drawArc(
                                color = Color(0x18FFFFFF),
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                style = Stroke(width = stroke)
                            )
                            drawArc(
                                color = HealthColors.AccentAmber,
                                startAngle = -90f,
                                sweepAngle = 360f * progress,
                                useCenter = false,
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$secondsRemaining", color = HealthColors.TextPrimary, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                            Text("Remaining (s)", color = HealthColors.TextSecondary, fontSize = 13.sp)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x0DFFFFFF))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Live HR · ${liveHrBpm ?: "—"} bpm",
                            color = HealthColors.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                }
            }
        }

        else -> {
            if (resultHrv == null) {
                HealthCard {
                    Text("Check failed, try again", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Keep the band snug and connected, and lie still.", color = HealthColors.TextSecondary, fontSize = 13.sp)
                }
                AmberActionButton(text = "Done", onClick = onDone, modifier = Modifier.fillMaxWidth())
                return
            }
            HealthCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScoreRingGauge(score = today.recovery, size = 104.dp, showRadialGlow = false)
                    Text("Updated Recovery Score", color = HealthColors.TextSecondary, fontSize = 13.sp)
                    if (today.recovery != null) InsightChip(Metrics.recovery, days, today)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(Triple(Metrics.hrv, resultHrv, "ms"), Triple(Metrics.rhr, today.rhrBpm?.roundToInt(), "bpm")).forEach { (def, v, unit) ->
                    HealthCard(modifier = Modifier.weight(1f)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(def.label, color = HealthColors.TextSecondary, fontSize = 13.sp)
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(v?.toString() ?: "—", color = HealthColors.TextPrimary, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                                Text(unit, color = HealthColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                            }
                            if (v != null) InsightChip(def, days, today)
                        }
                    }
                }
            }
            AmberActionButton(
                text = "Done",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
