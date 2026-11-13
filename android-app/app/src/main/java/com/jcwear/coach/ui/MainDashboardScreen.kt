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

@Composable
fun MainDashboardScreen(
    viewModel: DashboardViewModel,
    database: LocalHealthDatabase,
    bleManager: J2208BleManager,
    coachRepo: ClaudeCoachRepository,
    onRequestBlePermissions: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val connState by bleManager.connectionState.collectAsState()
    val batteryPct by bleManager.batteryPct.collectAsState()
    val liveFrames by bleManager.liveFrames.collectAsState()
    val morningRmssd by bleManager.morningRmssdMs.collectAsState()


    // Navigation & UI state
    var currentTab by remember { mutableStateOf(MainTab.TODAY) }
    var activeDetailMetric by remember { mutableStateOf<String?>(null) }
    var checkStep by remember { mutableStateOf(CheckFlowStep.NONE) }
    var checkCountdown by remember { mutableStateOf(60) }
    var morningCheckCompleted by remember { mutableStateOf(false) }

    var dailyExpanded by remember { mutableStateOf(false) }
    var recoveryExpanded by remember { mutableStateOf(false) }
    var simulateSpo2Alert by remember { mutableStateOf(false) }
    var viewingHistoryDayIndex by remember { mutableStateOf<Int?>(null) }
    var activeSheet by remember { mutableStateOf(ActiveSheet.NONE) }

    // Detail chart state
    var selectedRange by remember { mutableStateOf("Month") }
    var selectedChartDayIdx by remember { mutableStateOf(89) }

    // Settings state
    var homelabUrl by remember { mutableStateOf("http://192.168.1.100:8000") }
    var backupEnabled by remember { mutableStateOf(true) }
    var autoSyncEnabled by remember { mutableStateOf(true) }
    var lastBackupText by remember { mutableStateOf("Last backup today, 06:42") }
    var useMetricUnits by remember { mutableStateOf(true) }

    // Coach chat state (strictly <= 3 short lines per bubble)
    var chatMessages by remember {
        mutableStateOf(
            listOf(
                ClaudeCoachRepository.ChatMessage(
                    "assistant",
                    "Your recovery hit 86 this morning.\nHRV is 64 ms, 8% above normal.\nGood day for intervals or heavy strength."
                )
            )
        )
    }
    var customCoachInput by remember { mutableStateOf("") }

    // Compute reports and 90-day series
    // shortcut: blank screen until the first IO load finishes; real loading states come in 1b (DS3)
    val reportsAndSeries = viewModel.state.collectAsState().value ?: return

    val recentReports = reportsAndSeries.first
    val series90 = reportsAndSeries.second
    val activeDayPoint = viewingHistoryDayIndex?.let { series90.getOrNull(it) } ?: series90.last()

    // Countdown effect when Measuring step is active
    LaunchedEffect(checkStep) {
        if (checkStep == CheckFlowStep.MEASURING) {
            checkCountdown = 60
            bleManager.startMorningCheck60s { _ ->
                checkStep = CheckFlowStep.RESULT
            }
            while (checkCountdown > 1 && checkStep == CheckFlowStep.MEASURING) {
                delay(1000)
                checkCountdown--
            }
            if (checkStep == CheckFlowStep.MEASURING) {
                checkStep = CheckFlowStep.RESULT
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(HealthColors.BgDeep, HealthColors.BgNavy)
                )
            )
    ) {
        // Main scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                checkStep != CheckFlowStep.NONE -> {
                    MorningCheckFlowView(
                        step = checkStep,
                        secondsRemaining = checkCountdown,
                        liveHrBpm = liveFrames.lastOrNull()?.hrBpm?.takeIf { it > 0 } ?: 52,
                        resultHrv = (morningRmssd?.roundToInt() ?: 66),
                        onBeginMeasuring = {
                            onRequestBlePermissions()
                            checkStep = CheckFlowStep.MEASURING
                        },
                        onSkipToResult = { checkStep = CheckFlowStep.RESULT },
                        onDone = {
                            morningCheckCompleted = true
                            checkStep = CheckFlowStep.NONE
                        },
                        onCancel = { checkStep = CheckFlowStep.NONE }
                    )
                }

                activeDetailMetric != null -> {
                    val metricId = activeDetailMetric!!
                    if (metricId == "sleep") {
                        SleepDetailView(
                            dayPoint = activeDayPoint,
                            onBack = { activeDetailMetric = null }
                        )
                    } else {
                        MetricDetailView(
                            metricId = metricId,
                            series90 = series90,
                            selectedRange = selectedRange,
                            selectedDayIndex = selectedChartDayIdx,
                            spo2AlertActive = simulateSpo2Alert,
                            onSelectRange = { selectedRange = it },
                            onSelectDayIndex = { selectedChartDayIdx = it },
                            onBack = { activeDetailMetric = null }
                        )
                    }
                }

                currentTab == MainTab.TODAY -> {
                    TodayTabView(
                        dayPoint = activeDayPoint,
                        series90 = series90,
                        isHistoricalView = viewingHistoryDayIndex != null,
                        morningCheckDone = morningCheckCompleted,
                        dailyExpanded = dailyExpanded,
                        recoveryExpanded = recoveryExpanded,
                        spo2AlertActive = simulateSpo2Alert || activeDayPoint.spo2Pct < 95,
                        onOpenSyncSheet = { activeSheet = ActiveSheet.SYNC },
                        onExitHistory = { viewingHistoryDayIndex = null },
                        onStartMorningCheck = { checkStep = CheckFlowStep.INTRO },
                        onOpenVerdictSheet = { activeSheet = ActiveSheet.VERDICT },
                        onToggleDailyExpand = { dailyExpanded = !dailyExpanded },
                        onToggleRecoveryExpand = { recoveryExpanded = !recoveryExpanded },
                        onOpenMetricDetail = { metricId ->
                            selectedChartDayIdx = if (metricId == "hrv") 81 else 89
                            activeDetailMetric = metricId
                        }
                    )
                }

                currentTab == MainTab.TRENDS -> {
                    TrendsTabView(
                        series90 = series90,
                        onOpenCalendar = { activeSheet = ActiveSheet.CALENDAR },
                        onOpenMetricDetail = { metricId ->
                            selectedChartDayIdx = if (metricId == "hrv") 81 else 89
                            activeDetailMetric = metricId
                        }
                    )
                }

                currentTab == MainTab.COACH -> {
                    CoachTabView(
                        messages = chatMessages,
                        inputText = customCoachInput,
                        onInputChange = { customCoachInput = it },
                        onAskQuestion = { question ->
                            val updated = (chatMessages + ClaudeCoachRepository.ChatMessage("user", question)).takeLast(4)
                            chatMessages = updated
                            customCoachInput = ""
                            scope.launch {
                                val reply = coachRepo.askClaudeCoach(
                                    messages = updated,
                                    includeHealthContext = true,
                                    recentReports = recentReports,
                                    homelabBaseUrl = homelabUrl,
                                    anthropicApiKey = ""
                                )
                                chatMessages = (updated + ClaudeCoachRepository.ChatMessage("assistant", reply)).takeLast(4)
                            }
                        },
                        onAskInClaudeProApp = { question ->
                            val prompt = coachRepo.buildClaudeProPrompt(
                                userQuestion = question.ifBlank { "Based on my last 14 days, how should I train today?" },
                                recentReports = recentReports
                            )
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Health++ Claude Pro Brief", prompt))
                            Toast.makeText(
                                context,
                                "Copied 14-day brief! Paste in Claude Pro.",
                                Toast.LENGTH_LONG
                            ).show()
                            launchClaudeProAppOrWeb(context)
                        }
                    )
                }

                currentTab == MainTab.SETTINGS -> {
                    SettingsTabView(
                        connectionState = connState,
                        batteryPct = batteryPct ?: 84,
                        autoSyncEnabled = autoSyncEnabled,
                        backupEnabled = backupEnabled,
                        lastBackupText = lastBackupText,
                        homelabUrl = homelabUrl,
                        useMetricUnits = useMetricUnits,
                        spo2AlertDemo = simulateSpo2Alert,
                        onOpenSyncSheet = { activeSheet = ActiveSheet.SYNC },
                        onToggleAutoSync = { autoSyncEnabled = !autoSyncEnabled },
                        onToggleBackup = {
                            backupEnabled = !backupEnabled
                            if (backupEnabled) {
                                scope.launch {
                                    val res = coachRepo.syncToHomelab(homelabUrl, "", recentReports)
                                    lastBackupText = if (res.isSuccess) "Backed up just now" else "Local backup ready (tap URL to configure)"
                                }
                            }
                        },
                        onEditServerUrl = { activeSheet = ActiveSheet.SERVER_CONFIG },
                        onToggleUnits = { useMetricUnits = !useMetricUnits },
                        onToggleSpo2AlertDemo = {
                            simulateSpo2Alert = !simulateSpo2Alert
                            if (simulateSpo2Alert) {
                                currentTab = MainTab.TODAY
                            }
                        }
                    )
                }
            }
        }

        // Bottom Navigation Bar (4 tabs: Today · Trends · Coach · Settings)
        BottomNavBar(
            activeTab = currentTab,
            onSelectTab = { tab ->
                activeDetailMetric = null
                checkStep = CheckFlowStep.NONE
                currentTab = tab
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Bottom Sheet Overlays
        if (activeSheet != ActiveSheet.NONE) {
            BottomSheetOverlay(
                sheet = activeSheet,
                connectionState = connState,
                batteryPct = batteryPct ?: 84,
                dayPoint = activeDayPoint,
                series90 = series90,
                homelabUrl = homelabUrl,
                onUpdateHomelabUrl = { homelabUrl = it },
                onTriggerSync = {
                    onRequestBlePermissions()
                    bleManager.scanAndConnect()
                    activeSheet = ActiveSheet.NONE
                },
                onSelectHistoryDay = { idx ->
                    viewingHistoryDayIndex = idx
                    activeSheet = ActiveSheet.NONE
                    currentTab = MainTab.TODAY
                },
                onDismiss = { activeSheet = ActiveSheet.NONE }
            )
        }
    }
}

internal fun launchClaudeProAppOrWeb(context: Context) {
    try {
        val pkgIntent = context.packageManager.getLaunchIntentForPackage("com.anthropic.claude")
        if (pkgIntent != null) {
            pkgIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(pkgIntent)
            return
        }
    } catch (_: Exception) {}
    try {
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/new")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(webIntent)
    } catch (_: Exception) {}
}
