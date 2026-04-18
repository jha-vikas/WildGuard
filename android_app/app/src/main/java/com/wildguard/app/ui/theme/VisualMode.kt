package com.wildguard.app.ui.theme

import androidx.compose.ui.graphics.Color

enum class VisualMode {
    STANDARD,
    DAYLIGHT_CONTRAST,
    GLANCEABLE,
    NIGHT_RED,
    SNOW_GLARE,
    MARINE_WET
}

data class ModeColorScheme(
    val background: Color,
    val surface: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val accent: Color,
    val error: Color,
    val cardBackground: Color
)

object VisualModeColors {

    private val standard = ModeColorScheme(
        background = BackgroundDark,
        surface = SurfaceDark,
        primaryText = OnSurfaceDark,
        secondaryText = OnSurfaceDark.copy(alpha = 0.6f),
        accent = WildGreen,
        error = WildRed,
        cardBackground = SurfaceDark
    )

    private val daylightContrast = ModeColorScheme(
        background = Color.Black,
        surface = Color(0xFF0A0A0A),
        primaryText = Color.White,
        secondaryText = Color(0xFFCCCCCC),
        accent = Color(0xFF00E5FF),
        error = Color(0xFFFF1744),
        cardBackground = Color(0xFF0A0A0A)
    )

    private val glanceable = ModeColorScheme(
        background = Color.Black,
        surface = Color.Black,
        primaryText = Color.White,
        secondaryText = Color(0xFF999999),
        accent = Color(0xFF00E5FF),
        error = Color(0xFFFF1744),
        cardBackground = Color(0xFF111111)
    )

    private val nightRed = ModeColorScheme(
        background = Color.Black,
        surface = Color(0xFF0A0000),
        primaryText = Color(0xFFCC2200),
        secondaryText = Color(0xFF881500),
        accent = Color(0xFFCC2200),
        error = Color(0xFFCC2200),
        cardBackground = Color(0xFF0A0000)
    )

    private val snowGlare = ModeColorScheme(
        background = Color.Black,
        surface = Color(0xFF0A0A00),
        primaryText = Color(0xFFFFD600),
        secondaryText = Color(0xFFBBA000),
        accent = Color(0xFFFFD600),
        error = Color(0xFFFF6D00),
        cardBackground = Color(0xFF0A0A00)
    )

    private val marineWet = ModeColorScheme(
        background = Color.Black,
        surface = Color(0xFF0A0A0A),
        primaryText = Color.White,
        secondaryText = Color(0xFFBBBBBB),
        accent = Color(0xFF448AFF),
        error = Color(0xFFFF1744),
        cardBackground = Color(0xFF111111)
    )

    fun schemeFor(mode: VisualMode): ModeColorScheme = when (mode) {
        VisualMode.STANDARD -> standard
        VisualMode.DAYLIGHT_CONTRAST -> daylightContrast
        VisualMode.GLANCEABLE -> glanceable
        VisualMode.NIGHT_RED -> nightRed
        VisualMode.SNOW_GLARE -> snowGlare
        VisualMode.MARINE_WET -> marineWet
    }
}
