package com.wildguard.app.modules.thermal

import kotlin.math.pow

data class HydrationResult(
    val caloriesBurned: Double,
    val waterNeededLiters: Double,
    val electrolyteNote: String
)

/**
 * Estimates caloric expenditure using the Pandolf equation and derives hydration needs.
 *
 * Pandolf equation:
 *   M = 1.5W + 2.0(W+L)(L/W)² + η(W+L)(1.5V² + 0.35VG)
 * where:
 *   W = body weight (kg), L = load weight (kg), V = speed (m/s),
 *   G = grade (%), η = terrain factor
 *
 * The result M is in watts. Convert to kcal/hr: M * 0.86 (approx).
 */
object HydrationCalculator {

    enum class Terrain(val factor: Double, val label: String) {
        PAVED_ROAD(1.0, "Paved road"),
        DIRT_ROAD(1.1, "Dirt road"),
        GRAVEL(1.2, "Gravel path"),
        LIGHT_BUSH(1.3, "Light bush"),
        HEAVY_BUSH(1.5, "Heavy bush"),
        SWAMP(1.8, "Swamp/bog"),
        SAND(2.1, "Loose sand"),
        SNOW_PACKED(1.3, "Packed snow"),
        SNOW_DEEP(2.5, "Deep snow")
    }

    /**
     * @param bodyWeightKg hiker body weight
     * @param loadWeightKg pack weight
     * @param speedMps walking speed in m/s
     * @param gradePercent slope grade in percent (positive = uphill)
     * @param terrain terrain type
     * @param durationHours planned activity duration
     * @param tempC air temperature for hydration adjustment
     */
    fun calculate(
        bodyWeightKg: Double = 75.0,
        loadWeightKg: Double = 10.0,
        speedMps: Double = 1.2,
        gradePercent: Double = 0.0,
        terrain: Terrain = Terrain.DIRT_ROAD,
        durationHours: Double = 1.0,
        tempC: Double = 20.0
    ): HydrationResult {
        val w = bodyWeightKg
        val l = loadWeightKg
        val v = speedMps.coerceAtLeast(0.1)
        val g = gradePercent.coerceAtLeast(0.0)
        val n = terrain.factor

        val metabolicWatts = 1.5 * w +
                2.0 * (w + l) * (l / w).pow(2) +
                n * (w + l) * (1.5 * v.pow(2) + 0.35 * v * g)

        val kcalPerHour = metabolicWatts * 0.86
        val totalKcal = kcalPerHour * durationHours

        val baseWaterLPerKcal = 0.75 / 400.0
        val tempMultiplier = when {
            tempC < 20.0 -> 1.0
            tempC < 30.0 -> 1.25
            tempC < 40.0 -> 1.50
            else -> 2.0
        }

        val waterNeeded = totalKcal * baseWaterLPerKcal * tempMultiplier

        val electrolyteNote = when {
            durationHours < 1.0 -> "Water alone is sufficient for short activity."
            tempC > 30.0 || durationHours > 3.0 ->
                "Add electrolytes: ~500-700 mg sodium per liter of water."
            durationHours > 1.5 ->
                "Consider electrolyte supplementation for extended activity."
            else -> "Water alone should be sufficient. Eat salty snacks if sweating heavily."
        }

        return HydrationResult(
            caloriesBurned = totalKcal,
            waterNeededLiters = waterNeeded,
            electrolyteNote = electrolyteNote
        )
    }
}
