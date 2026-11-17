package com.jcwear.coach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcwear.coach.domain.DayMetrics
import com.jcwear.coach.domain.MetricInsights
import com.jcwear.coach.domain.Metrics
import com.jcwear.coach.domain.SleepAndReadinessEngine
import com.jcwear.coach.domain.Smoothing
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// ============================================================================
// SLEEP TRENDS (plan 2.3): last 30 nights; tap a night for its Sleep Detail
// ============================================================================
@Composable
internal fun SleepTrendsView(
    days: List<DayMetrics>,
    onOpenNight: (Int) -> Unit,
    onBack: () -> Unit
) {
    val firstIdx = (days.size - 30).coerceAtLeast(0)
    val slice = days.subList(firstIdx, days.size)
    val openNight: (Int) -> Unit = { i -> if (slice[i].sleep != null) onOpenNight(firstIdx + i) }

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
            Text("←", color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.SemiBold)
        }
        Text("Sleep Trends", color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
        Spacer(Modifier.size(48.dp))
    }

    if (slice.none { it.sleep != null }) {
        HealthCard {
            Text("No nights recorded in the last 30 days.", color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
        }
        return
    }

    val durations = slice.map { it.sleepMin?.toDouble() }
    val durBand = MetricInsights.baseline(Metrics.sleep, days, days.last().date)?.band(Metrics.sleep)
    val avgDur = durations.filterNotNull().average()
    ChartCard("Duration", "avg ${Metrics.sleep.format(avgDur, LocalUnitSystem.current)} · tap a night") {
        NightBarsChart(durations, durBand, HealthColors.TextSecondary, openNight)
    }

    ChartCard("Bedtime → wake", "late nights in red") {
        BedtimeRangeChart(slice, openNight)
    }

    val scores = slice.map { it.sleepScore?.toDouble() }
    val scoreBand = MetricInsights.baseline(Metrics.sleepScore, days, days.last().date)?.band(Metrics.sleepScore)
    ChartCard("Sleep score", "avg ${Metrics.sleepScore.format(scores.filterNotNull().average(), LocalUnitSystem.current)}") {
        NightBarsChart(scores, scoreBand, HealthColors.ScoreGreen, openNight)
    }

    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Stage mix by week", color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
            val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
            (3 downTo 0).forEach { w ->
                val end = days.lastIndex - w * 7
                if (end < 0) return@forEach
                val nights = days.subList((end - 6).coerceAtLeast(0), end + 1).mapNotNull { it.sleep }
                val deep = nights.sumOf { it.deepMin }
                val rem = nights.sumOf { it.remMin }
                val light = nights.sumOf { it.lightMin }
                val total = deep + rem + light
                if (total == 0) return@forEach
                val parts = listOf(deep to HealthColors.StageDeep, rem to HealthColors.StageRem, light to HealthColors.StageLight)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Week to ${days[end].date.format(fmt)} · Deep ${deep * 100 / total}% · REM ${rem * 100 / total}% · Light ${light * 100 / total}%",
                        color = HealthColors.TextSecondary,
                        fontSize = HealthType.Small
                    )
                    Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
                        var x = 0f
                        parts.forEach { (min, color) ->
                            val w2 = min.toFloat() / total * size.width
                            drawRect(color, Offset(x, 0f), Size(w2, size.height))
                            x += w2
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
                Text(subtitle, color = HealthColors.TextSecondary, fontSize = HealthType.Small)
            }
            content()
        }
    }
}

/** One bar per night (gaps stay empty), the 3-day smoothed line over real nights, and the normal band. */
@Composable
private fun NightBarsChart(values: List<Double?>, band: Pair<Double, Double>?, color: Color, onTap: (Int) -> Unit) {
    val present = values.filterNotNull()
    if (present.isEmpty()) {
        Text("No data in this range.", color = HealthColors.TextSecondary, fontSize = HealthType.Small)
        return
    }
    val segments = remember(values) { Smoothing.rolling(values, 3) }
    val maxV = (present + listOfNotNull(band?.second)).max() * 1.1
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .pointerInput(values.size) {
                detectTapGestures { o -> onTap((o.x / size.width * values.size).toInt().coerceIn(0, values.lastIndex)) }
            }
    ) {
        val slot = size.width / values.size
        fun y(v: Double) = (size.height - v / maxV * size.height).toFloat()
        band?.let { (lo, hi) -> drawRect(HealthColors.Track, Offset(0f, y(hi)), Size(size.width, y(lo) - y(hi))) }
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            drawRoundRect(
                color = color.copy(alpha = 0.55f),
                topLeft = Offset(i * slot + slot * 0.2f, y(v)),
                size = Size(slot * 0.6f, size.height - y(v)),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
        }
        val path = Path()
        segments.forEach { seg ->
            seg.forEachIndexed { k, (i, v) ->
                val x = i * slot + slot / 2
                if (k == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
            }
        }
        drawPath(path, HealthColors.TextPrimary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A vertical bar per night from sleep onset (top) to wake (bottom); onsets an hour past your median are red. */
@Composable
private fun BedtimeRangeChart(slice: List<DayMetrics>, onTap: (Int) -> Unit) {
    // Minutes from noon keep a night continuous across midnight.
    val ranges = slice.map { d ->
        d.sleep?.let { s ->
            val start = SleepAndReadinessEngine.minutesFromNoon(s.startTime.toLocalTime())
            start to start + Duration.between(s.startTime, s.endTime).toMinutes().toInt()
        }
    }
    val real = ranges.filterNotNull()
    val medianStart = real.map { it.first }.sorted()[real.size / 2]
    val lo = (real.minOf { it.first } - 30).floorDiv(60) * 60
    val hi = (real.maxOf { it.second } + 90).floorDiv(60) * 60
    val labelColor = HealthColors.TextSecondary.toArgb()
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .pointerInput(slice.size) {
                detectTapGestures { o ->
                    val left = 44.dp.toPx()
                    onTap(((o.x - left) / (size.width - left) * slice.size).toInt().coerceIn(0, slice.lastIndex))
                }
            }
    ) {
        val left = 44.dp.toPx()
        val slot = (size.width - left) / slice.size
        fun y(m: Int) = (m - lo).toFloat() / (hi - lo) * size.height
        val paint = android.graphics.Paint().apply { color = labelColor; textSize = 11.sp.toPx(); isAntiAlias = true }
        var m = lo
        while (m <= hi) {
            drawLine(HealthColors.Track, Offset(left, y(m)), Offset(size.width, y(m)), 1.dp.toPx())
            val label = LocalTime.NOON.plusMinutes(m.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
            drawContext.canvas.nativeCanvas.drawText(label, 0f, y(m) + 4.dp.toPx(), paint)
            m += 120
        }
        ranges.forEachIndexed { i, r ->
            if (r == null) return@forEachIndexed
            drawRoundRect(
                color = if (r.first > medianStart + 60) HealthColors.ScoreCoral else HealthColors.TextSecondary,
                topLeft = Offset(left + i * slot + slot * 0.2f, y(r.first)),
                size = Size(slot * 0.6f, y(r.second) - y(r.first)),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
        }
    }
}
