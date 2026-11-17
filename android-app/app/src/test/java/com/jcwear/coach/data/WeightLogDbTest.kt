package com.jcwear.coach.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jcwear.coach.ble.J2208Protocol.StepEpochRecord
import com.jcwear.coach.domain.DailySeriesRepository
import com.jcwear.coach.domain.WeightLog
import com.jcwear.coach.domain.calorieWeightKg
import com.jcwear.coach.domain.parseWeightKg
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.TreeMap

/** R5a: one weight per day in kg, and the weight each day's estimated calories use. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeightLogDbTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = LocalHealthDatabase(context)
    private val day = LocalDate.of(2026, 11, 17)

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("jcwear_local_health.db")
    }

    @Test
    fun insertReplaceDelete() {
        assertTrue(db.putWeight(day, WeightLog(78.44, 1L)))
        assertTrue(db.putWeight(day, WeightLog(78.0, 2L)))
        assertEquals(mapOf(day to WeightLog(78.0, 2L)), db.getAllWeightLogs())
        assertTrue(db.deleteWeight(day))
        assertFalse("nothing left to delete", db.deleteWeight(day))
        assertTrue(db.getAllWeightLogs().isEmpty())
    }

    @Test
    fun closedDatabaseReportsFailure() {
        db.close()
        context.deleteDatabase("jcwear_local_health.db")
        val broken = LocalHealthDatabase(context)
        broken.writableDatabase.execSQL("DROP TABLE weight_logs")
        assertFalse(broken.putWeight(day, WeightLog(78.0, 1L)))
        assertFalse(broken.deleteWeight(day))
        broken.close()
    }

    @Test
    fun calorieWeightRule() {
        val logs = TreeMap(mapOf(day.minusDays(10) to WeightLog(80.0, 1L), day.minusDays(2) to WeightLog(78.0, 2L)))
        assertEquals(78.0, calorieWeightKg(day, logs, 70.0)!!, 0.0)                 // newest on or before
        assertEquals(80.0, calorieWeightKg(day.minusDays(5), logs, 70.0)!!, 0.0)    // older day keeps the older log
        assertEquals(80.0, calorieWeightKg(day.minusDays(30), logs, 70.0)!!, 0.0)   // before any log: the earliest
        assertEquals(70.0, calorieWeightKg(day, TreeMap(), 70.0)!!, 0.0)            // no logs: profile
        assertNull(calorieWeightKg(day, TreeMap(), null))
    }

    @Test
    fun seriesUsesLogForCaloriesAndShowsItOnlyOnItsDay() {
        db.upsertStepRecords(listOf(StepEpochRecord("${day}T10:00:00", 8000, 0.0, 0.0, emptyList(), "t")))
        db.putWeight(day.minusDays(1), WeightLog(80.0, 1L))
        val days = DailySeriesRepository(db).load(day)
        val today = days.last()
        assertEquals(320.0, today.caloriesKcal!!, 1e-9)
        assertTrue(today.caloriesEstimated)
        assertNull("no carry-forward", today.weightKg)
        assertEquals(80.0, days[days.size - 2].weightKg!!, 0.0)
    }

    @Test
    fun parsing() {
        assertEquals(78.4, parseWeightKg("78,4", metric = true)!!, 1e-9)
        assertEquals(66.1 * 0.45359237, parseWeightKg("66.1", metric = false)!!, 1e-9)
        assertNull(parseWeightKg("66.0", metric = false))
        assertNull(parseWeightKg("29.9", metric = true))
        val kg = parseWeightKg("172.4", metric = false)!!
        assertEquals("172.4 lb", com.jcwear.coach.domain.Units.weight(kg, com.jcwear.coach.domain.UnitSystem.IMPERIAL))
        assertEquals("78.4 kg", com.jcwear.coach.domain.Units.weight(78.4, com.jcwear.coach.domain.UnitSystem.SI))
    }
}
