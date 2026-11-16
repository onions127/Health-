package com.jcwear.coach.ble

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jcwear.coach.data.AppSettings
import com.jcwear.coach.data.CleanupJob
import com.jcwear.coach.data.HomelabClient
import com.jcwear.coach.data.LocalHealthDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

        override suspend fun morningCheck60s(): Double? = 61.5
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

    private fun coordinator(band: FakeBand) = SyncCoordinator(
        band = band, settings = settings, homelab = HomelabClient(db, settings),
        cleanup = CleanupJob(db, settings, File(context.cacheDir, "b.db")),
        loadDays = { emptyList() }, onDataChanged = {}, retryDelayMs = 0
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
        assertEquals(61.5, check.await()!!, 0.0)
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
    fun failedMorningCheckReturnsNull() = runBlocking {
        val band = FakeBand().apply { connectFailures += SyncFailure(SyncFailureReason.BLUETOOTH_OFF) }
        assertNull(coordinator(band).morningCheck())
    }
}
