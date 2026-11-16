package com.jcwear.coach.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcwear.coach.domain.DayMetrics
import com.jcwear.coach.domain.MetricInsights
import com.jcwear.coach.domain.Smoothing
import com.jcwear.coach.domain.Metrics
import com.jcwear.coach.domain.hrvCheckHeader
import com.jcwear.coach.domain.Status
import kotlin.math.roundToInt

// ============================================================================
// DETAIL & HISTORY VIEW (Week / Month / 3M / Year + 3-day average + outliers)
// ============================================================================
@Composable
internal fun MetricDetailView(
    metricId: String,
    days: List<DayMetrics>,
    selectedRange: String,
    selectedDayIndex: Int,
    onSelectRange: (String) -> Unit,
    onSelectDayIndex: (Int) -> Unit,
    onBack: () -> Unit
) {
    val def = Metrics.byId(metricId)
    val today = days.last()
    val rangeCount = when (selectedRange) {
        "Week" -> 7
        "Month" -> 30
        "3M" -> 90
        else -> 365
    }.coerceAtMost(days.size)
    val firstIdx = days.size - rangeCount
    val slice = days.subList(firstIdx, days.size)
    val selIdx = selectedDayIndex.coerceIn(firstIdx, days.lastIndex)
    val selDay = days[selIdx]
    val selVal = def.extract(selDay)
    val todayBase = MetricInsights.baseline(def, days, today.date)

    // Per-day status for the visible range (outlier dots use each day's own baseline).
    val rawVals = remember(days, metricId, rangeCount) { slice.map(def.extract) }
    val outlier = remember(days, metricId, rangeCount) {
        slice.map { d ->
            val v = def.extract(d) ?: return@map false
            val b = MetricInsights.baseline(def, days, d.date) ?: return@map false
            MetricInsights.status(def, v, b) != Status.NORMAL
        }
    }
    val segments = remember(rawVals, selectedRange) { Smoothing.forRange(rawVals, selectedRange) }

    // Back header
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
        Text(def.label, color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(48.dp))
    }

    // Hero value
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Today", color = HealthColors.TextSecondary, fontSize = 13.sp)
                todayBase?.band(def)?.let { (lo, hi) ->
                    StatusChip("Normal ${def.format(lo)} – ${def.format(hi)}", Status.NORMAL)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = def.extract(today)?.let { def.format(it) } ?: "—",
                    color = HealthColors.TextPrimary,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold
                )
                if (def.extract(today) != null) InsightChip(def, days, today)
            }
        }
    }

    // Segmented toggle: Week / Month / 3M / Year
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

    // 3-day average line + daily dots + outliers
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selDay.label, color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = selVal?.let { def.format(it) } ?: "No data",
                        color = HealthColors.AccentAmber,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (selVal != null) {
                    val (chipText, st) = MetricInsights.chip(def, days, selDay.date)
                    StatusChip(MetricInsights.extremeLabel(def, days, selDay.date) ?: chipText, st)
                }
            }
            if (def.id == Metrics.hrv.id) {
                hrvCheckHeader(selDay)?.let { Text(it, color = HealthColors.TextSecondary, fontSize = 13.sp) }
            }

            val present = rawVals.filterNotNull()
            if (present.isEmpty()) {
                Text("No data in this range.", color = HealthColors.TextSecondary, fontSize = 13.sp)
            } else {
                val band = todayBase?.band(def)
                val pad = if (def == Metrics.steps) 800.0 else (present.max() - present.min()).coerceAtLeast(1.0) * 0.1
                val minV = (present + listOfNotNull(band?.first)).min() - pad
                val maxV = (present + listOfNotNull(band?.second)).max() + pad
                val span = (maxV - minV).coerceAtLeast(0.5)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(172.dp)
                        .pointerInput(firstIdx, rangeCount) {
                            detectTapGestures { offset ->
                                val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                onSelectDayIndex(firstIdx + (frac * (slice.size - 1)).roundToInt().coerceIn(0, slice.lastIndex))
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val padY = 14.dp.toPx()
                        val innerH = h - padY * 2
                        fun xFor(i: Int): Float = if (slice.size <= 1) w / 2f else i.toFloat() / (slice.size - 1) * w
                        fun yFor(v: Double): Float = (padY + innerH - (v - minV) / span * innerH).toFloat()
                        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

                        band?.let { (lo, hi) ->
                            val top = yFor(hi)
                            val bandH = (yFor(lo) - top).coerceAtLeast(8.dp.toPx())
                            drawRoundRect(
                                color = Color(0x14FFFFFF),
                                topLeft = Offset(0f, top),
                                size = Size(w, bandH),
                                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                            )
                        }

                        // Smoothed line over real days only; gaps start a new segment
                        val path = Path()
                        segments.forEach { seg ->
                            seg.forEachIndexed { k, (i, v) -> if (k == 0) path.moveTo(xFor(i), yFor(v)) else path.lineTo(xFor(i), yFor(v)) }
                        }
                        drawPath(
                            path = path,
                            color = HealthColors.TextSecondary,
                            style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )

                        slice.forEachIndexed { i, _ ->
                            val v = rawVals[i]
                            val cx = xFor(i)
                            val isSel = firstIdx + i == selIdx
                            if (isSel) {
                                drawLine(
                                    color = HealthColors.AccentAmber.copy(alpha = 0.45f),
                                    start = Offset(cx, padY),
                                    end = Offset(cx, h - padY),
                                    strokeWidth = 1.dp.toPx(),
                                    pathEffect = dash
                                )
                            }
                            if (v == null) return@forEachIndexed
                            val cy = yFor(v)
                            if (outlier[i]) {
                                drawCircle(HealthColors.ScoreCoral.copy(alpha = 0.25f), 7.dp.toPx(), Offset(cx, cy))
                            }
                            drawCircle(
                                color = when {
                                    isSel -> HealthColors.AccentAmber
                                    outlier[i] -> HealthColors.ScoreCoral
                                    else -> HealthColors.TextPrimary.copy(alpha = 0.32f)
                                },
                                radius = when {
                                    isSel -> 5.5.dp.toPx()
                                    outlier[i] -> 4.5.dp.toPx()
                                    else -> 3.dp.toPx()
                                },
                                center = Offset(cx, cy)
                            )
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Smoothing.label(selectedRange)?.let { "━ $it" } ?: "● Daily values", color = HealthColors.TextSecondary, fontSize = 13.sp)
                Text("● Outside your normal", color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
        }
    }

    // 30-day average
    val last30 = days.takeLast(30).mapNotNull(def.extract)
    HealthCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("30-day average", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("${last30.size} days with data", color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
            Text(
                last30.takeIf { it.isNotEmpty() }?.let { def.format(it.average()) } ?: "—",
                color = HealthColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
