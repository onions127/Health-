package com.jcwear.coach.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/** R5b: the smoothed line, the change text, the log sheet's decisions, and save / delete / undo. */
class WeightTest {
    private val today = LocalDate.of(2026, 11, 17)

    /** 40 consecutive days ending today; [logs] maps days-ago to kg. */
    private fun days(logs: Map<Int, Double>) = (39 downTo 0).map { ago ->
        DayMetrics(today.minusDays(ago.toLong()), weightLog = logs[ago]?.let { WeightLog(it, ago.toLong()) })
    }

    @Test
    fun lineIsTrailingSevenDayMeanAndBridgesGaps() {
        assertEquals(listOf(39 to 80.0), weightLine(days(mapOf(0 to 80.0))))
        val three = weightLine(days(mapOf(4 to 81.0, 2 to 80.0, 0 to 79.0)))
        assertEquals(80.0, three.last().second, 1e-9)
        // Weekly logs: each window holds one log, yet the line still has a point per log (one connected segment).
        val weekly = weightLine(days(mapOf(21 to 82.0, 14 to 81.0, 7 to 80.5, 0 to 80.0)))
        assertEquals(listOf(82.0, 81.0, 80.5, 80.0), weekly.map { it.second })
    }

    @Test
    fun changeNamesItsAnchor() {
        val d = days(mapOf(30 to 81.0, 9 to 80.6, 3 to 80.2, 0 to 80.0))
        // latest line = mean(80.2, 80.0) = 80.1; anchor = newest log 7+ days before the latest = 9 days ago (80.6)
        assertEquals("-0.5 kg since 8 Nov", weightChangeText(d, UnitSystem.SI))
        assertEquals("-1.1 lb since 8 Nov", weightChangeText(d, UnitSystem.IMPERIAL))
        assertNull("no log 7 or more days earlier", weightChangeText(days(mapOf(3 to 80.2, 0 to 80.0)), UnitSystem.SI))
        assertEquals("Weight: 80.0 kg logged 2026-11-17 (-0.9 kg since 2026-10-18, 7-day averages).", weightCoachLine(d))
        assertNull(weightCoachLine(days(emptyMap())))
    }

    @Test
    fun bigJumpIsNeverAnOutlierAndHasNoChip() {
        val d = days((1..20).associateWith { 80.0 } + (0 to 95.0))
        assertNull(MetricInsights.baseline(Metrics.weight, d, today))
        assertNull(MetricInsights.extremeLabel(Metrics.weight, d, today))
        assertEquals("" to Status.NORMAL, MetricInsights.chip(Metrics.weight, d, today, UnitSystem.SI))
        assertEquals("209.4 lb", Metrics.weight.valueText(d.last(), UnitSystem.IMPERIAL))
    }

    @Test
    fun pickerMillisAreReadAsUtcWhateverTheZone() {
        val utcMidnight = 1_794_873_600_000L // 2026-11-17T00:00Z
        val saved = TimeZone.getDefault()
        try {
            for (zone in listOf("America/Los_Angeles", "Europe/Sofia")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(today, pickerMillisToLocalDate(utcMidnight))
            }
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun sheetValidationPrefillAndUpdate() {
        val d = days(mapOf(5 to 78.4, 0 to 78.0))
        val s = WeightLogSheetState(UnitSystem.SI, d, today, profileKg = 70.0)
        assertEquals("78.0", s.text) // today's entry
        assertEquals("Update", s.button)
        assertEquals("Replaces 78.0 kg", s.replacesLine)
        s.pickDate(today.minusDays(1))
        assertEquals("78.0", s.text) // no entry that day: the latest value
        assertEquals("Save", s.button)
        assertNull(s.replacesLine)
        s.pickDate(today.minusDays(5))
        assertEquals("78.4", s.text)
        s.text = "500"
        assertFalse(s.canSave)
        assertEquals("Enter 30.0 to 300.0 kg", s.rangeError)
        s.text = "78,9"
        assertTrue(s.canSave)
        assertNull(s.rangeError)
        s.pickDate(today.plusDays(3)) // outside the loaded series
        assertEquals(today, s.date)
        assertEquals("70.0", WeightLogSheetState(UnitSystem.SI, days(emptyMap()), today, 70.0).text)
        assertEquals("", WeightLogSheetState(UnitSystem.SI, days(emptyMap()), today, null).text)
    }

    @Test
    fun unitsAreCapturedWhenTheSheetOpens() {
        val store = Store()
        val logger = store.logger(days(emptyMap()))
        logger.units = UnitSystem.IMPERIAL
        logger.open()
        logger.sheet!!.text = "172.4"
        logger.units = UnitSystem.SI // toggled mid-draft
        assertEquals("Enter 66.1 to 661.4 lb", logger.sheet!!.apply { text = "20" }.rangeError)
        logger.sheet!!.text = "172.4"
        runBlocking { logger.save() }
        assertEquals(172.4 * 0.45359237, store.rows[today]!!.weightKg, 1e-9)
        assertEquals("Saved 172.4 lb for 17 Nov", store.messages.single())
    }

    @Test
    fun failedSaveKeepsTheTextAndTheSheet() {
        val store = Store().apply { failPut = true }
        val logger = store.logger(days(emptyMap()))
        logger.open(today.minusDays(14))
        logger.sheet!!.text = "78.4"
        runBlocking { logger.save() }
        assertEquals("Couldn't save 78.4 kg for 3 Nov", logger.sheet!!.saveError)
        assertEquals("78.4", logger.sheet!!.text)
        assertTrue(store.rows.isEmpty())
        assertTrue(store.messages.isEmpty())
    }

    @Test
    fun undoRestoresThePriorRowOrRemovesTheNewOne() {
        val prior = WeightLog(78.0, 0L)
        val store = Store().apply { rows[today] = prior; tapAction = true }
        val logger = store.logger(days(mapOf(0 to 78.0)))
        logger.open()
        logger.sheet!!.text = "79"
        runBlocking { logger.save() }
        assertEquals("the same logged_at is back", prior, store.rows[today])

        logger.open(today.minusDays(1))
        logger.sheet!!.text = "79"
        runBlocking { logger.save() }
        assertNull(store.rows[today.minusDays(1)])

        runBlocking { logger.remove(today) } // delete, then Undo
        assertEquals(prior, store.rows[today])
        assertEquals("Deleted 78.0 kg for 17 Nov", store.messages.last())
    }

    @Test
    fun failedUndoReloadsAndSaysSo() {
        val store = Store().apply { tapAction = true }
        val logger = store.logger(days(emptyMap()))
        logger.open()
        logger.sheet!!.text = "79"
        store.failDelete = true // the Undo of a new row is a delete
        runBlocking { logger.save() }
        assertEquals(listOf("Saved 79.0 kg for 17 Nov", "Couldn't undo"), store.messages)
        assertEquals(1, store.reloads)
        assertEquals(79.0, store.rows[today]!!.weightKg, 0.0) // the screen reloads to what the database holds
    }

    @Test
    fun failedDeleteKeepsThePointAndOffersRetry() {
        val store = Store().apply { rows[today] = WeightLog(78.0, 0L); failDelete = true }
        val logger = store.logger(days(mapOf(0 to 78.0)))
        runBlocking { logger.remove(today) }
        assertEquals(listOf("Couldn't delete"), store.messages)
        assertEquals(78.0, store.rows[today]!!.weightKg, 0.0)
    }

    /** An in-memory weight table with switchable failures; records every message shown. */
    private class Store {
        val rows = mutableMapOf<LocalDate, WeightLog>()
        val messages = mutableListOf<String>()
        var failPut = false
        var failDelete = false
        var tapAction = false
        var reloads = 0

        fun logger(days: List<DayMetrics>) = WeightLogger(
            put = { d, w -> if (failPut) false else { rows[d] = w; true } },
            delete = { d -> if (failDelete) false else rows.remove(d) != null },
            reload = { reloads++ },
            notify = { m, action -> messages += m; tapAction && action == "Undo" },
            now = { 1L }
        ).also { it.days = days }
    }
}
