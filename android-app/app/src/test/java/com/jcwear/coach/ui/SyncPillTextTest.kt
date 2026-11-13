package com.jcwear.coach.ui

import com.jcwear.coach.ble.SyncFailureReason
import com.jcwear.coach.ble.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPillTextTest {
    private val now = 1_000_000_000_000L
    private val min = 60_000L

    @Test
    fun pillStates() {
        assertEquals("Syncing…" to false, syncPillText(SyncState.Syncing("Sleep"), now))
        assertEquals("Synced 2 min ago" to false, syncPillText(SyncState.Synced(now - 2 * min), now))
        assertEquals("Last sync 9 h ago" to true, syncPillText(SyncState.Synced(now - 9 * 60 * min), now))
        assertEquals("Band not found · Retry" to true, syncPillText(SyncState.Failed(SyncFailureReason.BAND_NOT_FOUND, now), now))
        assertEquals("Bluetooth off · Turn on" to true, syncPillText(SyncState.Failed(SyncFailureReason.BLUETOOTH_OFF, now), now))
        assertEquals("Allow Bluetooth" to true, syncPillText(SyncState.Failed(SyncFailureReason.PERMISSION_MISSING, now), now))
    }

    @Test
    fun agoTextUnits() {
        assertEquals("just now", agoText(now - 30_000, now))
        assertEquals("59 min ago", agoText(now - 59 * min, now))
        assertEquals("47 h ago", agoText(now - 47 * 60 * min, now))
        assertEquals("3 days ago", agoText(now - 3 * 24 * 60 * min, now))
    }
}
