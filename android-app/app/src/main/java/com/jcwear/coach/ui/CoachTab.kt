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
// COACH TAB (Max 3 lines per bubble + Suggested Chips + Claude Pro $0 Handoff)
// ============================================================================
@Composable
internal fun CoachTabView(
    messages: List<ClaudeCoachRepository.ChatMessage>,
    recoveryChip: Pair<String, com.jcwear.coach.domain.Status>?,
    inputText: String,
    onInputChange: (String) -> Unit,
    onAskQuestion: (String) -> Unit,
    onAskInClaudeProApp: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Coach", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("Grounded in your own normal", color = HealthColors.TextSecondary, fontSize = 13.sp)
        }
        recoveryChip?.let { StatusChip(it.first, it.second) }
    }

    // Chat thread
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        messages.forEach { msg ->
            val isUser = msg.role == "user"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
            ) {
                Surface(
                    color = if (isUser) HealthColors.AccentAmber else HealthColors.BgCard,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, HealthColors.BorderHairline),
                    modifier = Modifier.widthIn(max = 296.dp)
                ) {
                    Text(
                        text = msg.content,
                        color = if (isUser) HealthColors.TextInverse else HealthColors.TextPrimary,
                        fontSize = if (isUser) 16.sp else 13.sp,
                        fontWeight = if (isUser) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }
    }

    // Suggested-question chips above input
    val suggestions = listOf(
        "Can I push hard today?",
        "Why did my HRV rise?",
        "How was my deep sleep?"
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Suggested questions", color = HealthColors.TextSecondary, fontSize = 13.sp)
        suggestions.forEach { q ->
            SecondaryActionButton(
                text = q,
                onClick = { onAskQuestion(q) }
            )
        }
    }

    // Custom Input Row
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            placeholder = { Text("Ask about your recovery...", color = HealthColors.TextSecondary, fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp)
        )
        AmberActionButton(
            text = "Ask",
            onClick = {
                if (inputText.isNotBlank()) onAskQuestion(inputText.trim())
            }
        )
    }

    // Zero-cost Claude Pro subscription launcher card
    HealthCard(onClick = { onAskInClaudeProApp(inputText) }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Ask in Claude Pro App ($0 API)", color = HealthColors.AccentAmber, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Copies your 14-day metrics & opens Claude", color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
            Text("↗", color = HealthColors.AccentAmber, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
