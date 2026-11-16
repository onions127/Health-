package com.jcwear.coach.ui

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jcwear.coach.ble.J2208BleManager
import com.jcwear.coach.data.CsvExporter
import com.jcwear.coach.data.LocalHealthDatabase
import com.jcwear.coach.domain.DayMetrics
import com.jcwear.coach.domain.SleepLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Loads dashboard data off the main thread; null until the first load finishes. */
class DashboardViewModel(
    private val database: LocalHealthDatabase,
    private val bleManager: J2208BleManager
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)
    private var last: List<DayMetrics>? = null
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<List<DayMetrics>?> =
        combine(refreshTick, bleManager.morningRmssdMs) { _, rmssd -> rmssd }
            // mapLatest drops a stale load when a newer refresh arrives
            .mapLatest { rmssd ->
                try {
                    withContext(Dispatchers.IO) { buildDashboardState(database, rmssd, LocalDate.now()) }
                        .also { last = it; _loadFailed.value = false }
                } catch (e: SQLiteException) {
                    Log.e("DashboardViewModel", "Load failed; keeping last good state", e)
                    _loadFailed.value = true
                    last
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        bleManager.onDataUpdated = { refresh() }
    }

    fun refresh() = refreshTick.update { it + 1 }

    /** S4: zip of every table + daily summary in [cacheDir]/exports; throws on failure (nothing else changes). */
    suspend fun exportAll(cacheDir: java.io.File, days: List<DayMetrics>): java.io.File = withContext(Dispatchers.IO) {
        CsvExporter(database, java.io.File(cacheDir, "exports")).export(days, LocalDate.now())
    }

    fun saveLabel(label: SleepLabel) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                database.putLabel(label)
            } catch (e: SQLiteException) {
                Log.e("DashboardViewModel", "Label save failed", e)
            }
            refresh()
        }
    }

    override fun onCleared() {
        bleManager.onDataUpdated = null
    }
}
