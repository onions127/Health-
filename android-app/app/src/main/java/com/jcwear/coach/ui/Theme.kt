package com.jcwear.coach.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import com.jcwear.coach.domain.UnitSystem
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * R2: the one dark scheme behind every Material component (text fields, dialogs, pickers, switches).
 * Every role is set from [HealthColors] so no Material default (light scheme or baseline purple) shows through.
 */
val HealthColorScheme: ColorScheme = darkColorScheme(
    primary = HealthColors.AccentAmber,
    onPrimary = HealthColors.TextInverse,
    primaryContainer = HealthColors.BgCardElevated,
    onPrimaryContainer = HealthColors.TextPrimary,
    inversePrimary = HealthColors.BgCardElevated,
    secondary = HealthColors.AccentAmber,
    onSecondary = HealthColors.TextInverse,
    secondaryContainer = HealthColors.BgCardElevated,
    onSecondaryContainer = HealthColors.TextPrimary,
    tertiary = HealthColors.ScoreGreen,
    onTertiary = HealthColors.TextInverse,
    tertiaryContainer = HealthColors.BgCardElevated,
    onTertiaryContainer = HealthColors.TextPrimary,
    background = HealthColors.BgDeep,
    onBackground = HealthColors.TextPrimary,
    surface = HealthColors.BgCard,
    onSurface = HealthColors.TextPrimary,
    surfaceVariant = HealthColors.BgCardElevated,
    onSurfaceVariant = HealthColors.TextSecondary,
    surfaceTint = Color.Transparent, // no tonal-elevation tint on dialogs and sheets
    inverseSurface = HealthColors.TextPrimary,
    inverseOnSurface = HealthColors.TextInverse,
    error = HealthColors.ScoreCoral,
    onError = HealthColors.TextInverse,
    errorContainer = HealthColors.AlertBg,
    onErrorContainer = HealthColors.TextPrimary,
    // Opaque, so the 3:1 non-text contrast holds on every surface (ThemeContrastTest).
    outline = HealthColors.TextSecondary.copy(alpha = 0.6f).compositeOver(HealthColors.BgCardElevated),
    outlineVariant = HealthColors.TextSecondary.copy(alpha = 0.25f).compositeOver(HealthColors.BgCard),
    scrim = Color.Black,
    surfaceBright = HealthColors.BgCardElevated,
    surfaceDim = HealthColors.BgDeep,
    surfaceContainerLowest = HealthColors.BgDeep,
    surfaceContainerLow = HealthColors.BgDeep,
    surfaceContainer = HealthColors.BgCard,
    surfaceContainerHigh = HealthColors.BgCardElevated,
    surfaceContainerHighest = HealthColors.BgCardElevated
)

/** R6: one radius scale (DESIGN.md). */
object HealthShapes {
    val Panel = RoundedCornerShape(8.dp) // cards, buttons, fields, rows
    val Small = RoundedCornerShape(4.dp) // bars, tags, focus rows
    val Sheet = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
}

/** R6: the four text sizes (DESIGN.md). */
object HealthType {
    val Caption = 11.sp
    val Small = 13.sp
    val Body = 16.sp
    val Display = 40.sp
}

/** U: the unit system from Profile, provided once by MainDashboardScreen. */
val LocalUnitSystem = compositionLocalOf { UnitSystem.SI }

@Composable
fun HealthTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HealthColorScheme) {
        // Text with no explicit colour outside a Surface would otherwise be black.
        CompositionLocalProvider(LocalContentColor provides HealthColors.TextPrimary, content = content)
    }
}
