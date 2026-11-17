package com.jcwear.coach.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileTest {
    @Test
    fun heightAndWeightValidateInEitherUnit() {
        assertEquals(180.0, parseHeightCm(" 180 ", metric = true)!!, 1e-9)
        assertEquals(177.8, parseHeightCm("70", metric = false)!!, 1e-9)
        assertNull(parseHeightCm("70", metric = true)) // 70 cm is out of range
        assertNull(parseHeightCm("abc", metric = true))
        assertNull(parseHeightCm("", metric = true))
        assertEquals(80.0, parseWeightKg("80", metric = true)!!, 1e-9)
        assertEquals(79.83, parseWeightKg("176", metric = false)!!, 0.01)
        assertNull(parseWeightKg("500", metric = true))
    }

    @Test
    fun caloriesPreferBandThenEstimate() {
        assertEquals(320.0 to false, caloriesKcal(8000, 320.0, 80.0))
        assertEquals(320.0 to true, caloriesKcal(8000, 0.0, 80.0))
        assertNull(caloriesKcal(8000, 0.0, null))
    }
}
