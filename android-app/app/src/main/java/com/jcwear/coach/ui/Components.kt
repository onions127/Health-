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
// REUSABLE PRIMITIVES & CHARTS
// ============================================================================
@Composable
internal fun HealthCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        color = HealthColors.BgCard,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, HealthColors.BorderHairline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
internal fun AmberActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(HealthColors.AccentAmber)
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = HealthColors.TextInverse,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
internal fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x12FFFFFF))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = HealthColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
internal fun ContextChip(
    text: String,
    score: Int
) {
    val (bg, fg) = when {
        score >= 70 -> Color(0x2434D399) to Color(0xFF6EE7B7)
        score >= 40 -> Color(0x26FBBF24) to Color(0xFFFDE68A)
        else -> Color(0x29FB7185) to Color(0xFFFDA4AF)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
internal fun ScoreRingGauge(
    score: Int,
    size: Dp,
    showRadialGlow: Boolean
) {
    val ringColor = HealthColors.forScore(score)
    Box(
        modifier = Modifier.size(if (showRadialGlow) size + 16.dp else size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            if (showRadialGlow) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(ringColor.copy(alpha = 0.25f), Color.Transparent)
                    ),
                    radius = this.size.minDimension * 0.72f
                )
            }
            val stroke = if (size >= 70.dp) 7.dp.toPx() else 6.dp.toPx()
            val pad = stroke / 2f + 2.dp.toPx()
            val arcSize = Size(this.size.width - pad * 2, this.size.height - pad * 2)
            drawArc(
                color = Color(0x14FFFFFF),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = arcSize,
                style = Stroke(width = stroke)
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = (score.coerceIn(0, 100) / 100f) * 360f,
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
internal fun SubMetricBarRow(
    title: String,
    contextText: String,
    score: Int,
    normalMin: Int,
    normalMax: Int,
    barColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(contextText, color = HealthColors.TextSecondary, fontSize = 13.sp)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Canvas(modifier = Modifier.size(width = 104.dp, height = 10.dp)) {
                val w = size.width
                val h = size.height
                drawRoundRect(
                    color = Color(0x14FFFFFF),
                    topLeft = Offset(0f, h * 0.2f),
                    size = Size(w, h * 0.6f),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
                val nLeft = (normalMin / 100f) * w
                val nWidth = ((normalMax - normalMin) / 100f) * w
                drawRoundRect(
                    color = Color(0x29FFFFFF),
                    topLeft = Offset(nLeft, 0f),
                    size = Size(nWidth, h),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                )
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(0f, h * 0.2f),
                    size = Size((score / 100f).coerceIn(0.08f, 1f) * w, h * 0.6f),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }
            Text("$score", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("›", color = HealthColors.TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun SparklineVitalRow(
    title: String,
    unit: String,
    contextText: String,
    values: List<Float>,
    normalMin: Float,
    normalMax: Float,
    lineColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(unit, color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
            Text(contextText, color = HealthColors.TextSecondary, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniSparklineCanvas(values = values, normalMin = normalMin, normalMax = normalMax, color = lineColor)
            Text("›", color = HealthColors.TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun MiniSparklineCanvas(
    values: List<Float>,
    normalMin: Float,
    normalMax: Float,
    color: Color
) {
    Canvas(modifier = Modifier.size(width = 92.dp, height = 30.dp)) {
        if (values.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val minV = (values + normalMin).minOrNull()!! - 2f
        val maxV = (values + normalMax).maxOrNull()!! + 2f
        val span = (maxV - minV).coerceAtLeast(1f)

        fun xFor(i: Int) = if (values.size <= 1) w / 2f else (i.toFloat() / (values.size - 1).toFloat()) * (w - 8f) + 4f
        fun yFor(v: Float) = h - 4f - ((v - minV) / span) * (h - 8f)

        val topY = yFor(normalMax)
        val botY = yFor(normalMin)
        drawRoundRect(
            color = Color(0x12FFFFFF),
            topLeft = Offset(0f, topY),
            size = Size(w, (botY - topY).coerceAtLeast(4.dp.toPx())),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        )
        val path = Path()
        values.forEachIndexed { idx, v ->
            val px = xFor(idx)
            val py = yFor(v)
            if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawCircle(
            color = color,
            radius = 3.dp.toPx(),
            center = Offset(xFor(values.lastIndex), yFor(values.last()))
        )
    }
}

@Composable
internal fun StepsNormalBandBar(
    steps: Int,
    normalMin: Int,
    normalMax: Int
) {
    val maxScale = 13000f
    val barColor = if (steps >= normalMin) HealthColors.ScoreGreen else HealthColors.ScoreAmber
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
    ) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = Color(0x14FFFFFF),
            topLeft = Offset(0f, h * 0.25f),
            size = Size(w, h * 0.5f),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
        val nLeft = (normalMin / maxScale) * w
        val nWidth = ((normalMax - normalMin) / maxScale) * w
        drawRoundRect(
            color = Color(0x26FFFFFF),
            topLeft = Offset(nLeft, 0f),
            size = Size(nWidth, h),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
        val fillW = ((steps / maxScale).coerceIn(0.05f, 1f)) * w
        drawRoundRect(
            color = barColor,
            topLeft = Offset(0f, h * 0.25f),
            size = Size(fillW, h * 0.5f),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
        drawCircle(
            color = HealthColors.TextPrimary,
            radius = 5.dp.toPx(),
            center = Offset(fillW, h / 2f)
        )
    }
}

@Composable
internal fun BottomNavBar(
    activeTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
        color = Color(0xF50B1020),
        border = BorderStroke(1.dp, HealthColors.BorderHairline)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val items = listOf(
                Triple(MainTab.TODAY, "Today", "☀"),
                Triple(MainTab.TRENDS, "Trends", "∿"),
                Triple(MainTab.COACH, "Coach", "💬"),
                Triple(MainTab.SETTINGS, "Settings", "⚙")
            )
            items.forEach { (tab, label, _) ->
                val active = activeTab == tab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelectTab(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 24.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (active) HealthColors.AccentAmber else Color.Transparent)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = label,
                        color = if (active) HealthColors.AccentAmber else HealthColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
internal fun BottomSheetOverlay(
    sheet: ActiveSheet,
    connectionState: String,
    batteryPct: Int,
    dayPoint: MetricSeriesPoint,
    series90: List<MetricSeriesPoint>,
    homelabUrl: String,
    onUpdateHomelabUrl: (String) -> Unit,
    onTriggerSync: () -> Unit,
    onSelectHistoryDay: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB8060914))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {},
            color = HealthColors.BgCard,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            border = BorderStroke(1.dp, Color(0x1FFFFFFF))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (sheet) {
                    ActiveSheet.SYNC -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Band Connection", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "✕",
                                color = HealthColors.TextSecondary,
                                fontSize = 16.sp,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable { onDismiss() }
                                    .wrapContentSize(Alignment.Center)
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(HealthColors.BgDeep)
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Last synced 6 min ago", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("Battery $batteryPct% · $connectionState", color = HealthColors.TextSecondary, fontSize = 13.sp)
                        }
                        AmberActionButton(
                            text = "Sync band",
                            onClick = onTriggerSync,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    ActiveSheet.VERDICT -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Why this verdict", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "✕",
                                color = HealthColors.TextSecondary,
                                fontSize = 16.sp,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable { onDismiss() }
                                    .wrapContentSize(Alignment.Center)
                            )
                        }
                        val reasons = listOf(
                            "HRV is ${dayPoint.hrvMs} ms" to "↑ 8% vs normal",
                            "Resting HR is ${dayPoint.rhrBpm} bpm" to "↓ 2 vs normal",
                            "You slept ${formatSleepHm(dayPoint.sleepMin)}" to "1h 35m deep"
                        )
                        reasons.forEach { (left, right) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(HealthColors.BgDeep)
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(left, color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                ContextChip(right, score = dayPoint.recoveryScore)
                            }
                        }
                        AmberActionButton(
                            text = "Got it",
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    ActiveSheet.CALENDAR -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Recent 14 Days", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text("Tap any day to open its Today view", color = HealthColors.TextSecondary, fontSize = 13.sp)
                            }
                            Text(
                                text = "✕",
                                color = HealthColors.TextSecondary,
                                fontSize = 16.sp,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable { onDismiss() }
                                    .wrapContentSize(Alignment.Center)
                            )
                        }
                        val last14 = series90.takeLast(14)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (row in 0..1) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    for (col in 0..6) {
                                        val pt = last14[row * 7 + col]
                                        val isOutlierDay = pt.index == 81
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(if (isOutlierDay) Color(0x28FBBF24) else Color(0x0DFFFFFF))
                                                .clickable { onSelectHistoryDay(pt.index) },
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text("${pt.date.dayOfMonth}", color = HealthColors.TextPrimary, fontSize = 13.sp)
                                            Spacer(Modifier.height(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(HealthColors.forScore(pt.dailyScore))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        AmberActionButton(
                            text = "Open ${series90[81].label} (56 · Below normal)",
                            onClick = { onSelectHistoryDay(81) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    ActiveSheet.SERVER_CONFIG -> {
                        var draftUrl by remember { mutableStateOf(homelabUrl) }
                        Text("Home Server URL", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = draftUrl,
                            onValueChange = { draftUrl = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        AmberActionButton(
                            text = "Save server URL",
                            onClick = {
                                onUpdateHomelabUrl(draftUrl.trim())
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    else -> {}
                }
            }
        }
    }
}
