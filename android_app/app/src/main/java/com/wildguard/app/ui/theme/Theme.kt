package com.wildguard.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val WildGreen = Color(0xFF2E7D32)
val WildGreenLight = Color(0xFF66BB6A)
val WildAmber = Color(0xFFFFA000)
val WildRed = Color(0xFFD32F2F)
val WildBlue = Color(0xFF1565C0)
val BackgroundDark = Color(0xFF0D1117)
val SurfaceDark = Color(0xFF161B22)
val OnSurfaceDark = Color(0xFFE6EDF3)

val NightRed = Color(0xFFCC2200)
val NightRedDim = Color(0xFF881500)
val SnowAmber = Color(0xFFFFD600)

private val DarkColorScheme = darkColorScheme(
    primary = WildGreen,
    secondary = WildAmber,
    tertiary = WildBlue,
    background = BackgroundDark,
    surface = SurfaceDark,
    onBackground = OnSurfaceDark,
    onSurface = OnSurfaceDark,
    error = WildRed,
)

private fun modeColorSchemeToMaterial(scheme: ModeColorScheme) = darkColorScheme(
    primary = scheme.accent,
    secondary = scheme.accent,
    tertiary = scheme.accent,
    background = scheme.background,
    surface = scheme.cardBackground,
    surfaceVariant = scheme.surface,
    onBackground = scheme.primaryText,
    onSurface = scheme.primaryText,
    onSurfaceVariant = scheme.secondaryText,
    error = scheme.error,
    onError = scheme.background,
    onPrimary = scheme.background,
    onSecondary = scheme.background,
    onTertiary = scheme.background,
)

@Composable
fun WildGuardTheme(
    visualMode: VisualMode = VisualMode.STANDARD,
    content: @Composable () -> Unit
) {
    val colorScheme = if (visualMode == VisualMode.STANDARD) {
        DarkColorScheme
    } else {
        modeColorSchemeToMaterial(VisualModeColors.schemeFor(visualMode))
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
