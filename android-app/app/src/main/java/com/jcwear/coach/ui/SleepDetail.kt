package com.jcwear.coach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcwear.coach.ble.J2208Protocol
import com.jcwear.coach.domain.DayMetrics
import com.jcwear.coach.domain.Metrics
import com.jcwear.coach.domain.SleepAndReadinessEngine
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ============================================================================
// SLEEP DETAIL VIEW (real times, hypnogram from band stages, stage minutes)
// ============================================================================
@Composable
internal fun SleepDetailView(
    day: DayMetrics,
    days: List<DayMetrics>,
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

    val session = day.sleep
    if (session == null) {
        HealthCard {
            Text("No sleep recorded for this night.", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        return
    }
    val hm = DateTimeFormatter.ofPattern("HH:mm")

    HealthCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You slept", color = HealthColors.TextSecondary, fontSize = 13.sp)
                Text(
                    text = formatSleepHm(session.totalSleepMin),
                    color = HealthColors.TextPrimary,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold
                )
                InsightChip(Metrics.sleep, days, day)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ScoreRingGauge(score = day.sleepScore, size = 68.dp, showRadialGlow = false)
                Text("Score ${day.sleepScore ?: "—"}", color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Fell asleep ${session.startTime.format(hm)} · Woke ${session.endTime.format(hm)}",
            color = HealthColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (session.startTime.hour in 3..11) {
            Text("Asleep after 03:00, so the score is capped at 70.", color = HealthColors.TextSecondary, fontSize = 13.sp)
        }
    }

    // Hypnogram from the band's per-minute stages
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Sleep Stages", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("${session.startTime.format(hm)} – ${session.endTime.format(hm)}", color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.height(120.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text("Awake", color = HealthColors.TextSecondary, fontSize = 13.sp)
                    Text("REM", color = HealthColors.TextSecondary, fontSize = 13.sp)
                    Text("Light", color = HealthColors.TextSecondary, fontSize = 13.sp)
                    Text("Deep", color = HealthColors.TextSecondary, fontSize = 13.sp)
                }
                val runs = remember(session) { stageRuns(session.epochs, session.startTime) }
                val total = Duration.between(session.startTime, session.endTime).toMinutes().toFloat() + 1f
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(120.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val yLevels = listOf(h * 0.14f, h * 0.40f, h * 0.66f, h * 0.90f)
                    val colors = listOf(HealthColors.StageAwake, HealthColors.StageRem, HealthColors.StageLight, HealthColors.StageDeep)
                    yLevels.forEach { y ->
                        drawLine(HealthColors.BorderHairline, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
                    }
                    runs.forEach { (startMin, dur, lvl) ->
                        drawRoundRect(
                            color = colors[lvl],
                            topLeft = Offset(startMin / total * w, yLevels[lvl] - 5.dp.toPx()),
                            size = Size((dur / total * w).coerceAtLeast(2.dp.toPx()), 10.dp.toPx()),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                        )
                    }
                }
            }
        }
    }

    // Stage minutes
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Time in each stage", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val inBed = (session.totalSleepMin + session.awakeMin).coerceAtLeast(1)
            listOf(
                Triple("Deep", session.deepMin, HealthColors.StageDeep),
                Triple("REM", session.remMin, HealthColors.StageRem),
                Triple("Light", session.lightMin, HealthColors.StageLight),
                Triple("Awake", session.awakeMin, HealthColors.StageAwake)
            ).forEach { (name, min, color) ->
                StageDurationRow(name, formatSleepHm(min), "${min * 100 / inBed}% of the night", color)
            }
            if (session.post1200NoonExtendedMin > 0) {
                Text(
                    "Includes ${session.post1200NoonExtendedMin}m after 12:00 estimated from steps and heart rate.",
                    color = HealthColors.TextSecondary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/** Consecutive same-stage minutes as (start minute, length, level 0=awake 1=REM 2=light 3=deep); unknown codes are skipped. */
internal fun stageRuns(
    epochs: List<SleepAndReadinessEngine.SleepEpochMinute>,
    start: LocalDateTime
): List<Triple<Float, Float, Int>> {
    fun level(code: Int): Int? = when (code) {
        J2208Protocol.STAGE_AWAKE -> 0
        J2208Protocol.STAGE_REM -> 1
        J2208Protocol.STAGE_LIGHT -> 2
        J2208Protocol.STAGE_DEEP -> 3
        else -> null
    }
    val out = mutableListOf<Triple<Float, Float, Int>>()
    var runStart = 0L
    var runLvl: Int? = null
    var prev = Long.MIN_VALUE
    for (e in epochs) {
        val m = Duration.between(start, e.timestamp).toMinutes()
        val lvl = level(e.stageCode)
        if (lvl != runLvl || m != prev + 1) {
            runLvl?.let { out += Triple(runStart.toFloat(), (prev - runStart + 1).toFloat(), it) }
            runStart = m
            runLvl = lvl
        }
        prev = m
    }
    runLvl?.let { out += Triple(runStart.toFloat(), (prev - runStart + 1).toFloat(), it) }
    return out
}

@Composable
internal fun StageDurationRow(
    stage: String,
    duration: String,
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
            modifier = Modifier.width(140.dp)
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
