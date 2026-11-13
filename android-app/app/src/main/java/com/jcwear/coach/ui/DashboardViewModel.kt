package com.jcwear.coach.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jcwear.coach.ble.J2208BleManager
import com.jcwear.coach.data.LocalHealthDatabase
import com.jcwear.coach.domain.SleepAndReadinessEngine.DailyReadinessReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Loads dashboard data off the main thread; null until the first load finishes. */
class DashboardViewModel(
    private val database: LocalHealthDatabase,
    private val bleManager: J2208BleManager
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<Pair<List<DailyReadinessReport>, List<MetricSeriesPoint>>?> =
        combine(refreshTick, bleManager.morningRmssdMs) { _, rmssd -> rmssd }
            // mapLatest drops a stale load when a newer refresh arrives
            .mapLatest { rmssd -> withContext(Dispatchers.IO) { buildDashboardState(database, rmssd, LocalDate.now()) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        bleManager.onDataUpdated = { refresh() }
    }

    fun refresh() = refreshTick.update { it + 1 }

    override fun onCleared() {
        bleManager.onDataUpdated = null
    }
}
