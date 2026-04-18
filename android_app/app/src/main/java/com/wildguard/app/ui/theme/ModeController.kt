package com.wildguard.app.ui.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ActivityMode {
    IDLE,
    ACTIVE_HIKING,
    ACTIVE_MARINE
}

enum class SurfaceSetting {
    LAND,
    SNOW,
    WATER
}

class ModeController {

    private val _currentMode = MutableStateFlow(VisualMode.STANDARD)
    val currentMode: StateFlow<VisualMode> = _currentMode.asStateFlow()

    private val _currentColorScheme = MutableStateFlow(VisualModeColors.schemeFor(VisualMode.STANDARD))
    val currentColorScheme: StateFlow<ModeColorScheme> = _currentColorScheme.asStateFlow()

    private var userOverride: VisualMode? = null
    private var sunAltitude: Double = 45.0
    private var lightLux: Float = 500f
    private var activityMode: ActivityMode = ActivityMode.IDLE
    private var surfaceSetting: SurfaceSetting = SurfaceSetting.LAND

    fun setUserOverride(mode: VisualMode?) {
        userOverride = mode
        recalculate()
    }

    fun clearUserOverride() {
        userOverride = null
        recalculate()
    }

    fun setSunAltitude(altitude: Double) {
        sunAltitude = altitude
        recalculate()
    }

    fun setLightLux(lux: Float) {
        lightLux = lux
        recalculate()
    }

    fun setActivityMode(mode: ActivityMode) {
        activityMode = mode
        recalculate()
    }

    fun setSurfaceSetting(setting: SurfaceSetting) {
        surfaceSetting = setting
        recalculate()
    }

    /**
     * Priority-based mode resolution:
     *  1. User override (always wins)
     *  2. Astronomical twilight → NIGHT_RED
     *  3. Active hiking + bright sun → GLANCEABLE (daylight contrast colors applied upstream)
     *  4. Active hiking → GLANCEABLE
     *  5. Active marine → MARINE_WET
     *  6. Bright sun → DAYLIGHT_CONTRAST
     *  7. Snow/water + bright sun → SNOW_GLARE
     *  8. Fallback → STANDARD
     */
    private fun recalculate() {
        val resolved = resolveMode()
        _currentMode.value = resolved
        _currentColorScheme.value = VisualModeColors.schemeFor(resolved)
    }

    private fun resolveMode(): VisualMode {
        userOverride?.let { return it }

        if (sunAltitude < -18.0) return VisualMode.NIGHT_RED

        if (activityMode == ActivityMode.ACTIVE_HIKING && lightLux > 30_000f) {
            return VisualMode.GLANCEABLE
        }

        if (activityMode == ActivityMode.ACTIVE_HIKING) return VisualMode.GLANCEABLE

        if (activityMode == ActivityMode.ACTIVE_MARINE) return VisualMode.MARINE_WET

        if (lightLux > 30_000f) return VisualMode.DAYLIGHT_CONTRAST

        if ((surfaceSetting == SurfaceSetting.SNOW || surfaceSetting == SurfaceSetting.WATER)
            && lightLux > 20_000f
        ) {
            return VisualMode.SNOW_GLARE
        }

        return VisualMode.STANDARD
    }
}
