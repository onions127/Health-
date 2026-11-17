package com.jcwear.coach.ble

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jcwear.coach.data.AppSettings
import com.jcwear.coach.data.CleanupJob
import com.jcwear.coach.data.HomelabClient
import com.jcwear.coach.data.LocalHealthDatabase
import com.jcwear.coach.domain.MorningCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncCoordinatorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var settings: AppSettings
    private lateinit var db: LocalHealthDatabase

    /** Fake band: counts sessions, can hold a sync open, fail connects, and detects overlap. */
    private class FakeBand : BandLink {
        var connects = 0
        var disconnects = 0
        var open = 0
        var overlapped = false
        val connectFailures = ArrayDeque<SyncFailure>()
        var gate: CompletableDeferred<Unit>? = null
        var incomplete = false

        override suspend fun connect(timeoutMs: Long) {
            connects++
            connectFailures.removeFirstOrNull()?.let { throw it }
            if (++open > 1) overlapped = true
        }

        override suspend fun syncHistory(intervalMin: Int, onStep: (String) -> Unit): HistoryResult {
            onStep("Sleep")
            gate?.await()
            return HistoryResult(rows = 42, incomplete = incomplete, clockDriftSec = 30)
        }

        var reading: MorningReading? = MorningReading(61.5, estimated = false)
        var cancelTarget: Job? = null
        var cancelAfterReading: (suspend () -> Unit)? = null
        override suspend fun morningCheck60s(): MorningReading? {
            if (cancelAfterReading != null) {
                while (cancelTarget == null) kotlinx.coroutines.yield()
                cancelTarget!!.cancel() // Back pressed as the band finishes
            }
            return reading
        }
        override fun disconnect() {
            disconnects++
            if (open > 0) open--
        }
    }

    @Before
    fun setUp() {
        settings = AppSettings(context).apply { homelabBaseUrl = "" } // no network in tests
        db = LocalHealthDatabase(context)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("jcwear_local_health.db")
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private var dataChanged = 0
    private val saved = mutableListOf<MorningCheck>()
    private var saveWorks = true

    private fun coordinator(band: FakeBand) = SyncCoordinator(
        band = band, settings = settings, homelab = HomelabClient(db, settings),
        cleanup = CleanupJob(db, settings, File(context.cacheDir, "b.db")),
        loadDays = { emptyList() }, onDataChanged = { dataChanged++ },
        saveMorningCheck = { check -> saveWorks.also { if (it) saved += check } },
        now = { 1_000L }, retryDelayMs = 0
    )

    @Test
    fun twoTriggersShareOneBandSession() = runBlocking {
        val band = FakeBand().apply { gate = CompletableDeferred() }
        val c = coordinator(band)
        val first = c.sync("OPEN")
        val second = c.sync("PULL")
        assertSame(first, second)
        band.gate!!.complete(Unit)
        first.join()
        assertEquals(1, band.connects)
        assertEquals(1, band.disconnects)
        assertTrue(c.state.value is SyncState.Synced)
        assertEquals(30L, settings.bandClockDriftSec)
        assertEquals("OK", JSONArray(c.recentSyncsJson.value).getJSONObject(0).getString("result"))
    }

    @Test
    fun joinerOfAFailedRunSeesTheFailure() = runBlocking {
        val band = FakeBand().apply {
            gate = CompletableDeferred()
            incomplete = true
        }
        val c = coordinator(band)
        val open = c.sync("OPEN") // app-open run
        val background = c.sync("BACKGROUND") // the worker joins it
        band.gate!!.complete(Unit)
        assertTrue(background.await() is SyncState.Failed)
        assertSame(open, background)
        c.annotateLatest("morning", "skipped: sync_failed")
        assertEquals("skipped: sync_failed", JSONArray(c.recentSyncsJson.value).getJSONObject(0).getString("morning"))
    }

    @Test
    fun morningCheckWaitsForARunningSync() = runBlocking {
        val band = FakeBand().apply { gate = CompletableDeferred() }
        val c = coordinator(band)
        val sync = c.sync("OPEN")
        val check = async { c.morningCheck() }
        band.gate!!.complete(Unit)
        sync.join()
        assertEquals(61.5, (check.await() as MorningCheckResult.Saved).check.rmssdMs, 0.0)
        assertTrue("Sessions must never overlap", !band.overlapped)
    }

    @Test
    fun gattErrorRetriesOnceThenSucceeds() = runBlocking {
        val band = FakeBand().apply { connectFailures += SyncFailure(SyncFailureReason.GATT_ERROR, 133) }
        val c = coordinator(band)
        c.sync("OPEN").join()
        assertEquals(2, band.connects)
        assertTrue(c.state.value is SyncState.Synced)
    }

    @Test
    fun bandNotFoundIsNamedAndGattIsClosed() = runBlocking {
        val band = FakeBand().apply { connectFailures += SyncFailure(SyncFailureReason.BAND_NOT_FOUND) }
        val c = coordinator(band)
        c.sync("OPEN").join()
        assertEquals(SyncFailureReason.BAND_NOT_FOUND, (c.state.value as SyncState.Failed).reason)
        assertEquals(1, band.connects) // no retry for "not found"
        assertEquals(1, band.disconnects)
        assertEquals(0L, settings.lastSyncAt)
    }

    @Test
    fun timedOutHistoryIsIncompleteButKeepsTheSync() = runBlocking {
        val band = FakeBand().apply { incomplete = true }
        val c = coordinator(band)
        c.sync("OPEN").join()
        assertEquals(SyncFailureReason.HISTORY_TIMEOUT, (c.state.value as SyncState.Failed).reason)
        assertTrue(settings.lastSyncAt > 0)
    }

    @Test
    fun failedMorningCheckSavesNothing() = runBlocking {
        val band = FakeBand().apply { connectFailures += SyncFailure(SyncFailureReason.BLUETOOTH_OFF) }
        assertEquals(MorningCheckResult.MeasureFailed, coordinator(band).morningCheck())
        assertTrue(saved.isEmpty())
        assertEquals(0, dataChanged)
    }

    @Test
    fun morningCheckIsSavedWithTimeAndSourceThenRefreshes() = runBlocking {
        val result = coordinator(FakeBand()).morningCheck()
        assertEquals(MorningCheckResult.Saved(MorningCheck(61.5, 1_000L, MorningCheck.SOURCE_HRV)), result)
        assertEquals(1, saved.size)
        assertEquals(1, dataChanged)
    }

    @Test
    fun heartRateOnlyReadingIsStoredAsEstimate() = runBlocking {
        val band = FakeBand().apply { reading = MorningReading(48.0, estimated = true) }
        val result = coordinator(band).morningCheck() as MorningCheckResult.Saved
        assertEquals(MorningCheck.SOURCE_HR_ESTIMATE, result.check.source)
        assertTrue(result.check.estimated)
    }

    @Test
    fun outOfRangeReadingIsAFailedCheck() = runBlocking {
        for (v in listOf(14.9, 200.1, 0.0)) {
            val band = FakeBand().apply { reading = MorningReading(v, estimated = false) }
            assertEquals(MorningCheckResult.MeasureFailed, coordinator(band).morningCheck())
        }
        assertTrue(saved.isEmpty())
    }

    @Test
    fun failedSaveReportsItKeepsTheValueAndDoesNotRefresh() = runBlocking {
        saveWorks = false
        val c = coordinator(FakeBand())
        val failed = c.morningCheck() as MorningCheckResult.SaveFailed
        assertEquals(61.5, failed.check.rmssdMs, 0.0)
        assertEquals(0, dataChanged)
        // Retry save writes the same measurement again, without the band.
        saveWorks = true
        assertEquals(MorningCheckResult.Saved(failed.check), c.retrySaveMorningCheck(failed))
        assertEquals(listOf(failed.check), saved)
        assertEquals(1, dataChanged)
    }

    @Test
    fun redoWithTheSameValueStillRefreshes() = runBlocking {
        val c = coordinator(FakeBand())
        c.morningCheck()
        c.morningCheck()
        assertEquals(2, saved.size)
        assertEquals(2, dataChanged)
    }

    @Test
    fun cancellingRightAfterTheMeasurementStillSaves() = runBlocking {
        val band = FakeBand().apply { cancelAfterReading = {} }
        val job = launch(Dispatchers.Default) { coordinator(band).morningCheck() }
        band.cancelTarget = job
        job.join()
        assertTrue(job.isCancelled)
        assertEquals("the finished measurement must be stored", 1, saved.size)
        assertEquals(1, dataChanged)
    }
}
