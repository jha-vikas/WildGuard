package com.wildguard.app.modules.thermal

import kotlin.math.pow

enum class WindChillRisk { LOW, MODERATE, HIGH, EXTREME }

data class WindChillResult(
    val effectiveTempC: Double,
    val frostbiteMinutes: Double?,
    val riskLevel: WindChillRisk
)

/**
 * NWS Wind Chill Temperature Index.
 * Valid for air temperature <= 10°C and wind speed >= 4.8 km/h.
 */
object WindChillCalculator {

    fun calculate(tempC: Double, windSpeedKmh: Double): WindChillResult? {
        if (tempC > 10.0 || windSpeedKmh < 4.8) return null

        val v016 = windSpeedKmh.pow(0.16)
        val wc = 13.12 + 0.6215 * tempC - 11.37 * v016 + 0.3965 * tempC * v016

        val frostbite = frostbiteOnsetMinutes(wc)
        val risk = riskLevel(wc)

        return WindChillResult(
            effectiveTempC = wc,
            frostbiteMinutes = frostbite,
            riskLevel = risk
        )
    }

    /**
     * Frostbite onset time estimation based on wind chill value.
     * Uses linear interpolation between known thresholds:
     *   WC <= -28 → ~30 min
     *   WC <= -40 → ~10 min
     *   WC <= -48 → ~5 min
     */
    private fun frostbiteOnsetMinutes(wc: Double): Double? = when {
        wc > -28.0 -> null
        wc > -40.0 -> lerp(30.0, 10.0, (-28.0 - wc) / 12.0)
        wc > -48.0 -> lerp(10.0, 5.0, (-40.0 - wc) / 8.0)
        else -> 5.0
    }

    private fun riskLevel(wc: Double): WindChillRisk = when {
        wc > -10.0 -> WindChillRisk.LOW
        wc > -28.0 -> WindChillRisk.MODERATE
        wc > -40.0 -> WindChillRisk.HIGH
        else -> WindChillRisk.EXTREME
    }

    private fun lerp(a: Double, b: Double, t: Double): Double =
        a + (b - a) * t.coerceIn(0.0, 1.0)
}
