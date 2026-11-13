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
// DETAIL & HISTORY VIEW (Week / Month / 3M / Year + 3-Day Rolling Avg + Outliers)
// ============================================================================
@Composable
internal fun MetricDetailView(
    metricId: String,
    series90: List<MetricSeriesPoint>,
    selectedRange: String,
    selectedDayIndex: Int,
    spo2AlertActive: Boolean,
    onSelectRange: (String) -> Unit,
    onSelectDayIndex: (Int) -> Unit,
    onBack: () -> Unit
) {
    val title = when (metricId) {
        "hrv" -> "Heart Rate Variability"
        "rhr" -> "Resting Heart Rate"
        "readiness" -> "Readiness"
        "strain" -> "Daily Strain"
        "spo2" -> "Blood Oxygen"
        "skinTemp" -> "Skin Temperature"
        "steps" -> "Daily Steps"
        else -> "Heart Rate Variability"
    }
    val unit = when (metricId) {
        "hrv" -> "ms"
        "rhr" -> "bpm"
        "spo2" -> "%"
        "skinTemp" -> "°C"
        "steps" -> "steps"
        else -> ""
    }
    val (normalMin, normalMax, normalLabel) = when (metricId) {
        "hrv" -> Triple(55f, 63f, "55–63 ms normal")
        "rhr" -> Triple(51f, 56f, "51–56 bpm normal")
        "readiness" -> Triple(72f, 85f, "72–85 normal")
        "strain" -> Triple(65f, 82f, "65–82 normal")
        "spo2" -> Triple(95f, 99f, "95–99% normal")
        "skinTemp" -> Triple(-0.3f, 0.3f, "±0.3 °C normal")
        "steps" -> Triple(7000f, 10000f, "7k–10k normal")
        else -> Triple(55f, 63f, "55–63 ms normal")
    }

    fun extractRaw(pt: MetricSeriesPoint): Float = when (metricId) {
        "hrv" -> pt.hrvMs.toFloat()
        "rhr" -> pt.rhrBpm.toFloat()
        "readiness" -> pt.readiness.toFloat()
        "strain" -> pt.strainScore100.toFloat()
        "spo2" -> if (spo2AlertActive && pt.index == 89) 91f else pt.spo2Pct.toFloat()
        "skinTemp" -> pt.skinTempDelta
        "steps" -> pt.steps.toFloat()
        else -> pt.hrvMs.toFloat()
    }

    fun formatValue(v: Float): String = when (metricId) {
        "skinTemp" -> String.format(Locale.US, "%+.1f °C", v)
        "steps" -> String.format(Locale.US, "%,d", v.roundToInt())
        "spo2" -> "${v.roundToInt()}%"
        else -> if (unit.isNotEmpty()) "${v.roundToInt()} $unit" else "${v.roundToInt()}"
    }

    fun isOutlier(v: Float): Boolean = when (metricId) {
        "hrv" -> v < 50f || v > 67f
        "rhr" -> v > 57f || v < 50f
        "spo2" -> v < 95f
        "skinTemp" -> abs(v) > 0.4f
        "steps" -> v < 5500f || v > 12000f
        else -> v < normalMin - 8f || v > normalMax + 8f
    }

    val rangeCount = when (selectedRange) {
        "Week" -> 7
        "Month" -> 30
        else -> 90
    }
    val slice = series90.takeLast(rangeCount)
    val latestPt = series90.last()
    val currentVal = extractRaw(latestPt)
    val selPt = series90.getOrElse(selectedDayIndex) { latestPt }
    val selVal = extractRaw(selPt)
    val selOutlier = isOutlier(selVal)

    // Back Header
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
        Text(
            text = title,
            color = HealthColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.size(48.dp))
    }

    // Hero Value Card
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Current reading", color = HealthColors.TextSecondary, fontSize = 13.sp)
                ContextChip(text = normalLabel, score = 75)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val heroStr = when (metricId) {
                        "skinTemp" -> String.format(Locale.US, "%+.1f", currentVal)
                        "steps" -> String.format(Locale.US, "%,d", currentVal.roundToInt())
                        else -> "${currentVal.roundToInt()}"
                    }
                    Text(
                        text = heroStr,
                        color = HealthColors.TextPrimary,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (unit.isNotEmpty()) {
                        Text(
                            text = unit,
                            color = HealthColors.TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                ContextChip(
                    text = if (spo2AlertActive && metricId == "spo2") "↓ 7% vs normal" else "↑ 8% vs normal",
                    score = if (spo2AlertActive && metricId == "spo2") 35 else 84
                )
            }
        }
    }

    // Segmented Toggle: Week / Month / 3M / Year
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = HealthColors.BgCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, HealthColors.BorderHairline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("Week", "Month", "3M", "Year").forEach { r ->
                val active = selectedRange == r
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) HealthColors.AccentAmber else Color.Transparent)
                        .clickable { onSelectRange(r) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = r,
                        color = if (active) HealthColors.TextInverse else HealthColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }

    // 3-Day Rolling Average + Faint Raw Dots + Outliers Chart Card
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = selPt.label,
                        color = HealthColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = formatValue(selVal),
                        color = HealthColors.AccentAmber,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                ContextChip(
                    text = if (selOutlier) "Lowest in 2 weeks" else "Within normal",
                    score = if (selOutlier) 35 else 82
                )
            }

            val rawVals = slice.map { extractRaw(it) }
            val rollVals = slice.map { it.rolling3d[metricId] ?: extractRaw(it) }
            val minV = (rawVals + normalMin).minOrNull()!! - (if (metricId == "steps") 800f else 4f)
            val maxV = (rawVals + normalMax).maxOrNull()!! + (if (metricId == "steps") 800f else 4f)
            val span = (maxV - minV).coerceAtLeast(0.5f)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(172.dp)
                    .pointerInput(slice) {
                        detectTapGestures { offset ->
                            val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val idxInSlice = (frac * (slice.size - 1)).roundToInt().coerceIn(0, slice.lastIndex)
                            onSelectDayIndex(slice[idxInSlice].index)
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val padY = 14.dp.toPx()
                    val innerH = h - padY * 2

                    fun xFor(i: Int): Float =
                        if (slice.size <= 1) w / 2f else (i.toFloat() / (slice.size - 1).toFloat()) * w

                    fun yFor(v: Float): Float =
                        padY + innerH - ((v - minV) / span) * innerH

                    val bandTop = yFor(normalMax)
                    val bandBottom = yFor(normalMin)
                    val bandH = (bandBottom - bandTop).coerceAtLeast(8.dp.toPx())

                    // Shaded Normal Range Band
                    drawRoundRect(
                        color = HealthColors.ScoreGreen.copy(alpha = 0.12f),
                        topLeft = Offset(0f, bandTop),
                        size = Size(w, bandH),
                        cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                    )
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    drawLine(
                        color = HealthColors.ScoreGreen.copy(alpha = 0.30f),
                        start = Offset(0f, bandTop),
                        end = Offset(w, bandTop),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash
                    )
                    drawLine(
                        color = HealthColors.ScoreGreen.copy(alpha = 0.30f),
                        start = Offset(0f, bandTop + bandH),
                        end = Offset(w, bandTop + bandH),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash
                    )

                    // 3-Day Rolling Average Line
                    val path = Path()
                    rollVals.forEachIndexed { idx, rv ->
                        val px = xFor(idx)
                        val py = yFor(rv)
                        if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    drawPath(
                        path = path,
                        color = HealthColors.ScoreGreen,
                        style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // Faint Daily Raw Dots + Highlighted Outliers
                    slice.forEachIndexed { idx, pt ->
                        val v = rawVals[idx]
                        val cx = xFor(idx)
                        val cy = yFor(v)
                        val out = isOutlier(v)
                        val isSel = pt.index == selectedDayIndex

                        if (isSel) {
                            drawLine(
                                color = HealthColors.AccentAmber.copy(alpha = 0.45f),
                                start = Offset(cx, padY),
                                end = Offset(cx, h - padY),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = dash
                            )
                        }
                        if (out) {
                            drawCircle(
                                color = HealthColors.ScoreCoral.copy(alpha = 0.25f),
                                radius = 7.dp.toPx(),
                                center = Offset(cx, cy)
                            )
                        }
                        drawCircle(
                            color = when {
                                isSel -> HealthColors.AccentAmber
                                out -> HealthColors.ScoreCoral
                                else -> HealthColors.TextPrimary.copy(alpha = 0.32f)
                            },
                            radius = when {
                                isSel -> 5.5.dp.toPx()
                                out -> 4.5.dp.toPx()
                                else -> 3.dp.toPx()
                            },
                            center = Offset(cx, cy)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("━ 3-day average", color = HealthColors.TextSecondary, fontSize = 13.sp)
                Text("● Outlier day (tap dot)", color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
        }
    }

    // 30-day average comparison card
    val avg30 = series90.takeLast(30).map { extractRaw(it) }.average().toFloat()
    HealthCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("30-day average", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("vs 90-day normal", color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(formatValue(avg30), color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                ContextChip("Within normal", score = 80)
            }
        }
    }
}
