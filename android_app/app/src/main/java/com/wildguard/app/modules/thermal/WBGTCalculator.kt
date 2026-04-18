package com.wildguard.app.modules.thermal

import kotlin.math.exp

enum class ActivityGuidance {
    UNRESTRICTED,
    USE_DISCRETION,
    LIMIT_INTENSE_EXERCISE,
    REST_WATER_BREAKS,
    SUSPEND_EXERCISE
}

data class WBGTResult(
    val wbgtC: Double,
    val activityGuidance: ActivityGuidance,
    val restCycleMinutes: Int,
    val description: String
)

/**
 * Simplified Wet Bulb Globe Temperature estimation from temperature and humidity.
 * Uses the Liljegren approximation: WBGT ≈ 0.567*T + 0.393*e + 3.94
 * where e = vapor pressure in hPa.
 *
 * Activity guidelines follow U.S. military heat stress standards (TB MED 507).
 */
object WBGTCalculator {

    fun calculate(tempC: Double, humidityPercent: Double): WBGTResult {
        val e = (humidityPercent / 100.0) * 6.105 * exp(17.27 * tempC / (237.7 + tempC))
        val wbgt = 0.567 * tempC + 0.393 * e + 3.94

        val (guidance, rest, desc) = classifyRisk(wbgt)

        return WBGTResult(
            wbgtC = wbgt,
            activityGuidance = guidance,
            restCycleMinutes = rest,
            description = desc
        )
    }

    private fun classifyRisk(wbgt: Double): Triple<ActivityGuidance, Int, String> = when {
        wbgt < 25.0 -> Triple(
            ActivityGuidance.UNRESTRICTED, 60,
            "Normal activity. Hydrate regularly."
        )
        wbgt < 27.6 -> Triple(
            ActivityGuidance.USE_DISCRETION, 50,
            "Use discretion for intense activity. 50 min work / 10 min rest."
        )
        wbgt < 29.4 -> Triple(
            ActivityGuidance.LIMIT_INTENSE_EXERCISE, 40,
            "Limit intense exercise. 40 min work / 20 min rest."
        )
        wbgt < 31.1 -> Triple(
            ActivityGuidance.REST_WATER_BREAKS, 30,
            "Strenuous exercise limited. 30 min work / 30 min rest."
        )
        else -> Triple(
            ActivityGuidance.SUSPEND_EXERCISE, 20,
            "Suspend strenuous exercise. Rest in shade, hydrate continuously."
        )
    }
}
