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

/**
 * Health++ Design Tokens (Deep Navy, Soft Glow, 8pt Grid, Strictly 3 Font Sizes)
 */
object HealthColors {
    val BgDeep = Color(0xFF0B1020)
    val BgNavy = Color(0xFF111833)
    val BgCard = Color(0xFF151E3F)
    val BgCardElevated = Color(0xFF1B2650)
    val BorderHairline = Color(0x10FFFFFF) // white at 6% opacity

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFFA8B8D0)
    val TextInverse = Color(0xFF0B1020)

    val AccentAmber = Color(0xFFFFB86B)
    val ScoreGreen = Color(0xFF34D399)
    val ScoreAmber = Color(0xFFFBBF24)
    val ScoreCoral = Color(0xFFFB7185)
    val AlertRed = Color(0xFFEF4444)
    val AlertBg = Color(0xFF2A121B)

    val StageDeep = Color(0xFF6366F1)
    val StageRem = Color(0xFF38BDF8)
    val StageLight = Color(0xFF34D399)
    val StageAwake = Color(0xFFFB7185)

    fun forScore(score: Int): Color = when {
        score >= 70 -> ScoreGreen
        score >= 40 -> ScoreAmber
        else -> ScoreCoral
    }
}

enum class MainTab { TODAY, TRENDS, COACH, SETTINGS }
enum class ActiveSheet { NONE, SYNC, VERDICT, CALENDAR, SERVER_CONFIG }
enum class CheckFlowStep { NONE, INTRO, MEASURING, RESULT }

data class MetricSeriesPoint(
    val index: Int,
    val date: LocalDate,
    val label: String,
    val dailyScore: Int,
    val recoveryScore: Int,
    val readiness: Int,
    val sleepScore: Int,
    val sleepMin: Int,
    val strainScore100: Int,
    val hrvMs: Int,
    val rhrBpm: Int,
    val steps: Int,
    val spo2Pct: Int,
    val skinTempDelta: Float,
    val rolling3d: Map<String, Float> = emptyMap()
)

fun formatSleepHm(totalMin: Int): String {
    val hrs = totalMin / 60
    val mins = totalMin % 60
    return "${hrs}h ${mins.toString().padStart(2, '0')}m"
}

/**
 * Builds a 90-day series combining real SQLite records from the J2208A band with a deterministic
 * 90-day physiological baseline so Week / Month / 3M / Year charts and rolling averages are always rich.
 */
fun buildNinetyDaySeries(
    reportsByDate: Map<LocalDate, DailyReadinessReport>,
    morningHrvOverride: Double?
): List<MetricSeriesPoint> {
    val today = LocalDate.now()
    val rawPoints = mutableListOf<MetricSeriesPoint>()
    val dowFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    for (i in 0 until 90) {
        val d = today.minusDays((89 - i).toLong())
        val rep = reportsByDate[d]
        val wave = (sin(i / 4.5) * 3.5 + cos(i / 9.0) * 2.0).toFloat()

        var hrv = rep?.overnightHrvMs?.roundToInt() ?: (59f + wave).roundToInt()
        var rhr = rep?.restingHrBpm?.roundToInt() ?: (53f - wave * 0.4f).roundToInt()
        var sleepMin = rep?.totalSleepMin?.takeIf { it > 0 } ?: (432f + wave * 6f).roundToInt()
        var sleepScore = rep?.sleepScore ?: ((sleepMin - 300) * 0.42f).roundToInt().coerceIn(48, 96)
        var strain100 = rep?.let { (it.strainScore / 21.0 * 100.0).roundToInt().coerceIn(35, 96) }
            ?: (74f + sin(i / 3.2) * 10f).roundToInt()
        var steps = rep?.steps?.takeIf { it > 0 } ?: (8100 + (sin(i / 3.2) * 1600).roundToInt())
        var spo2 = rep?.spo2AvgPct?.roundToInt() ?: 98
        var tempDelta = rep?.skinTempDeltaC?.toFloat() ?: 0.0f

        // Realistic outliers across 90 days
        if (i == 24) {
            hrv = 45; rhr = 59; sleepMin = 355; sleepScore = 54
        } else if (i == 52) {
            hrv = 44; rhr = 60; tempDelta = 0.8f; spo2 = 93; sleepMin = 365; sleepScore = 56
        } else if (i == 74) {
            hrv = 69; rhr = 49; sleepMin = 475; sleepScore = 91
        } else if (i == 81) {
            // Historical low-recovery outlier day (8 days ago)
            hrv = 46; rhr = 58; sleepMin = 365; sleepScore = 58; strain100 = 49; steps = 4820; spo2 = 96; tempDelta = 0.4f
        } else if (i == 89) {
            // Today
            if (morningHrvOverride != null && morningHrvOverride > 0.0) {
                hrv = morningHrvOverride.roundToInt()
                rhr = 51
            } else if (rep == null) {
                hrv = 64; rhr = 52; sleepMin = 440; sleepScore = 82; strain100 = 78; steps = 8420; spo2 = 98; tempDelta = 0.1f
            }
        }

        val readiness = rep?.readinessScore ?: (hrv * 0.85f + (100 - rhr) * 0.65f).roundToInt().coerceIn(38, 98)
        val recovery = if (i == 89 && morningHrvOverride != null) 88
        else if (i == 89) 86
        else if (i == 81) 52
        else (hrv * 0.92f + (90 - rhr) * 0.72f).roundToInt().coerceIn(36, 98)

        val dailyScore = if (i == 89 && morningHrvOverride != null) 86
        else if (i == 89) 84
        else if (i == 81) 56
        else (readiness * 0.42f + sleepScore * 0.36f + strain100 * 0.22f).roundToInt().coerceIn(38, 98)

        rawPoints.add(
            MetricSeriesPoint(
                index = i,
                date = d,
                label = d.format(dowFmt),
                dailyScore = dailyScore,
                recoveryScore = recovery,
                readiness = readiness,
                sleepScore = sleepScore,
                sleepMin = sleepMin,
                strainScore100 = strain100,
                hrvMs = hrv,
                rhrBpm = rhr,
                steps = steps,
                spo2Pct = spo2,
                skinTempDelta = tempDelta
            )
        )
    }

    // Compute 3-day rolling averages
    return rawPoints.mapIndexed { idx, pt ->
        val slice = rawPoints.subList((idx - 2).coerceAtLeast(0), idx + 1)
        val roll = mapOf(
            "hrv" to slice.map { it.hrvMs }.average().toFloat(),
            "rhr" to slice.map { it.rhrBpm }.average().toFloat(),
            "readiness" to slice.map { it.readiness }.average().toFloat(),
            "sleep" to slice.map { it.sleepMin }.average().toFloat(),
            "strain" to slice.map { it.strainScore100 }.average().toFloat(),
            "steps" to slice.map { it.steps }.average().toFloat(),
            "spo2" to slice.map { it.spo2Pct }.average().toFloat(),
            "skinTemp" to slice.map { it.skinTempDelta.toDouble() }.average().toFloat()
        )
        pt.copy(rolling3d = roll)
    }
}

@Composable
fun MainDashboardScreen(
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

    var refreshTick by remember { mutableStateOf(0) }
    DisposableEffect(bleManager) {
        bleManager.onDataUpdated = { refreshTick++ }
        onDispose { bleManager.onDataUpdated = null }
    }

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
    val reportsAndSeries = remember(refreshTick, morningRmssd) {
        val sleepBlocks = database.getAllSleepBlocks()
        val hrRecords = database.getAllHrRecords()
        val hrvRecords = database.getAllHrvRecords()
        val spo2Records = database.getAllSpo2Records()
        val tempRecords = database.getAllTempRecords()
        val stepRecords = database.getAllStepRecords()

        val stitched = SleepAndReadinessEngine.stitchSleepSessions(sleepBlocks, stepRecords, hrRecords)
        val today = LocalDate.now()
        val map = mutableMapOf<LocalDate, DailyReadinessReport>()
        for (d in 0..13) {
            val date = today.minusDays(d.toLong())
            map[date] = SleepAndReadinessEngine.buildDailyReadinessReport(
                targetDate = date,
                sleepSessions = stitched,
                hrvRecords = hrvRecords,
                hrRecords = hrRecords,
                spo2Records = spo2Records,
                tempRecords = tempRecords,
                stepRecords = stepRecords,
                morningRmssdOverride = if (d == 0) morningRmssd else null
            )
        }
        val series = buildNinetyDaySeries(map, morningRmssd)
        val reportsList = (0..13).mapNotNull { map[today.minusDays(it.toLong())] }
        reportsList to series
    }

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

private fun launchClaudeProAppOrWeb(context: Context) {
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

// ============================================================================
// TODAY SCREEN
// ============================================================================
@Composable
private fun TodayTabView(
    dayPoint: MetricSeriesPoint,
    series90: List<MetricSeriesPoint>,
    isHistoricalView: Boolean,
    morningCheckDone: Boolean,
    dailyExpanded: Boolean,
    recoveryExpanded: Boolean,
    spo2AlertActive: Boolean,
    onOpenSyncSheet: () -> Unit,
    onExitHistory: () -> Unit,
    onStartMorningCheck: () -> Unit,
    onOpenVerdictSheet: () -> Unit,
    onToggleDailyExpand: () -> Unit,
    onToggleRecoveryExpand: () -> Unit,
    onOpenMetricDetail: (String) -> Unit
) {
    // 1. Greeting and date + small sync status dot (48dp tap target)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = if (isHistoricalView) dayPoint.label else "Morning, Alex",
                color = HealthColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (isHistoricalView) "Historical snapshot" else dayPoint.label,
                color = HealthColors.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isHistoricalView) {
                AmberActionButton(
                    text = "Back to today",
                    onClick = onExitHistory
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background( Color(0x0AFFFFFF))
                    .clickable { onOpenSyncSheet() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(HealthColors.ScoreGreen)
                )
            }
        }
    }

    // 2. Morning Check prompt card (shown only in morning until done that day)
    if (!morningCheckDone && !isHistoricalView && !spo2AlertActive) {
        HealthCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Morning check",
                    color = HealthColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                AmberActionButton(
                    text = "Start 60-sec check",
                    onClick = onStartMorningCheck
                )
            }
        }
    }

    // 3. Coach verdict card (one line + one action; tapping opens 2-3 reasons sheet)
    HealthCard(onClick = onOpenVerdictSheet) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isHistoricalView) "Low recovery. Keep effort light."
                else "Well recovered. Good for hard training.",
                color = HealthColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "›",
                color = HealthColors.TextSecondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    // 7. Out-of-bounds Alert card (above Daily Score when SpO2 is low)
    if (spo2AlertActive) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable { onOpenMetricDetail("spo2") },
            color = HealthColors.AlertBg,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, HealthColors.AlertRed.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Blood oxygen low overnight: 91%",
                        color = Color(0xFFFECACA),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "↓ 7% vs normal · Tap for details",
                        color = Color(0xFFFCA5A5),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = "›",
                    color = Color(0xFFFCA5A5),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    // 4. Daily Score card (1 big score with ring + ONLY soft radial glow + trend chip; expands in place)
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clickable { onToggleDailyExpand() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Daily Score",
                        color = HealthColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "${dayPoint.dailyScore}",
                            color = HealthColors.TextPrimary,
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold
                        )
                        ContextChip(
                            text = if (isHistoricalView) "↓ 22 vs normal" else "↑ 6 vs normal",
                            score = dayPoint.dailyScore
                        )
                    }
                }
                ScoreRingGauge(
                    score = dayPoint.dailyScore,
                    size = 72.dp,
                    showRadialGlow = true
                )
            }

            AnimatedVisibility(
                visible = dailyExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = HealthColors.BorderHairline)
                    SubMetricBarRow(
                        title = "Readiness",
                        contextText = "↑ 5 vs normal",
                        score = dayPoint.readiness,
                        normalMin = 72,
                        normalMax = 85,
                        barColor = HealthColors.ScoreGreen,
                        onClick = { onOpenMetricDetail("readiness") }
                    )
                    SubMetricBarRow(
                        title = "Sleep",
                        contextText = "↑ 4 vs normal",
                        score = dayPoint.sleepScore,
                        normalMin = 70,
                        normalMax = 85,
                        barColor = HealthColors.ScoreGreen,
                        onClick = { onOpenMetricDetail("sleep") }
                    )
                    SubMetricBarRow(
                        title = "Strain",
                        contextText = "Within normal",
                        score = dayPoint.strainScore100,
                        normalMin = 65,
                        normalMax = 82,
                        barColor = HealthColors.AccentAmber,
                        onClick = { onOpenMetricDetail("strain") }
                    )
                }
            }
        }
    }

    // 5. Recovery Score card (expands to show HRV & Resting HR with 7-day sparklines)
    val last7Hrv = remember(series90) { series90.takeLast(7).map { it.hrvMs.toFloat() } }
    val last7Rhr = remember(series90) { series90.takeLast(7).map { it.rhrBpm.toFloat() } }

    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable { onToggleRecoveryExpand() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Recovery",
                        color = HealthColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "${dayPoint.recoveryScore}",
                            color = HealthColors.TextPrimary,
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold
                        )
                        ContextChip(
                            text = if (isHistoricalView) "Lowest in 2 weeks" else "↑ 8% vs normal",
                            score = dayPoint.recoveryScore
                        )
                    }
                }
                ScoreRingGauge(
                    score = dayPoint.recoveryScore,
                    size = 60.dp,
                    showRadialGlow = false
                )
            }

            AnimatedVisibility(
                visible = recoveryExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = HealthColors.BorderHairline)
                    SparklineVitalRow(
                        title = "HRV · ${dayPoint.hrvMs}",
                        unit = "ms",
                        contextText = if (isHistoricalView) "Lowest in 2 weeks" else "↑ 8% vs normal",
                        values = last7Hrv,
                        normalMin = 55f,
                        normalMax = 63f,
                        lineColor = HealthColors.ScoreGreen,
                        onClick = { onOpenMetricDetail("hrv") }
                    )
                    SparklineVitalRow(
                        title = "Resting HR · ${dayPoint.rhrBpm}",
                        unit = "bpm",
                        contextText = if (isHistoricalView) "↑ 4 bpm vs normal" else "↓ 2 bpm vs normal",
                        values = last7Rhr,
                        normalMin = 51f,
                        normalMax = 56f,
                        lineColor = HealthColors.ScoreGreen,
                        onClick = { onOpenMetricDetail("rhr") }
                    )
                }
            }
        }
    }

    // 6. Activity card: steps compared with normal range shaded band
    HealthCard(onClick = { onOpenMetricDetail("steps") }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Steps  ${String.format(Locale.US, "%,d", dayPoint.steps)}",
                    color = HealthColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                ContextChip(
                    text = if (isHistoricalView) "Below normal · 7k–10k" else "Within normal · 7k–10k",
                    score = if (isHistoricalView) 55 else 80
                )
            }
            StepsNormalBandBar(
                steps = dayPoint.steps,
                normalMin = 7000,
                normalMax = 10000
            )
        }
    }
}

// ============================================================================
// DETAIL & HISTORY VIEW (Week / Month / 3M / Year + 3-Day Rolling Avg + Outliers)
// ============================================================================
@Composable
private fun MetricDetailView(
    metricId: String,
    series90: List<MetricSeriesPoint>,
    selectedRange: String,
    selectedDayIndex: Int,
    spo2AlertActive: Boolean,
    onSelectRange: (String) -> Unit,
    onSelectDayIndex: (Int) -> Unit,
    onBack: () -> Unit
) {
    val title = when (metricId) {
        "hrv" -> "Heart Rate Variability"
        "rhr" -> "Resting Heart Rate"
        "readiness" -> "Readiness"
        "strain" -> "Daily Strain"
        "spo2" -> "Blood Oxygen"
        "skinTemp" -> "Skin Temperature"
        "steps" -> "Daily Steps"
        else -> "Heart Rate Variability"
    }
    val unit = when (metricId) {
        "hrv" -> "ms"
        "rhr" -> "bpm"
        "spo2" -> "%"
        "skinTemp" -> "°C"
        "steps" -> "steps"
        else -> ""
    }
    val (normalMin, normalMax, normalLabel) = when (metricId) {
        "hrv" -> Triple(55f, 63f, "55–63 ms normal")
        "rhr" -> Triple(51f, 56f, "51–56 bpm normal")
        "readiness" -> Triple(72f, 85f, "72–85 normal")
        "strain" -> Triple(65f, 82f, "65–82 normal")
        "spo2" -> Triple(95f, 99f, "95–99% normal")
        "skinTemp" -> Triple(-0.3f, 0.3f, "±0.3 °C normal")
        "steps" -> Triple(7000f, 10000f, "7k–10k normal")
        else -> Triple(55f, 63f, "55–63 ms normal")
    }

    fun extractRaw(pt: MetricSeriesPoint): Float = when (metricId) {
        "hrv" -> pt.hrvMs.toFloat()
        "rhr" -> pt.rhrBpm.toFloat()
        "readiness" -> pt.readiness.toFloat()
        "strain" -> pt.strainScore100.toFloat()
        "spo2" -> if (spo2AlertActive && pt.index == 89) 91f else pt.spo2Pct.toFloat()
        "skinTemp" -> pt.skinTempDelta
        "steps" -> pt.steps.toFloat()
        else -> pt.hrvMs.toFloat()
    }

    fun formatValue(v: Float): String = when (metricId) {
        "skinTemp" -> String.format(Locale.US, "%+.1f °C", v)
        "steps" -> String.format(Locale.US, "%,d", v.roundToInt())
        "spo2" -> "${v.roundToInt()}%"
        else -> if (unit.isNotEmpty()) "${v.roundToInt()} $unit" else "${v.roundToInt()}"
    }

    fun isOutlier(v: Float): Boolean = when (metricId) {
        "hrv" -> v < 50f || v > 67f
        "rhr" -> v > 57f || v < 50f
        "spo2" -> v < 95f
        "skinTemp" -> abs(v) > 0.4f
        "steps" -> v < 5500f || v > 12000f
        else -> v < normalMin - 8f || v > normalMax + 8f
    }

    val rangeCount = when (selectedRange) {
        "Week" -> 7
        "Month" -> 30
        else -> 90
    }
    val slice = series90.takeLast(rangeCount)
    val latestPt = series90.last()
    val currentVal = extractRaw(latestPt)
    val selPt = series90.getOrElse(selectedDayIndex) { latestPt }
    val selVal = extractRaw(selPt)
    val selOutlier = isOutlier(selVal)

    // Back Header
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
        Text(
            text = title,
            color = HealthColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.size(48.dp))
    }

    // Hero Value Card
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Current reading", color = HealthColors.TextSecondary, fontSize = 13.sp)
                ContextChip(text = normalLabel, score = 75)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val heroStr = when (metricId) {
                        "skinTemp" -> String.format(Locale.US, "%+.1f", currentVal)
                        "steps" -> String.format(Locale.US, "%,d", currentVal.roundToInt())
                        else -> "${currentVal.roundToInt()}"
                    }
                    Text(
                        text = heroStr,
                        color = HealthColors.TextPrimary,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (unit.isNotEmpty()) {
                        Text(
                            text = unit,
                            color = HealthColors.TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                ContextChip(
                    text = if (spo2AlertActive && metricId == "spo2") "↓ 7% vs normal" else "↑ 8% vs normal",
                    score = if (spo2AlertActive && metricId == "spo2") 35 else 84
                )
            }
        }
    }

    // Segmented Toggle: Week / Month / 3M / Year
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = HealthColors.BgCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, HealthColors.BorderHairline)
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
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) HealthColors.AccentAmber else Color.Transparent)
                        .clickable { onSelectRange(r) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = r,
                        color = if (active) HealthColors.TextInverse else HealthColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }

    // 3-Day Rolling Average + Faint Raw Dots + Outliers Chart Card
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = selPt.label,
                        color = HealthColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = formatValue(selVal),
                        color = HealthColors.AccentAmber,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                ContextChip(
                    text = if (selOutlier) "Lowest in 2 weeks" else "Within normal",
                    score = if (selOutlier) 35 else 82
                )
            }

            val rawVals = slice.map { extractRaw(it) }
            val rollVals = slice.map { it.rolling3d[metricId] ?: extractRaw(it) }
            val minV = (rawVals + normalMin).minOrNull()!! - (if (metricId == "steps") 800f else 4f)
            val maxV = (rawVals + normalMax).maxOrNull()!! + (if (metricId == "steps") 800f else 4f)
            val span = (maxV - minV).coerceAtLeast(0.5f)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(172.dp)
                    .pointerInput(slice) {
                        detectTapGestures { offset ->
                            val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val idxInSlice = (frac * (slice.size - 1)).roundToInt().coerceIn(0, slice.lastIndex)
                            onSelectDayIndex(slice[idxInSlice].index)
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val padY = 14.dp.toPx()
                    val innerH = h - padY * 2

                    fun xFor(i: Int): Float =
                        if (slice.size <= 1) w / 2f else (i.toFloat() / (slice.size - 1).toFloat()) * w

                    fun yFor(v: Float): Float =
                        padY + innerH - ((v - minV) / span) * innerH

                    val bandTop = yFor(normalMax)
                    val bandBottom = yFor(normalMin)
                    val bandH = (bandBottom - bandTop).coerceAtLeast(8.dp.toPx())

                    // Shaded Normal Range Band
                    drawRoundRect(
                        color = HealthColors.ScoreGreen.copy(alpha = 0.12f),
                        topLeft = Offset(0f, bandTop),
                        size = Size(w, bandH),
                        cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                    )
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    drawLine(
                        color = HealthColors.ScoreGreen.copy(alpha = 0.30f),
                        start = Offset(0f, bandTop),
                        end = Offset(w, bandTop),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash
                    )
                    drawLine(
                        color = HealthColors.ScoreGreen.copy(alpha = 0.30f),
                        start = Offset(0f, bandTop + bandH),
                        end = Offset(w, bandTop + bandH),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash
                    )

                    // 3-Day Rolling Average Line
                    val path = Path()
                    rollVals.forEachIndexed { idx, rv ->
                        val px = xFor(idx)
                        val py = yFor(rv)
                        if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    drawPath(
                        path = path,
                        color = HealthColors.ScoreGreen,
                        style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // Faint Daily Raw Dots + Highlighted Outliers
                    slice.forEachIndexed { idx, pt ->
                        val v = rawVals[idx]
                        val cx = xFor(idx)
                        val cy = yFor(v)
                        val out = isOutlier(v)
                        val isSel = pt.index == selectedDayIndex

                        if (isSel) {
                            drawLine(
                                color = HealthColors.AccentAmber.copy(alpha = 0.45f),
                                start = Offset(cx, padY),
                                end = Offset(cx, h - padY),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = dash
                            )
                        }
                        if (out) {
                            drawCircle(
                                color = HealthColors.ScoreCoral.copy(alpha = 0.25f),
                                radius = 7.dp.toPx(),
                                center = Offset(cx, cy)
                            )
                        }
                        drawCircle(
                            color = when {
                                isSel -> HealthColors.AccentAmber
                                out -> HealthColors.ScoreCoral
                                else -> HealthColors.TextPrimary.copy(alpha = 0.32f)
                            },
                            radius = when {
                                isSel -> 5.5.dp.toPx()
                                out -> 4.5.dp.toPx()
                                else -> 3.dp.toPx()
                            },
                            center = Offset(cx, cy)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("━ 3-day average", color = HealthColors.TextSecondary, fontSize = 13.sp)
                Text("● Outlier day (tap dot)", color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
        }
    }

    // 30-day average comparison card
    val avg30 = series90.takeLast(30).map { extractRaw(it) }.average().toFloat()
    HealthCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("30-day average", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("vs 90-day normal", color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(formatValue(avg30), color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                ContextChip("Within normal", score = 80)
            }
        }
    }
}

// ============================================================================
// SLEEP DETAIL VIEW (Hypnogram + "You slept 7h 20m" + Stage Minutes + Score)
// ============================================================================
@Composable
private fun SleepDetailView(
    dayPoint: MetricSeriesPoint,
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

    HealthCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You slept", color = HealthColors.TextSecondary, fontSize = 13.sp)
                Text(
                    text = formatSleepHm(dayPoint.sleepMin),
                    color = HealthColors.TextPrimary,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold
                )
                ContextChip("↑ 20m vs normal", score = 82)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ScoreRingGauge(score = dayPoint.sleepScore, size = 68.dp, showRadialGlow = false)
                Text("${dayPoint.sleepScore} · ↑ 4 vs normal", color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
        }
    }

    // Hypnogram Card
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Sleep Stages", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("23:10 – 06:30", color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier.height(120.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Awake", color = HealthColors.TextSecondary, fontSize = 13.sp)
                    Text("REM", color = HealthColors.TextSecondary, fontSize = 13.sp)
                    Text("Light", color = HealthColors.TextSecondary, fontSize = 13.sp)
                    Text("Deep", color = HealthColors.TextSecondary, fontSize = 13.sp)
                }
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(120.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val yLevels = listOf(h * 0.14f, h * 0.40f, h * 0.66f, h * 0.90f)
                    val colors = listOf(
                        HealthColors.StageAwake,
                        HealthColors.StageRem,
                        HealthColors.StageLight,
                        HealthColors.StageDeep
                    )
                    yLevels.forEach { y ->
                        drawLine(
                            color = HealthColors.BorderHairline,
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    val segments = listOf(
                        Triple(0f, 12f, 0),
                        Triple(12f, 38f, 2),
                        Triple(50f, 50f, 3),
                        Triple(100f, 30f, 2),
                        Triple(130f, 25f, 1),
                        Triple(155f, 40f, 2),
                        Triple(195f, 45f, 3),
                        Triple(240f, 10f, 0),
                        Triple(250f, 45f, 2),
                        Triple(295f, 45f, 1),
                        Triple(340f, 52f, 2),
                        Triple(392f, 40f, 1),
                        Triple(432f, 30f, 2)
                    )
                    val total = 462f
                    segments.forEach { (start, dur, lvl) ->
                        val x = (start / total) * w
                        val bw = ((dur / total) * w).coerceAtLeast(3.dp.toPx())
                        val y = yLevels[lvl]
                        drawRoundRect(
                            color = colors[lvl],
                            topLeft = Offset(x, y - 5.dp.toPx()),
                            size = Size(bw, 10.dp.toPx()),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                        )
                    }
                }
            }
        }
    }

    // Stage Minutes Breakdown
    HealthCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Time in each stage", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            StageDurationRow("Deep", "1h 35m", 0.25f, "↑ 12m vs normal", HealthColors.StageDeep)
            StageDurationRow("REM", "1h 50m", 0.28f, "Within normal", HealthColors.StageRem)
            StageDurationRow("Light", "3h 55m", 0.52f, "Within normal", HealthColors.StageLight)
            StageDurationRow("Awake", "22m", 0.08f, "↓ 8m vs normal", HealthColors.StageAwake)
        }
    }
}

@Composable
private fun StageDurationRow(
    stage: String,
    duration: String,
    fraction: Float,
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
            modifier = Modifier.width(120.dp)
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

// ============================================================================
// TRENDS TAB
// ============================================================================
@Composable
private fun TrendsTabView(
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

// ============================================================================
// MORNING CHECK FLOW (Intro -> Measuring -> Result)
// ============================================================================
@Composable
private fun MorningCheckFlowView(
    step: CheckFlowStep,
    secondsRemaining: Int,
    liveHrBpm: Int,
    resultHrv: Int,
    onBeginMeasuring: () -> Unit,
    onSkipToResult: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit
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
                .clickable { onCancel() },
            contentAlignment = Alignment.Center
        ) {
            Text("✕", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = when (step) {
                CheckFlowStep.INTRO -> "Morning Check"
                CheckFlowStep.MEASURING -> "Measuring HRV"
                else -> "Check Complete"
            },
            color = HealthColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.size(48.dp))
    }

    when (step) {
        CheckFlowStep.INTRO -> {
            HealthCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ScoreRingGauge(score = 85, size = 112.dp, showRadialGlow = false)
                    Text("Lie still, 60 seconds", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Rest your arm flat and breathe normally.", color = HealthColors.TextSecondary, fontSize = 13.sp)
                    AmberActionButton(
                        text = "Begin 60-sec check",
                        onClick = onBeginMeasuring,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        CheckFlowStep.MEASURING -> {
            HealthCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(168.dp)) {
                        val progress = ((60 - secondsRemaining) / 60f).coerceIn(0.05f, 1f)
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val stroke = 9.dp.toPx()
                            drawArc(
                                color = Color(0x18FFFFFF),
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                style = Stroke(width = stroke)
                            )
                            drawArc(
                                color = HealthColors.AccentAmber,
                                startAngle = -90f,
                                sweepAngle = 360f * progress,
                                useCenter = false,
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$secondsRemaining", color = HealthColors.TextPrimary, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                            Text("Remaining (s)", color = HealthColors.TextSecondary, fontSize = 13.sp)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x0DFFFFFF))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Live HR · $liveHrBpm bpm",
                            color = HealthColors.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text("Within normal", color = HealthColors.ScoreGreen, fontSize = 13.sp)
                    }

                    SecondaryActionButton(
                        text = "Complete check now",
                        onClick = onSkipToResult,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        else -> {
            HealthCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScoreRingGauge(score = 88, size = 80.dp, showRadialGlow = false)
                    Text("Updated Recovery Score", color = HealthColors.TextSecondary, fontSize = 13.sp)
                    Text("88", color = HealthColors.TextPrimary, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    ContextChip("↑ 10% vs your normal", score = 88)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HealthCard(modifier = Modifier.weight(1f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("HRV", color = HealthColors.TextSecondary, fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("$resultHrv", color = HealthColors.TextPrimary, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                            Text("ms", color = HealthColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        ContextChip("↑ 10% vs normal", score = 88)
                    }
                }
                HealthCard(modifier = Modifier.weight(1f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Resting HR", color = HealthColors.TextSecondary, fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("51", color = HealthColors.TextPrimary, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                            Text("bpm", color = HealthColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        ContextChip("↓ 3 vs normal", score = 88)
                    }
                }
            }
            AmberActionButton(
                text = "Done",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ============================================================================
// COACH TAB (Max 3 lines per bubble + Suggested Chips + Claude Pro $0 Handoff)
// ============================================================================
@Composable
private fun CoachTabView(
    messages: List<ClaudeCoachRepository.ChatMessage>,
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
            Text("Grounded in your 90-day normal", color = HealthColors.TextSecondary, fontSize = 13.sp)
        }
        ContextChip("86 · ↑ 8% vs normal", score = 86)
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

// ============================================================================
// SETTINGS TAB
// ============================================================================
@Composable
private fun SettingsTabView(
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

// ============================================================================
// REUSABLE PRIMITIVES & CHARTS
// ============================================================================
@Composable
private fun HealthCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        color = HealthColors.BgCard,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, HealthColors.BorderHairline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
private fun AmberActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(HealthColors.AccentAmber)
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = HealthColors.TextInverse,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x12FFFFFF))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = HealthColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun ContextChip(
    text: String,
    score: Int
) {
    val (bg, fg) = when {
        score >= 70 -> Color(0x2434D399) to Color(0xFF6EE7B7)
        score >= 40 -> Color(0x26FBBF24) to Color(0xFFFDE68A)
        else -> Color(0x29FB7185) to Color(0xFFFDA4AF)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun ScoreRingGauge(
    score: Int,
    size: Dp,
    showRadialGlow: Boolean
) {
    val ringColor = HealthColors.forScore(score)
    Box(
        modifier = Modifier.size(if (showRadialGlow) size + 16.dp else size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            if (showRadialGlow) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(ringColor.copy(alpha = 0.25f), Color.Transparent)
                    ),
                    radius = this.size.minDimension * 0.72f
                )
            }
            val stroke = if (size >= 70.dp) 7.dp.toPx() else 6.dp.toPx()
            val pad = stroke / 2f + 2.dp.toPx()
            val arcSize = Size(this.size.width - pad * 2, this.size.height - pad * 2)
            drawArc(
                color = Color(0x14FFFFFF),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = arcSize,
                style = Stroke(width = stroke)
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = (score.coerceIn(0, 100) / 100f) * 360f,
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun SubMetricBarRow(
    title: String,
    contextText: String,
    score: Int,
    normalMin: Int,
    normalMax: Int,
    barColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(contextText, color = HealthColors.TextSecondary, fontSize = 13.sp)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Canvas(modifier = Modifier.size(width = 104.dp, height = 10.dp)) {
                val w = size.width
                val h = size.height
                drawRoundRect(
                    color = Color(0x14FFFFFF),
                    topLeft = Offset(0f, h * 0.2f),
                    size = Size(w, h * 0.6f),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
                val nLeft = (normalMin / 100f) * w
                val nWidth = ((normalMax - normalMin) / 100f) * w
                drawRoundRect(
                    color = Color(0x29FFFFFF),
                    topLeft = Offset(nLeft, 0f),
                    size = Size(nWidth, h),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                )
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(0f, h * 0.2f),
                    size = Size((score / 100f).coerceIn(0.08f, 1f) * w, h * 0.6f),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }
            Text("$score", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("›", color = HealthColors.TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SparklineVitalRow(
    title: String,
    unit: String,
    contextText: String,
    values: List<Float>,
    normalMin: Float,
    normalMax: Float,
    lineColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(unit, color = HealthColors.TextSecondary, fontSize = 13.sp)
            }
            Text(contextText, color = HealthColors.TextSecondary, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniSparklineCanvas(values = values, normalMin = normalMin, normalMax = normalMax, color = lineColor)
            Text("›", color = HealthColors.TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MiniSparklineCanvas(
    values: List<Float>,
    normalMin: Float,
    normalMax: Float,
    color: Color
) {
    Canvas(modifier = Modifier.size(width = 92.dp, height = 30.dp)) {
        if (values.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val minV = (values + normalMin).minOrNull()!! - 2f
        val maxV = (values + normalMax).maxOrNull()!! + 2f
        val span = (maxV - minV).coerceAtLeast(1f)

        fun xFor(i: Int) = if (values.size <= 1) w / 2f else (i.toFloat() / (values.size - 1).toFloat()) * (w - 8f) + 4f
        fun yFor(v: Float) = h - 4f - ((v - minV) / span) * (h - 8f)

        val topY = yFor(normalMax)
        val botY = yFor(normalMin)
        drawRoundRect(
            color = Color(0x12FFFFFF),
            topLeft = Offset(0f, topY),
            size = Size(w, (botY - topY).coerceAtLeast(4.dp.toPx())),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        )
        val path = Path()
        values.forEachIndexed { idx, v ->
            val px = xFor(idx)
            val py = yFor(v)
            if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawCircle(
            color = color,
            radius = 3.dp.toPx(),
            center = Offset(xFor(values.lastIndex), yFor(values.last()))
        )
    }
}

@Composable
private fun StepsNormalBandBar(
    steps: Int,
    normalMin: Int,
    normalMax: Int
) {
    val maxScale = 13000f
    val barColor = if (steps >= normalMin) HealthColors.ScoreGreen else HealthColors.ScoreAmber
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
    ) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = Color(0x14FFFFFF),
            topLeft = Offset(0f, h * 0.25f),
            size = Size(w, h * 0.5f),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
        val nLeft = (normalMin / maxScale) * w
        val nWidth = ((normalMax - normalMin) / maxScale) * w
        drawRoundRect(
            color = Color(0x26FFFFFF),
            topLeft = Offset(nLeft, 0f),
            size = Size(nWidth, h),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
        val fillW = ((steps / maxScale).coerceIn(0.05f, 1f)) * w
        drawRoundRect(
            color = barColor,
            topLeft = Offset(0f, h * 0.25f),
            size = Size(fillW, h * 0.5f),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
        drawCircle(
            color = HealthColors.TextPrimary,
            radius = 5.dp.toPx(),
            center = Offset(fillW, h / 2f)
        )
    }
}

@Composable
private fun BottomNavBar(
    activeTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
        color = Color(0xF50B1020),
        border = BorderStroke(1.dp, HealthColors.BorderHairline)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val items = listOf(
                Triple(MainTab.TODAY, "Today", "☀"),
                Triple(MainTab.TRENDS, "Trends", "∿"),
                Triple(MainTab.COACH, "Coach", "💬"),
                Triple(MainTab.SETTINGS, "Settings", "⚙")
            )
            items.forEach { (tab, label, _) ->
                val active = activeTab == tab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelectTab(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 24.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (active) HealthColors.AccentAmber else Color.Transparent)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = label,
                        color = if (active) HealthColors.AccentAmber else HealthColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomSheetOverlay(
    sheet: ActiveSheet,
    connectionState: String,
    batteryPct: Int,
    dayPoint: MetricSeriesPoint,
    series90: List<MetricSeriesPoint>,
    homelabUrl: String,
    onUpdateHomelabUrl: (String) -> Unit,
    onTriggerSync: () -> Unit,
    onSelectHistoryDay: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB8060914))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {},
            color = HealthColors.BgCard,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            border = BorderStroke(1.dp, Color(0x1FFFFFFF))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (sheet) {
                    ActiveSheet.SYNC -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Band Connection", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "✕",
                                color = HealthColors.TextSecondary,
                                fontSize = 16.sp,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable { onDismiss() }
                                    .wrapContentSize(Alignment.Center)
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(HealthColors.BgDeep)
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Last synced 6 min ago", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("Battery $batteryPct% · $connectionState", color = HealthColors.TextSecondary, fontSize = 13.sp)
                        }
                        AmberActionButton(
                            text = "Sync band",
                            onClick = onTriggerSync,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    ActiveSheet.VERDICT -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Why this verdict", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "✕",
                                color = HealthColors.TextSecondary,
                                fontSize = 16.sp,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable { onDismiss() }
                                    .wrapContentSize(Alignment.Center)
                            )
                        }
                        val reasons = listOf(
                            "HRV is ${dayPoint.hrvMs} ms" to "↑ 8% vs normal",
                            "Resting HR is ${dayPoint.rhrBpm} bpm" to "↓ 2 vs normal",
                            "You slept ${formatSleepHm(dayPoint.sleepMin)}" to "1h 35m deep"
                        )
                        reasons.forEach { (left, right) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(HealthColors.BgDeep)
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(left, color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                ContextChip(right, score = dayPoint.recoveryScore)
                            }
                        }
                        AmberActionButton(
                            text = "Got it",
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    ActiveSheet.CALENDAR -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Recent 14 Days", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text("Tap any day to open its Today view", color = HealthColors.TextSecondary, fontSize = 13.sp)
                            }
                            Text(
                                text = "✕",
                                color = HealthColors.TextSecondary,
                                fontSize = 16.sp,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable { onDismiss() }
                                    .wrapContentSize(Alignment.Center)
                            )
                        }
                        val last14 = series90.takeLast(14)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (row in 0..1) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    for (col in 0..6) {
                                        val pt = last14[row * 7 + col]
                                        val isOutlierDay = pt.index == 81
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(if (isOutlierDay) Color(0x28FBBF24) else Color(0x0DFFFFFF))
                                                .clickable { onSelectHistoryDay(pt.index) },
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text("${pt.date.dayOfMonth}", color = HealthColors.TextPrimary, fontSize = 13.sp)
                                            Spacer(Modifier.height(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(HealthColors.forScore(pt.dailyScore))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        AmberActionButton(
                            text = "Open ${series90[81].label} (56 · Below normal)",
                            onClick = { onSelectHistoryDay(81) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    ActiveSheet.SERVER_CONFIG -> {
                        var draftUrl by remember { mutableStateOf(homelabUrl) }
                        Text("Home Server URL", color = HealthColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = draftUrl,
                            onValueChange = { draftUrl = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        AmberActionButton(
                            text = "Save server URL",
                            onClick = {
                                onUpdateHomelabUrl(draftUrl.trim())
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    else -> {}
                }
            }
        }
    }
}
