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
// SETTINGS TAB
// ============================================================================
@Composable
internal fun SettingsTabView(
    connectionState: String,
    batteryPct: Int,
    autoSyncEnabled: Boolean,
    backupEnabled: Boolean,
    lastBackupText: String,
    homelabUrl: String,
    useMetricUnits: Boolean,
    spo2AlertDemo: Boolean,
    onOpenSyncSheet: () -> Unit,
    onToggleAutoSync: () -> Unit,
    onToggleBackup: () -> Unit,
    onEditServerUrl: () -> Unit,
    onToggleUnits: () -> Unit,
    onToggleSpo2AlertDemo: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Settings", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("Band & local server", color = HealthColors.TextSecondary, fontSize = 13.sp)
        }
    }

    // 1. Band
    HealthCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Health++ Band 2", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "$connectionState · $batteryPct% battery",
                    color = HealthColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SecondaryActionButton(text = "Re-pair", onClick = onOpenSyncSheet)
        }
    }

    // 2. Sync
    HealthCard(onClick = onToggleAutoSync) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Background sync", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Last synced 6 min ago", color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
            ContextChip(if (autoSyncEnabled) "On" else "Off", score = if (autoSyncEnabled) 85 else 50)
        }
    }

    // 3. Backup to home server
    HealthCard(onClick = onToggleBackup) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Backup to home server", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(lastBackupText, color = HealthColors.TextSecondary, fontSize = 13.sp)
                }
                ContextChip(if (backupEnabled) "Active" else "Paused", score = if (backupEnabled) 85 else 50)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEditServerUrl() },
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Server · $homelabUrl", color = HealthColors.TextSecondary, fontSize = 13.sp)
                Text("Edit", color = HealthColors.AccentAmber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    // 4. Units
    HealthCard(onClick = onToggleUnits) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Units", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Distance and temperature", color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
            SecondaryActionButton(
                text = if (useMetricUnits) "Metric · km, °C" else "Imperial · mi, °F",
                onClick = onToggleUnits
            )
        }
    }

    // 5. Out-of-Bounds SpO2 Alert State Preview
    HealthCard(onClick = onToggleSpo2AlertDemo) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Preview SpO2 alert state", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Shows red alert card above Daily Score", color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
            ContextChip(if (spo2AlertDemo) "Shown" else "Hidden", score = if (spo2AlertDemo) 30 else 80)
        }
    }

    // 6. About
    HealthCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("About Health++", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Version 2.4 · Claude Pro CLI ready", color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
            ContextChip("90d history", score = 80)
        }
    }
}
