package com.jcwear.coach.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jcwear.coach.domain.PartUnit
import com.jcwear.coach.domain.Provenance
import com.jcwear.coach.domain.ScoreBreakdown
import com.jcwear.coach.domain.SleepAndReadinessEngine.StitchedSleepSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Notes about where a night's times come from, for the sleep sheet. */
fun sleepNotes(s: StitchedSleepSession): List<String> = listOfNotNull(
    when (s.provenance) {
        Provenance.EDITED -> "Edited by you"
        Provenance.ADJUSTED -> "Adjusted by heart rate"
        Provenance.BAND -> null
    },
    "Confirmed by you".takeIf { s.confirmed },
    "No sleep stages this night".takeIf { !s.stagesAvailable }
)

/** "<inputs> · synced 07:12" plus "How is this calculated? ›"; tapping opens the parts sheet. Hidden without a score. */
@Composable
internal fun ScoreSource(title: String, breakdown: ScoreBreakdown?, lastSyncAt: Long, notes: List<String> = emptyList()) {
    breakdown ?: return
    var open by remember { mutableStateOf(false) }
    val synced = if (lastSyncAt > 0) " · synced " + Instant.ofEpochMilli(lastSyncAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)) else ""
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { open = true },
        verticalArrangement = Arrangement.Center
    ) {
        Text(breakdown.basis + synced, color = HealthColors.TextSecondary, fontSize = 13.sp)
        Text("How is this calculated? ›", color = HealthColors.AccentAmber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    if (open) ScoreInputsSheet(title, breakdown, notes) { open = false }
}

@Composable
private fun ScoreInputsSheet(title: String, b: ScoreBreakdown, notes: List<String>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = false) {},
                color = HealthColors.BgCard,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                border = BorderStroke(1.dp, Color(0x1FFFFFFF))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("How $title is calculated", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    b.parts.forEach { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(HealthColors.BgDeep)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.label, color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                if (p.unit == PartUnit.PERCENT) {
                                    Text("${p.value.roundToInt()}/100 · weight ${fmtPct(p.weight)}", color = HealthColors.TextSecondary, fontSize = 13.sp)
                                }
                            }
                            Text(String.format(Locale.US, "%+.0f", p.points), color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    val raw = b.parts.sumOf { it.points }.roundToInt()
                    Text(
                        if (b.cap != null && raw > b.cap) "Total $raw, capped at ${b.cap}: fell asleep after 03:00" else "Total ${b.score}",
                        color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                    )
                    notes.forEach { Text(it, color = HealthColors.TextSecondary, fontSize = 13.sp) }
                    AmberActionButton(text = "Got it", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun fmtPct(w: Double): String = String.format(Locale.US, "%.2f", w * 100).trimEnd('0').trimEnd('.') + "%"
