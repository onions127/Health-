package com.jcwear.coach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.jcwear.coach.domain.EditCheck
import com.jcwear.coach.domain.LabelKind
import com.jcwear.coach.domain.Provenance
import com.jcwear.coach.domain.SleepAndReadinessEngine
import com.jcwear.coach.domain.SleepLabel
import com.jcwear.coach.domain.anchorAwake
import com.jcwear.coach.domain.anchorEdit
import com.jcwear.coach.domain.mergeAwake
import com.jcwear.coach.domain.checkEdit
import com.jcwear.coach.domain.labelFor
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// ============================================================================
// SLEEP DETAIL VIEW (real times, hypnogram from band stages, stage minutes)
// ============================================================================
@Composable
internal fun SleepDetailView(
    day: DayMetrics,
    days: List<DayMetrics>,
    onSaveLabel: (SleepLabel) -> Unit,
    onBack: () -> Unit,
    openEditor: Boolean = false,
    lastSyncAt: Long = 0L
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
            Text("←", color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.SemiBold)
        }
        Text("Sleep Detail", color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
        Spacer(Modifier.size(48.dp))
    }

    val session = day.sleep
    if (session == null) {
        HealthCard {
            Text("No sleep recorded for this night.", color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
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
                Text("You slept", color = HealthColors.TextSecondary, fontSize = HealthType.Small)
                Text(
                    text = formatSleepHm(session.totalSleepMin),
                    color = HealthColors.TextPrimary,
                    fontSize = HealthType.Display,
                    fontWeight = FontWeight.SemiBold
                )
                InsightChip(Metrics.sleep, days, day)
            }
            ScoreGauge(score = day.sleepScore, size = 76.dp, label = "Score")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Fell asleep ${session.startTime.format(hm)} · Woke ${session.endTime.format(hm)}",
            color = HealthColors.TextPrimary,
            fontSize = HealthType.Body,
            fontWeight = FontWeight.Medium
        )
        // W3: why the big number is less than the window. In bed = asleep + awake (no-data minutes count as awake).
        if (session.awakeMin > 0) {
            Text(
                "In bed ${formatSleepHm(session.totalSleepMin + session.awakeMin)} · awake ${formatSleepHm(session.awakeMin)}",
                color = HealthColors.TextSecondary, fontSize = HealthType.Small
            )
        }
        if (session.startTime.hour in 3..11) {
            Text("Asleep after 03:00, so the score is capped at 70.", color = HealthColors.TextSecondary, fontSize = HealthType.Small)
        }
        val bandDiffers = Duration.between(session.bandStartTime, session.startTime).abs().toMinutes() > 10 ||
            Duration.between(session.bandEndTime, session.endTime).abs().toMinutes() > 10
        if (bandDiffers) {
            Text(
                "Band reported ${formatSleepHm(session.bandTotalSleepMin)} (${session.bandStartTime.format(hm)}–${session.bandEndTime.format(hm)})",
                color = HealthColors.TextSecondary, fontSize = HealthType.Small
            )
        }
        ScoreSource("Sleep Score", day.sleepParts, lastSyncAt, sleepNotes(session))
        SleepLabelRow(session, openEditor, onSaveLabel)
    }

    if (!session.stagesAvailable) {
        HealthCard {
            Text("Your band didn't record sleep stages this night.", color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
        }
        return
    }

    // Hypnogram from the band's per-minute stages
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Sleep Stages", color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
                Text("${session.startTime.format(hm)} – ${session.endTime.format(hm)}", color = HealthColors.TextSecondary, fontSize = HealthType.Small)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.height(120.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text("Awake", color = HealthColors.TextSecondary, fontSize = HealthType.Small)
                    Text("REM", color = HealthColors.TextSecondary, fontSize = HealthType.Small)
                    Text("Light", color = HealthColors.TextSecondary, fontSize = HealthType.Small)
                    Text("Deep", color = HealthColors.TextSecondary, fontSize = HealthType.Small)
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
            Text("Time in each stage", color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
            val inBed = (session.totalSleepMin + session.awakeMin).coerceAtLeast(1)
            listOf(
                Triple("Deep", session.deepMin, HealthColors.StageDeep),
                Triple("REM", session.remMin, HealthColors.StageRem),
                Triple("Light", session.lightMin, HealthColors.StageLight),
                Triple("Awake", session.awakeMin, HealthColors.StageAwake)
            ).forEach { (name, min, color) ->
                StageDurationRow(name, formatSleepHm(min), "${min * 100 / inBed}% of the night", color)
            }
            if (session.unstagedMin > 0) {
                Text(
                    "${formatSleepHm(session.unstagedMin)} of your edited night has no band stages.",
                    color = HealthColors.TextSecondary,
                    fontSize = HealthType.Small
                )
            }
        }
    }
}

/** "Looks right" / "Edit" under the night's times; edited/confirmed nights say so. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepLabelRow(
    session: SleepAndReadinessEngine.StitchedSleepSession,
    openEditor: Boolean,
    onSaveLabel: (SleepLabel) -> Unit
) {
    var editing by remember(session.sessionKey) { mutableStateOf(openEditor) }
    val status = when {
        session.provenance == Provenance.EDITED -> "Edited by you"
        session.confirmed -> "Confirmed by you"
        else -> null
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(status ?: "Is this right?", color = HealthColors.TextSecondary, fontSize = HealthType.Small, modifier = Modifier.weight(1f))
        if (status == null) {
            TextButton(onClick = { onSaveLabel(labelFor(session, LabelKind.CONFIRMED, session.startTime, session.endTime)) }) {
                Text("Looks right")
            }
        }
        TextButton(onClick = { editing = true }) { Text("Edit") }
    }
    if (!editing) return

    val onsetState = rememberTimePickerState(session.startTime.hour, session.startTime.minute, is24Hour = true)
    val wakeState = rememberTimePickerState(session.endTime.hour, session.endTime.minute, is24Hour = true)
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    // W2: awake periods as picked clock times; placed in the night only when saving.
    val awakeTimes = remember { mutableStateListOf(*session.userAwake.map { (a, b) -> a.toLocalTime() to b.toLocalTime() }.toTypedArray()) }
    var adding by remember { mutableStateOf(false) }
    val fromState = rememberTimePickerState(session.startTime.plusHours(3).hour, 0, is24Hour = true)
    val toState = rememberTimePickerState(session.startTime.plusHours(3).hour, 30, is24Hour = true)
    val (onset, wake) = anchorEdit(
        session.bandStartTime,
        LocalTime.of(onsetState.hour, onsetState.minute),
        LocalTime.of(wakeState.hour, wakeState.minute)
    )
    val awake = mergeAwake(awakeTimes.map { (a, b) -> anchorAwake(onset, a, b) })
    fun save() {
        onSaveLabel(labelFor(session, LabelKind.EDITED, onset, wake, awake = awake))
        editing = false
    }
    AlertDialog(
        onDismissRequest = { editing = false },
        shape = HealthShapes.Panel,
        title = { Text(if (adding) "Awake time" else confirm ?: "Edit sleep times") },
        text = {
            if (adding) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("From")
                    TimeInput(fromState)
                    Text("To")
                    TimeInput(toState)
                }
            } else if (confirm == null) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Fell asleep")
                    TimeInput(onsetState)
                    Text("Woke")
                    TimeInput(wakeState)
                    Text("Awake in the night")
                    awakeTimes.forEachIndexed { i, (a, b) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${a.format(hmFormat)} – ${b.format(hmFormat)}", modifier = Modifier.weight(1f))
                            TextButton(onClick = { awakeTimes.removeAt(i); error = null }) { Text("Remove") }
                        }
                    }
                    TextButton(onClick = { adding = true }) { Text("Add awake time") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = HealthType.Small) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    adding -> {
                        awakeTimes += LocalTime.of(fromState.hour, fromState.minute) to LocalTime.of(toState.hour, toState.minute)
                        adding = false
                        error = null
                    }
                    confirm != null -> save()
                    else -> when (val check = checkEdit(onset, wake, awake)) {
                        EditCheck.Ok -> save()
                        is EditCheck.Confirm -> confirm = check.message
                        is EditCheck.Invalid -> error = check.message
                    }
                }
            }) { Text(if (adding) "Add" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = {
                when {
                    adding -> adding = false
                    confirm != null -> confirm = null
                    else -> editing = false
                }
            }) { Text(if (adding) "Back" else "Cancel") }
        }
    )
}

private val hmFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Consecutive same-stage minutes as (start minute, length, level 0=awake 1=REM 2=light 3=deep); unknown codes are skipped. */
internal fun stageRuns(
    epochs: List<SleepAndReadinessEngine.SleepEpochMinute>,
    start: LocalDateTime
): List<Triple<Float, Float, Int>> {
    fun level(code: Int): Int? = when (code) {
        J2208Protocol.STAGE_AWAKE, SleepAndReadinessEngine.STAGE_NO_DATA -> 0
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
            Text(stage, color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
            Text(duration, color = HealthColors.TextSecondary, fontSize = HealthType.Small)
        }
        Text(contextText, color = HealthColors.TextSecondary, fontSize = HealthType.Small)
    }
}
