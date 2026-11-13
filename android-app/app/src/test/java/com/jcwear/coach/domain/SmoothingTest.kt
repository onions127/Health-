package com.jcwear.coach.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothingTest {
    @Test
    fun flatSeriesStaysFlat() {
        val segs = Smoothing.rolling(List(10) { 50.0 }, 3)
        assertEquals(1, segs.size)
        assertTrue(segs[0].all { it.second == 50.0 })
    }

    @Test
    fun singleSpikeIsSoftenedButVisible() {
        val v = List(10) { if (it == 5) 80.0 else 50.0 }
        val at5 = Smoothing.rolling(v, 3).flatten().first { it.first == 5 }.second
        assertEquals(60.0, at5, 1e-9)
    }

    @Test
    fun gapsMakeSeparateSegments() {
        val v = listOf(50.0, 52.0, null, 54.0, null, null, 51.0, 53.0)
        val segs = Smoothing.rolling(v, 3)
        assertEquals(listOf(listOf(1, 3), listOf(7)), segs.map { s -> s.map { it.first } })
    }

    @Test
    fun weekShowsNoLineAndYearUsesWeeklyMeans() {
        assertTrue(Smoothing.forRange(List(7) { 1.0 }, "Week").isEmpty())
        val v = List(14) { if (it < 7) 10.0 else 20.0 }
        assertEquals(listOf(3 to 10.0, 10 to 20.0), Smoothing.weekly(v).single())
    }

    @Test
    fun distanceUsesBandThenStrideFallback() {
        assertEquals(6.1, distanceKm(8420, 6.1, 180.0)!!, 1e-9)
        assertEquals(10_000 * 180.0 * 0.414 / 100_000.0, distanceKm(10_000, 0.0, 180.0)!!, 1e-9)
        assertNull(distanceKm(10_000, 0.0, null))
        assertNull(distanceKm(null, null, 180.0))
    }
}
