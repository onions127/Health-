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
// TRENDS TAB
// ============================================================================
@Composable
internal fun TrendsTabView(
    series90: List<MetricSeriesPoint>,
    onOpenCalendar: () -> Unit,
    onOpenMetricDetail: (String) -> Unit
) {
    val slice30 = remember(series90) { series90.takeLast(30) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Trends", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("30-day averages vs normal", color = HealthColors.TextSecondary, fontSize = 13.sp)
        }
        SecondaryActionButton(
            text = "Calendar · Nov",
            onClick = onOpenCalendar
        )
    }

    val trendItems = listOf(
        Triple("hrv", "HRV", "59 ms" to "↑ 8% vs normal"),
        Triple("rhr", "Resting HR", "54 bpm" to "↓ 2 vs normal"),
        Triple("sleep", "Sleep", "7h 12m" to "↑ 4 vs normal"),
        Triple("readiness", "Readiness", "79" to "↑ 5 vs normal"),
        Triple("strain", "Strain", "75" to "Within normal"),
        Triple("spo2", "Blood Oxygen", "98%" to "Within normal"),
        Triple("skinTemp", "Skin Temp", "+0.0 °C" to "Within normal")
    )

    trendItems.forEach { (id, title, avgAndChip) ->
        val values = slice30.map { it.rolling3d[id] ?: 60f }
        val (nMin, nMax) = when (id) {
            "hrv" -> 55f to 63f
            "rhr" -> 51f to 56f
            "sleep" -> 410f to 465f
            "readiness" -> 72f to 85f
            "strain" -> 65f to 82f
            "spo2" -> 95f to 99f
            else -> -0.3f to 0.3f
        }
        HealthCard(onClick = { onOpenMetricDetail(id) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(avgAndChip.first, color = HealthColors.AccentAmber, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(avgAndChip.second, color = HealthColors.TextSecondary, fontSize = 13.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniSparklineCanvas(
                        values = values,
                        normalMin = nMin,
                        normalMax = nMax,
                        color = if (id == "strain") HealthColors.AccentAmber else HealthColors.ScoreGreen
                    )
                    Text("›", color = HealthColors.TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
