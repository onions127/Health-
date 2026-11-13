package com.jcwear.coach.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.provider.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.jcwear.coach.ble.SyncCoordinator
import com.jcwear.coach.ble.SyncFailureReason
import com.jcwear.coach.ble.SyncState
import org.json.JSONArray
import kotlin.math.abs
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcwear.coach.ai.ClaudeCoachRepository
import com.jcwear.coach.ble.J2208BleManager
import com.jcwear.coach.data.AppSettings
import com.jcwear.coach.domain.MetricInsights
import com.jcwear.coach.domain.Metrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardScreen(
    viewModel: DashboardViewModel,
    settings: AppSettings,
    bleManager: J2208BleManager,
    syncCoordinator: SyncCoordinator,
    coachRepo: ClaudeCoachRepository,
    cleanupPending: Boolean,
    onRequestBlePermissions: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val connState by bleManager.connectionState.collectAsState()
    val batteryPct by bleManager.batteryPct.collectAsState()
    val liveFrames by bleManager.liveFrames.collectAsState()
    val loadFailed by viewModel.loadFailed.collectAsState()
    val syncState by syncCoordinator.state.collectAsState()
    val recentSyncsJson by syncCoordinator.recentSyncsJson.collectAsState()
    // Re-render "N min ago" texts every 30 s.
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    val syncing = syncState is SyncState.Connecting || syncState is SyncState.Syncing
    val syncPill = syncPillText(syncState, nowMs)
    val pullState = rememberPullToRefreshState()
    if (pullState.isRefreshing) {
        LaunchedEffect(Unit) {
            syncCoordinator.sync("PULL").join()
            pullState.endRefresh()
        }
    }

    // Navigation & UI state
    var currentTab by remember { mutableStateOf(MainTab.TODAY) }
    var activeDetailMetric by remember { mutableStateOf<String?>(null) }
    var checkStep by remember { mutableStateOf(CheckFlowStep.NONE) }
    var checkCountdown by remember { mutableStateOf(60) }
    var morningCheckCompleted by remember { mutableStateOf(false) }
    var checkResultHrv by remember { mutableStateOf<Int?>(null) }

    var dailyExpanded by remember { mutableStateOf(false) }
    var recoveryExpanded by remember { mutableStateOf(false) }
    var viewingHistoryDayIndex by remember { mutableStateOf<Int?>(null) }
    var activeSheet by remember { mutableStateOf(ActiveSheet.NONE) }

    // Detail chart state
    var selectedRange by remember { mutableStateOf("Month") }
    var selectedChartDayIdx by remember { mutableStateOf(Int.MAX_VALUE) }

    // Settings state (persisted in AppSettings)
    var homelabUrl by remember { mutableStateOf(settings.homelabBaseUrl) }
    var homelabToken by remember { mutableStateOf(settings.homelabApiToken) }
    var anthropicKey by remember { mutableStateOf(settings.anthropicApiKey) }
    var useMetricUnits by remember { mutableStateOf(settings.useMetricUnits) }

    var chatMessages by remember { mutableStateOf(listOf<ClaudeCoachRepository.ChatMessage>()) }
    var customCoachInput by remember { mutableStateOf("") }

    // Countdown while measuring; the BLE callback moves the flow to the result.
    LaunchedEffect(checkStep) {
        if (checkStep == CheckFlowStep.MEASURING) {
            checkCountdown = 60
            checkResultHrv = null
            val ticker = launch {
                while (checkCountdown > 0) {
                    delay(1000)
                    checkCountdown--
                }
            }
            checkResultHrv = syncCoordinator.morningCheck()?.roundToInt()
            ticker.cancel()
            if (checkStep == CheckFlowStep.MEASURING) checkStep = CheckFlowStep.RESULT
        }
    }

    // DS3 loading state: a quiet line, no full-screen spinner
    val days = viewModel.state.collectAsState().value
    if (days == null) {
        Box(
            Modifier
                .fillMaxSize()
                .background(HealthColors.BgDeep)
                .padding(16.dp)
        ) {
            Text("Loading your data…", color = HealthColors.TextSecondary, fontSize = 13.sp)
        }
        return
    }

    val today = days.last()
    val activeDay = viewingHistoryDayIndex?.let { days.getOrNull(it) } ?: today
    // Coach and backup get real days only, newest first.
    val recentReal = days.filter { it.hasData }.reversed().take(30)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(HealthColors.BgDeep, HealthColors.BgNavy)))
            .nestedScroll(pullState.nestedScrollConnection)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (loadFailed) {
                HealthCard(onClick = { viewModel.refresh() }) {
                    Text("Couldn't load data · Retry", color = HealthColors.ScoreCoral, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            when {
                checkStep != CheckFlowStep.NONE -> {
                    MorningCheckFlowView(
                        step = checkStep,
                        secondsRemaining = checkCountdown,
                        liveHrBpm = liveFrames.lastOrNull()?.hrBpm?.takeIf { it > 0 },
                        resultHrv = checkResultHrv,
                        today = today,
                        days = days,
                        onBeginMeasuring = {
                            onRequestBlePermissions()
                            checkStep = CheckFlowStep.MEASURING
                        },
                        onDone = {
                            morningCheckCompleted = checkResultHrv != null
                            checkStep = CheckFlowStep.NONE
                        },
                        onCancel = { checkStep = CheckFlowStep.NONE }
                    )
                }

                activeDetailMetric != null -> {
                    val metricId = activeDetailMetric!!
                    if (metricId == "sleep") {
                        SleepDetailView(day = activeDay, days = days, onBack = { activeDetailMetric = null })
                    } else {
                        MetricDetailView(
                            metricId = metricId,
                            days = days,
                            selectedRange = selectedRange,
                            selectedDayIndex = selectedChartDayIdx,
                            onSelectRange = { selectedRange = it },
                            onSelectDayIndex = { selectedChartDayIdx = it },
                            onBack = { activeDetailMetric = null }
                        )
                    }
                }

                currentTab == MainTab.TODAY -> {
                    TodayTabView(
                        day = activeDay,
                        days = days,
                        isHistoricalView = viewingHistoryDayIndex != null,
                        morningCheckDone = morningCheckCompleted,
                        dailyExpanded = dailyExpanded,
                        recoveryExpanded = recoveryExpanded,
                        syncPill = syncPill,
                        syncing = syncing,
                        batteryPct = batteryPct,
                        onSyncPillClick = {
                            when ((syncState as? SyncState.Failed)?.reason) {
                                SyncFailureReason.PERMISSION_MISSING -> onRequestBlePermissions() // sync starts once granted
                                SyncFailureReason.BLUETOOTH_OFF -> context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                                else -> syncCoordinator.sync("RETRY")
                            }
                        },
                        onExitHistory = { viewingHistoryDayIndex = null },
                        onStartMorningCheck = { checkStep = CheckFlowStep.INTRO },
                        onOpenVerdictSheet = { activeSheet = ActiveSheet.VERDICT },
                        onToggleDailyExpand = { dailyExpanded = !dailyExpanded },
                        onToggleRecoveryExpand = { recoveryExpanded = !recoveryExpanded },
                        onOpenMetricDetail = { metricId ->
                            selectedChartDayIdx = viewingHistoryDayIndex ?: days.lastIndex
                            activeDetailMetric = metricId
                        }
                    )
                }

                currentTab == MainTab.TRENDS -> {
                    TrendsTabView(
                        days = days,
                        onOpenCalendar = { activeSheet = ActiveSheet.CALENDAR },
                        onOpenMetricDetail = { metricId ->
                            selectedChartDayIdx = days.lastIndex
                            activeDetailMetric = metricId
                        }
                    )
                }

                currentTab == MainTab.COACH -> {
                    CoachTabView(
                        messages = chatMessages,
                        recoveryChip = today.recovery?.let { r ->
                            MetricInsights.chip(Metrics.recovery, days, today.date).let { (t, st) -> "Recovery $r · $t" to st }
                        },
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
                                    recentReports = recentReal,
                                    homelabBaseUrl = homelabUrl,
                                    apiToken = homelabToken,
                                    anthropicApiKey = anthropicKey
                                )
                                chatMessages = (updated + ClaudeCoachRepository.ChatMessage("assistant", reply)).takeLast(4)
                            }
                        },
                        onAskInClaudeProApp = { question ->
                            val prompt = coachRepo.buildClaudeProPrompt(
                                userQuestion = question.ifBlank { "Based on my last 14 days, how should I train today?" },
                                recentReports = recentReal
                            )
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Health++ Claude Pro Brief", prompt))
                            Toast.makeText(context, "Copied 14-day brief! Paste in Claude Pro.", Toast.LENGTH_LONG).show()
                            launchClaudeProAppOrWeb(context)
                        }
                    )
                }

                currentTab == MainTab.SETTINGS -> {
                    val drift = settings.bandClockDriftSec
                    SettingsTabView(
                        connectionState = connState,
                        syncStatusText = syncPill.first,
                        batteryPct = batteryPct,
                        bandNote = drift?.takeIf { abs(it) > 120 }?.let { "Band clock is ${abs(it) / 60} min off" },
                        lastBackupText = remember(recentSyncsJson, homelabUrl, nowMs) { // re-read after each recorded event
                            when {
                                homelabUrl.isBlank() -> "Set up backup"
                                settings.lastBackupError.isNotBlank() -> "Backup failed: ${settings.lastBackupError} · Retry"
                                settings.lastBackupAt > 0 -> "Backed up ${agoText(settings.lastBackupAt, nowMs)}"
                                else -> "Not backed up yet"
                            }
                        },
                        cleanupPending = cleanupPending,
                        recentSyncs = remember(recentSyncsJson) {
                            val arr = JSONArray(recentSyncsJson)
                            (0 until arr.length()).map { recentSyncLine(arr.getJSONObject(it)) }
                        },
                        onForgetBand = { bleManager.forgetBand() },
                        homelabUrl = homelabUrl,
                        useMetricUnits = useMetricUnits,
                        historyDays = days.count { it.hasData },
                        onOpenSyncSheet = { activeSheet = ActiveSheet.SYNC },
                        onBackupNow = {
                            if (homelabUrl.isBlank()) activeSheet = ActiveSheet.SERVER_CONFIG
                            else syncCoordinator.backupNow()
                        },
                        onEditServerUrl = { activeSheet = ActiveSheet.SERVER_CONFIG },
                        onToggleUnits = {
                            useMetricUnits = !useMetricUnits
                            settings.useMetricUnits = useMetricUnits
                        }
                    )
                }
            }
        }

        PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))

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

        if (activeSheet != ActiveSheet.NONE) {
            BottomSheetOverlay(
                sheet = activeSheet,
                connectionState = connState,
                syncStatusText = syncPill.first,
                batteryPct = batteryPct,
                day = activeDay,
                days = days,
                homelabUrl = homelabUrl,
                homelabToken = homelabToken,
                anthropicKey = anthropicKey,
                onSaveServerConfig = { url, token, key ->
                    settings.homelabBaseUrl = url
                    settings.homelabApiToken = token
                    settings.anthropicApiKey = key
                    homelabUrl = settings.homelabBaseUrl
                    homelabToken = settings.homelabApiToken
                    anthropicKey = settings.anthropicApiKey
                },
                onTriggerSync = {
                    onRequestBlePermissions()
                    syncCoordinator.sync("MANUAL")
                    activeSheet = ActiveSheet.NONE
                },
                onSelectHistoryDay = { idx ->
                    viewingHistoryDayIndex = idx.takeIf { it != days.lastIndex }
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
