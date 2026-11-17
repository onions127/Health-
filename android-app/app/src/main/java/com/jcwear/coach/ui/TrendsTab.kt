package com.jcwear.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcwear.coach.domain.DayMetrics
import com.jcwear.coach.domain.MetricInsights
import com.jcwear.coach.domain.skinTempLine
import com.jcwear.coach.domain.Metrics

// ============================================================================
// TRENDS TAB: 30-day average per metric, today's chip vs your normal
// ============================================================================
@Composable
internal fun TrendsTabView(
    days: List<DayMetrics>,
    onOpenCalendar: () -> Unit,
    onOpenMetricDetail: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Trends", color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
            Text("30-day average · today vs your normal", color = HealthColors.TextSecondary, fontSize = HealthType.Small)
        }
        SecondaryActionButton(text = "Calendar", onClick = onOpenCalendar)
    }

    if (days.count { it.hasData } < 3) {
        HealthCard {
            Text("Trends start after 3 nights of data.", color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
        }
        return
    }

    val today = days.last()
    val slice30 = days.takeLast(30)
    val units = LocalUnitSystem.current
    listOf(Metrics.hrv, Metrics.rhr, Metrics.sleep, Metrics.readiness, Metrics.steps, Metrics.strain, Metrics.spo2, Metrics.skinTemp)
        .forEach { def ->
            val values = slice30.mapNotNull(def.extract)
            val base = MetricInsights.baseline(def, days, today.date)
            val todayVal = def.extract(today)
            // Plan 2.1: color means one thing, today's status (green good / red bad / grey normal)
            val todayColor = todayVal?.let { statusColor(MetricInsights.chip(def, days, today.date, units).second) } ?: HealthColors.TextSecondary
            HealthCard(onClick = { onOpenMetricDetail(if (def == Metrics.sleep) "sleepTrends" else def.id) }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(def.label, color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
                        val todayText = "Today ${def.valueText(today, units) ?: "—"}"
                        if (def == Metrics.skinTemp) {
                            // R3: the temperature leads; the difference is the second line.
                            Text(todayText, color = todayColor, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
                            Text(skinTempLine(today, units), color = HealthColors.TextSecondary, fontSize = HealthType.Small)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(todayText, color = todayColor, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
                                Text(
                                    values.takeIf { it.isNotEmpty() }?.let { "avg ${def.format(it.average(), units)}" } ?: "No data",
                                    color = HealthColors.TextSecondary,
                                    fontSize = HealthType.Small
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniSparklineCanvas(
                            values = values.map { it.toFloat() },
                            normalMin = base?.band(def)?.first?.toFloat(),
                            normalMax = base?.band(def)?.second?.toFloat(),
                            color = HealthColors.TextSecondary,
                            lastDotColor = if (todayVal != null) todayColor else HealthColors.TextSecondary
                        )
                        Text("›", color = HealthColors.TextSecondary, fontSize = HealthType.Body, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    WeightTrendCard(days) { onOpenMetricDetail("weight") }
}
