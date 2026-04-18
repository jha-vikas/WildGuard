package com.wildguard.app.modules.altitude

import kotlin.math.ln

object BoilingPointCalculator {

    fun boilingPointSimple(altitudeM: Double): Double =
        100.0 - 0.0034 * altitudeM

    fun boilingPointPrecise(pressureHpa: Double): Double {
        val pAtm = pressureHpa / 1013.25
        val tKelvin = 1.0 / (1.0 / 373.15 - (R_GAS / LATENT_HEAT) * ln(pAtm))
        return tKelvin - 273.15
    }

    fun cookingTimeMultiplier(altitudeM: Double): Double =
        1.0 + 0.05 * (altitudeM / 1000.0)

    fun summary(altitudeM: Double, pressureHpa: Double? = null): BoilingPointSummary {
        val bpSimple = boilingPointSimple(altitudeM)
        val bpPrecise = pressureHpa?.let { boilingPointPrecise(it) }
        val multiplier = cookingTimeMultiplier(altitudeM)

        return BoilingPointSummary(
            boilingPointC = bpPrecise ?: bpSimple,
            usedPreciseModel = bpPrecise != null,
            cookingTimeMultiplier = multiplier,
            altitudeM = altitudeM
        )
    }

    private const val R_GAS = 8.314
    private const val LATENT_HEAT = 40660.0
}

data class BoilingPointSummary(
    val boilingPointC: Double,
    val usedPreciseModel: Boolean,
    val cookingTimeMultiplier: Double,
    val altitudeM: Double
) {
    val cookingNote: String
        get() {
            val pct = ((cookingTimeMultiplier - 1.0) * 100).toInt()
            return if (pct <= 0) "No significant cooking adjustment needed"
            else "Cooking takes ~${pct}% longer at this altitude"
        }
}
