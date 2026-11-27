package com.jcwear.coach.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R2: typed text, labels and errors are readable on every surface, and no Material default colour is left. */
class ThemeContrastTest {
    private val s = HealthColorScheme

    /** Every background a text field, dialog, sheet or picker can sit on. */
    private val surfaces = mapOf(
        "background" to s.background, "surface" to s.surface, "surfaceVariant" to s.surfaceVariant,
        "surfaceContainerLowest" to s.surfaceContainerLowest, "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest, "surfaceBright" to s.surfaceBright, "surfaceDim" to s.surfaceDim
    )

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertContrast(min: Double, fgName: String, fg: Color) = surfaces.forEach { (name, bg) ->
        val c = contrast(fg, bg)
        assertTrue("$fgName on $name is %.2f, needs $min".format(c), c >= min)
    }

    @Test
    fun textIsReadableOnEverySurface() {
        assertContrast(4.5, "onSurface (typed text)", s.onSurface)
        assertContrast(4.5, "onSurfaceVariant (labels, placeholders)", s.onSurfaceVariant)
        assertContrast(4.5, "error (error text)", s.error)
        assertContrast(4.5, "primary (focused label, cursor, text buttons)", s.primary)
    }

    @Test
    fun fieldOutlineMeetsNonTextContrast() {
        assertContrast(3.0, "outline", s.outline)
        assertEquals("outline must be opaque so the number above is what is drawn", 1f, s.outline.alpha)
    }

    @Test
    fun textOnFilledControlsIsReadable() {
        assertTrue(contrast(s.onPrimary, s.primary) >= 4.5)
        assertTrue(contrast(s.onError, s.error) >= 4.5)
        assertTrue(contrast(s.inverseOnSurface, s.inverseSurface) >= 4.5)
        assertTrue(contrast(s.onPrimaryContainer, s.primaryContainer) >= 4.5)
        assertTrue(contrast(s.onSecondaryContainer, s.secondaryContainer) >= 4.5)
        assertTrue(contrast(s.onTertiaryContainer, s.tertiaryContainer) >= 4.5)
        assertTrue(contrast(s.onErrorContainer, s.errorContainer) >= 4.5)
    }

    @Test
    fun noMaterialDefaultColourIsLeft() {
        val scheme = roles(s)
        for ((which, defaults) in listOf("dark" to roles(darkColorScheme()), "light" to roles(lightColorScheme()))) {
            scheme.forEach { (role, colour) ->
                if (role == "scrim") return@forEach // black in every scheme
                assertNotEquals("$role is still the Material $which default", defaults.getValue(role), colour)
            }
        }
        assertEquals(Color.Transparent, s.surfaceTint)
    }

    private fun roles(c: androidx.compose.material3.ColorScheme) = mapOf(
        "primary" to c.primary, "onPrimary" to c.onPrimary, "primaryContainer" to c.primaryContainer,
        "onPrimaryContainer" to c.onPrimaryContainer, "inversePrimary" to c.inversePrimary,
        "secondary" to c.secondary, "onSecondary" to c.onSecondary, "secondaryContainer" to c.secondaryContainer,
        "onSecondaryContainer" to c.onSecondaryContainer,
        "tertiary" to c.tertiary, "onTertiary" to c.onTertiary, "tertiaryContainer" to c.tertiaryContainer,
        "onTertiaryContainer" to c.onTertiaryContainer,
        "background" to c.background, "onBackground" to c.onBackground, "surface" to c.surface, "onSurface" to c.onSurface,
        "surfaceVariant" to c.surfaceVariant, "onSurfaceVariant" to c.onSurfaceVariant, "surfaceTint" to c.surfaceTint,
        "inverseSurface" to c.inverseSurface, "inverseOnSurface" to c.inverseOnSurface,
        "error" to c.error, "onError" to c.onError, "errorContainer" to c.errorContainer, "onErrorContainer" to c.onErrorContainer,
        "outline" to c.outline, "outlineVariant" to c.outlineVariant, "scrim" to c.scrim,
        "surfaceBright" to c.surfaceBright, "surfaceDim" to c.surfaceDim,
        "surfaceContainerLowest" to c.surfaceContainerLowest, "surfaceContainerLow" to c.surfaceContainerLow,
        "surfaceContainer" to c.surfaceContainer, "surfaceContainerHigh" to c.surfaceContainerHigh,
        "surfaceContainerHighest" to c.surfaceContainerHighest
    )

    /** R6.1: colour literals live in HealthColors only (Gradle runs unit tests from the module dir). */
    @Test
    fun noRawColourOutsideTokens() {
        val raw = java.io.File("src/main/java/com/jcwear/coach/ui").walkTopDown().filter { it.isFile }
            .filter { it.name != "DashboardModels.kt" && "Color(0x" in it.readText() }.map { it.name }.toList()
        assertEquals(emptyList<String>(), raw)
    }
}
