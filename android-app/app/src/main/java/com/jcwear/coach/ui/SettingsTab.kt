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
    syncStatusText: String,
    batteryPct: Int?,
    bandNote: String?,
    lastBackupText: String,
    cleanupPending: Boolean,
    recentSyncs: List<String>,
    onForgetBand: () -> Unit,
    homelabUrl: String,
    useMetricUnits: Boolean,
    historyDays: Int,
    onOpenSyncSheet: () -> Unit,
    onBackupNow: () -> Unit,
    onEditServerUrl: () -> Unit,
    onToggleUnits: () -> Unit
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Band", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(syncStatusText, color = HealthColors.TextSecondary, fontSize = 13.sp)
                    Text(connectionState, color = HealthColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BatteryBadge(batteryPct)
            }
            bandNote?.let { Text(it, color = HealthColors.ScoreAmber, fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryActionButton(text = "Sync now", onClick = onOpenSyncSheet)
                SecondaryActionButton(text = "Forget band", onClick = onForgetBand)
            }
        }
    }

    // 2. Backup to home server
    HealthCard(onClick = onBackupNow) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Backup to home server", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(lastBackupText, color = HealthColors.TextSecondary, fontSize = 13.sp)
                    if (cleanupPending) Text("Finishing data cleanup…", color = HealthColors.TextSecondary, fontSize = 13.sp)
                }
                SecondaryActionButton(text = "Back up now", onClick = onBackupNow)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEditServerUrl() },
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Server · ${homelabUrl.ifBlank { "not set" }}",
                    color = HealthColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("Edit", color = HealthColors.AccentAmber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    // Recent syncs (D13)
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Recent syncs", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (recentSyncs.isEmpty()) {
                Text("No syncs yet. Open the app near your band.", color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
            recentSyncs.forEach { Text(it, color = HealthColors.TextSecondary, fontSize = 13.sp) }
        }
    }

    // 3. Units
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

    // 4. About
    HealthCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("About Health++", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            StatusChip("$historyDays days of band data", com.jcwear.coach.domain.Status.NORMAL)
        }
    }
}
