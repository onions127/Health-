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
import com.jcwear.coach.domain.dayMonth
import com.jcwear.coach.domain.weightChangeText
import com.jcwear.coach.domain.weightLine
import com.jcwear.coach.domain.partsFor
import com.jcwear.coach.domain.readinessMissingLine
import com.jcwear.coach.domain.skinTempLine
import com.jcwear.coach.domain.Units
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
    val units = LocalUnitSystem.current
    val isTemp = def == Metrics.skinTemp // R3: the temperature leads, the difference is the second line
    val weightLogger = LocalWeightLogger.current
    // A sparse metric (weight) leads with its latest entry, not today's.
    val heroDay = if (def.sparse) days.lastOrNull { def.extract(it) != null } ?: today else today
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
    val segments = remember(rawVals, selectedRange) {
        if (def.sparse) listOf(weightLine(days).filter { it.first >= firstIdx }.map { (i, v) -> i - firstIdx to v })
        else Smoothing.forRange(rawVals, selectedRange)
    }

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
            Text("←", color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.SemiBold)
        }
        Text(def.label, color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
        if (def.sparse) LogWeightButton { weightLogger?.open() } else Spacer(Modifier.size(48.dp))
    }

    // Hero value
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        isTemp -> "Wrist skin temperature · today"
                        def.sparse -> if (def.extract(heroDay) != null) "Latest · ${dayMonth(heroDay.date)}" else "No entry yet"
                        else -> "Today"
                    },
                    color = HealthColors.TextSecondary, fontSize = HealthType.Small)
                if (!isTemp) todayBase?.band(def)?.let { (lo, hi) ->
                    StatusChip("Normal ${def.format(lo, units)} – ${def.format(hi, units)}", Status.NORMAL)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = def.valueText(heroDay, units) ?: "—",
                    color = HealthColors.TextPrimary,
                    fontSize = HealthType.Display,
                    fontWeight = FontWeight.SemiBold
                )
                if (!isTemp && def.showBaseline && def.extract(today) != null) InsightChip(def, days, today)
            }
            if (def.sparse) weightChangeText(days, units)?.let { Text(it, color = HealthColors.TextSecondary, fontSize = HealthType.Small) }
            if (isTemp) {
                Text(skinTempLine(today, units), color = HealthColors.TextSecondary, fontSize = HealthType.Small)
                Text("Not core body temperature", color = HealthColors.TextSecondary, fontSize = HealthType.Small)
            }
        }
    }

    // Segmented toggle: Week / Month / 3M / Year
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = HealthColors.BgCard,
        shape = HealthShapes.Panel
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
                        .clip(HealthShapes.Panel)
                        .background(if (active) HealthColors.AccentAmber else Color.Transparent)
                        .clickable { onSelectRange(r) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = r,
                        color = if (active) HealthColors.TextInverse else HealthColors.TextSecondary,
                        fontSize = HealthType.Small,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
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
                    Text(selDay.label, color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
                    Text(
                        text = def.valueText(selDay, units) ?: if (isTemp) "No reading" else "No data",
                        color = HealthColors.AccentAmber,
                        fontSize = HealthType.Body,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (def.sparse) WeightDayActions(selDay)
                if (selVal != null && !isTemp && def.showBaseline) {
                    val (chipText, st) = MetricInsights.chip(def, days, selDay.date, units)
                    StatusChip(MetricInsights.extremeLabel(def, days, selDay.date) ?: chipText, st)
                }
            }
            if (isTemp) {
                if (selDay.skinTempC != null) Text(skinTempLine(selDay, units), color = HealthColors.TextSecondary, fontSize = HealthType.Small)
                Text("Difference from your average", color = HealthColors.TextSecondary, fontSize = HealthType.Small)
            }
            if (def.id == Metrics.hrv.id) {
                hrvCheckHeader(selDay)?.let { Text(it, color = HealthColors.TextSecondary, fontSize = HealthType.Small) }
            }

            val present = rawVals.filterNotNull()
            if (present.isEmpty()) {
                Text("No data in this range.", color = HealthColors.TextSecondary, fontSize = HealthType.Small)
            } else {
                val band = todayBase?.band(def)
                val pad = when {
                    def == Metrics.steps -> 800.0
                    def.sparse -> 2.0 // weight: never from zero, at least 2 kg either side of the data
                    else -> (present.max() - present.min()).coerceAtLeast(1.0) * 0.1
                }
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
                                color = HealthColors.Track,
                                topLeft = Offset(0f, top),
                                size = Size(w, bandH),
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
                Text(
                    if (def.sparse) "━ 7-day average · ● Entries" else Smoothing.label(selectedRange)?.let { "━ $it" } ?: "● Daily values",
                    color = HealthColors.TextSecondary, fontSize = HealthType.Small
                )
                if (def.showBaseline) Text("● Outside your normal", color = HealthColors.TextSecondary, fontSize = HealthType.Small)
            }
        }
    }

    // R4: how the selected day's score is calculated
    val selParts = partsFor(metricId, selDay)
    val missing = if (def == Metrics.readiness) readinessMissingLine(selDay, days) else null
    if (selParts != null || missing != null) {
        HealthCard {
            Column {
                ScoreSource(def.label, selParts, 0L, sheetTitle = "${def.label} · ${selDay.label}")
                missing?.let { Text(it, color = HealthColors.TextSecondary, fontSize = HealthType.Small) }
            }
        }
    }

    // 30-day average
    val last30 = days.takeLast(30).mapNotNull(def.extract)
    val (avgTitle, avgSub, avgValue) = if (isTemp) Triple(
        "Your average",
        if (selDay.skinTempBaseC != null) "from ${selDay.skinTempBaseDays} days before ${selDay.label}" else skinTempLine(selDay.copy(skinTempC = selDay.skinTempC ?: 0.0, skinTempBaseC = null), units),
        selDay.skinTempBaseC?.let { Units.temp(it, units) } ?: "—"
    ) else Triple(
        "30-day average", "${last30.size} days with data",
        last30.takeIf { it.isNotEmpty() }?.let { def.format(it.average(), units) } ?: "—"
    )
    if (def.showBaseline) HealthCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(avgTitle, color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
                Text(avgSub, color = HealthColors.TextSecondary, fontSize = HealthType.Small)
            }
            Text(
                avgValue,
                color = HealthColors.TextPrimary,
                fontSize = HealthType.Body,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
