package com.jcwear.coach.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcwear.coach.domain.DayMetrics
import com.jcwear.coach.domain.Units
import com.jcwear.coach.domain.WeightLogSheetState
import com.jcwear.coach.domain.WeightLogger
import com.jcwear.coach.domain.dayMonth
import com.jcwear.coach.domain.pickerMillisToLocalDate
import com.jcwear.coach.domain.weightChangeText
import com.jcwear.coach.domain.weightLine
import kotlinx.coroutines.launch
import java.time.ZoneOffset

/** R5: the one weight logger, provided by [WeightLogScope]. */
val LocalWeightLogger = staticCompositionLocalOf<WeightLogger?> { null }

/** Owns the weight log sheet and its snackbar, so MainDashboardScreen holds no weight state. */
@Composable
internal fun WeightLogScope(viewModel: DashboardViewModel, days: List<DayMetrics>, profileKg: Double?, content: @Composable () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val logger = remember {
        WeightLogger(
            put = viewModel::putWeight, delete = viewModel::deleteWeight, reload = { viewModel.refresh() },
            notify = { message, action ->
                snackbar.currentSnackbarData?.dismiss() // a new message replaces a waiting Undo
                snackbar.showSnackbar(message, action, duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short) ==
                    SnackbarResult.ActionPerformed
            }
        )
    }
    logger.days = days
    logger.units = LocalUnitSystem.current
    logger.profileKg = profileKg

    CompositionLocalProvider(LocalWeightLogger provides logger, content = content)
    logger.sheet?.let { WeightLogSheet(it, onSave = { scope.launch { logger.save() } }, onDismiss = logger::close) }
    Box(Modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.BottomCenter) { SnackbarHost(snackbar) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeightLogSheet(s: WeightLogSheetState, onSave: () -> Unit, onDismiss: () -> Unit) {
    var pickDate by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = HealthColors.BgCard,
        shape = HealthShapes.Sheet
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Log weight", color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
            val focus = remember { FocusRequester() }
            // Re-seeded (and selected) when the date changes the prefill.
            var field by remember(s.date) { mutableStateOf(TextFieldValue(s.text, TextRange(0, s.text.length))) }
            val error = s.rangeError
            OutlinedTextField(
                value = field,
                onValueChange = { field = it; s.text = it.text },
                label = { Text("Weight") },
                suffix = { Text(Units.weightUnit(s.units)) },
                singleLine = true,
                isError = error != null,
                supportingText = if (error != null) ({ Text(error) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (s.canSave) onSave() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
            SecondaryActionButton(text = "Date · ${s.dateLabel}", onClick = { pickDate = true })
            s.replacesLine?.let { Text(it, color = HealthColors.TextSecondary, fontSize = HealthType.Small) }
            s.saveError?.let {
                Text(
                    "$it · Retry", color = HealthColors.ScoreCoral, fontSize = HealthType.Small, fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onSave).wrapContentHeight()
                )
            }
            AmberActionButton(
                text = s.button,
                onClick = { if (s.canSave) onSave() },
                modifier = Modifier.fillMaxWidth().alpha(if (s.canSave) 1f else 0.4f).semantics { if (!s.canSave) disabled() }
            )
        }
    }
    if (pickDate) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = s.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                // Only days inside the loaded series.
                override fun isSelectableDate(utcTimeMillis: Long) =
                    pickerMillisToLocalDate(utcTimeMillis).let { !it.isBefore(s.firstDay) && !it.isAfter(s.today) }
                override fun isSelectableYear(year: Int) = year in s.firstDay.year..s.today.year
            }
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { s.pickDate(pickerMillisToLocalDate(it)) }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } }
        ) { DatePicker(picker) }
    }
}

@Composable
internal fun LogWeightButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = "Log weight" },
        contentAlignment = Alignment.Center
    ) { Text("+", color = HealthColors.AccentAmber, fontSize = HealthType.Body, fontWeight = FontWeight.SemiBold) }
}

/** Trends, last card: latest weight, the change against a week or more ago, and the smoothed line. */
@Composable
internal fun WeightTrendCard(days: List<DayMetrics>, onOpenDetail: () -> Unit) {
    val logger = LocalWeightLogger.current ?: return
    val units = LocalUnitSystem.current
    val logged = days.filter { it.weightLog != null }
    val latest = logged.lastOrNull()
    HealthCard(onClick = { if (latest == null) logger.open() else onOpenDetail() }) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Weight", color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium)
                if (latest == null) {
                    Text(
                        "Log your weight" + (logger.profileKg?.let { " · profile ${Units.weight(it, units)}" } ?: ""),
                        color = HealthColors.TextSecondary, fontSize = HealthType.Small
                    )
                } else {
                    Text(
                        "${Units.weight(latest.weightKg!!, units)} · ${dayMonth(latest.date)}",
                        color = HealthColors.TextPrimary, fontSize = HealthType.Body, fontWeight = FontWeight.Medium
                    )
                    (weightChangeText(days, units) ?: "Log again to see a change".takeIf { logged.size == 1 })
                        ?.let { Text(it, color = HealthColors.TextSecondary, fontSize = HealthType.Small) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (logged.size >= 2) MiniSparklineCanvas(
                    values = weightLine(days).map { it.second.toFloat() }, normalMin = null, normalMax = null,
                    color = HealthColors.TextSecondary, lastDotColor = HealthColors.TextSecondary
                )
                LogWeightButton { logger.open() }
            }
        }
    }
}

/** Profile: "Weight · 78.4 kg · 12 Nov" opens the log sheet. Without a log it shows the profile weight, undated. */
@Composable
internal fun WeightProfileRow() {
    val logger = LocalWeightLogger.current ?: return
    val units = LocalUnitSystem.current
    val latest = logger.days.lastOrNull { it.weightLog != null }
    val text = latest?.let { "Weight · ${Units.weight(it.weightKg!!, units)} · ${dayMonth(it.date)}" }
        ?: logger.profileKg?.let { "Weight · ${Units.weight(it, units)}" } ?: "Weight · not set"
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(HealthShapes.Small).clickable { logger.open() },
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = HealthColors.TextPrimary, fontSize = HealthType.Body)
        Text("›", color = HealthColors.TextSecondary, fontSize = HealthType.Body, fontWeight = FontWeight.SemiBold)
    }
}

/** Weight detail, selected-day row: "Edit · Delete" for a logged day, "No entry · Log" otherwise. */
@Composable
internal fun WeightDayActions(day: DayMetrics) {
    val logger = LocalWeightLogger.current ?: return
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (day.weightLog != null) {
            TextButton(onClick = { logger.open(day.date) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit") }
            TextButton(onClick = { scope.launch { logger.remove(day.date) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
        } else {
            Text("No entry", color = HealthColors.TextSecondary, fontSize = HealthType.Small)
            TextButton(onClick = { logger.open(day.date) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Log") }
        }
    }
}
