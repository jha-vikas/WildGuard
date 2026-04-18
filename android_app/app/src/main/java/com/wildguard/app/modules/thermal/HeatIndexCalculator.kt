package com.wildguard.app.modules.thermal

enum class HeatRisk { CAUTION, EXTREME_CAUTION, DANGER, EXTREME_DANGER }

data class HeatIndexResult(
    val heatIndexC: Double,
    val riskLevel: HeatRisk?,
    val recommendation: String
)

/**
 * Rothfusz regression for Heat Index calculation.
 * Input in Celsius; internally converts to Fahrenheit for the NWS formula, then back.
 */
object HeatIndexCalculator {

    fun calculate(tempC: Double, humidityPercent: Double): HeatIndexResult {
        val tempF = tempC * 9.0 / 5.0 + 32.0

        // Simple formula first (Steadman)
        val simpleHiF = 0.5 * (tempF + 61.0 + (tempF - 68.0) * 1.2 + humidityPercent * 0.094)

        val hiF: Double
        if (simpleHiF < 80.0) {
            hiF = simpleHiF
        } else {
            val t = tempF
            val rh = humidityPercent
            var hi = -42.379 +
                    2.04901523 * t +
                    10.14333127 * rh -
                    0.22475541 * t * rh -
                    0.00683783 * t * t -
                    0.05481717 * rh * rh +
                    0.00122874 * t * t * rh +
                    0.00085282 * t * rh * rh -
                    0.00000199 * t * t * rh * rh

            if (rh < 13.0 && t in 80.0..112.0) {
                hi -= ((13.0 - rh) / 4.0) * kotlin.math.sqrt((17.0 - kotlin.math.abs(t - 95.0)) / 17.0)
            } else if (rh > 85.0 && t in 80.0..87.0) {
                hi += ((rh - 85.0) / 10.0) * ((87.0 - t) / 5.0)
            }

            hiF = hi
        }

        val hiC = (hiF - 32.0) * 5.0 / 9.0

        val risk = when {
            hiC < 27.0 -> null
            hiC < 32.0 -> HeatRisk.CAUTION
            hiC < 39.0 -> HeatRisk.EXTREME_CAUTION
            hiC < 51.0 -> HeatRisk.DANGER
            else -> HeatRisk.EXTREME_DANGER
        }

        val recommendation = when (risk) {
            null -> "Conditions are comfortable."
            HeatRisk.CAUTION -> "Fatigue possible with prolonged exposure. Take regular breaks."
            HeatRisk.EXTREME_CAUTION -> "Heat cramps and exhaustion possible. Limit strenuous activity."
            HeatRisk.DANGER -> "Heat exhaustion likely. Avoid strenuous activity, seek shade."
            HeatRisk.EXTREME_DANGER -> "Heat stroke imminent. Stop all activity, cool immediately."
        }

        return HeatIndexResult(heatIndexC = hiC, riskLevel = risk, recommendation = recommendation)
    }

    /**
     * Alternate calculation accepting Celsius directly using the Rothfusz regression
     * converted to metric (for UI display when sensor data is already in Celsius).
     */
    fun calculateMetric(tempC: Double, humidityPercent: Double): HeatIndexResult {
        if (tempC < 27.0) {
            return HeatIndexResult(
                heatIndexC = tempC,
                riskLevel = null,
                recommendation = "Conditions are comfortable."
            )
        }

        val t = tempC
        val rh = humidityPercent
        val hi = -8.78469476 +
                1.61139411 * t +
                2.33854884 * rh -
                0.14611605 * t * rh -
                0.012308094 * t * t -
                0.016424828 * rh * rh +
                0.002211732 * t * t * rh +
                0.00072546 * t * rh * rh -
                0.000003582 * t * t * rh * rh

        val risk = when {
            hi < 27.0 -> null
            hi < 32.0 -> HeatRisk.CAUTION
            hi < 39.0 -> HeatRisk.EXTREME_CAUTION
            hi < 51.0 -> HeatRisk.DANGER
            else -> HeatRisk.EXTREME_DANGER
        }

        val recommendation = when (risk) {
            null -> "Conditions are comfortable."
            HeatRisk.CAUTION -> "Fatigue possible with prolonged exposure. Take regular breaks."
            HeatRisk.EXTREME_CAUTION -> "Heat cramps and exhaustion possible. Limit strenuous activity."
            HeatRisk.DANGER -> "Heat exhaustion likely. Avoid strenuous activity, seek shade."
            HeatRisk.EXTREME_DANGER -> "Heat stroke imminent. Stop all activity, cool immediately."
        }

        return HeatIndexResult(heatIndexC = hi, riskLevel = risk, recommendation = recommendation)
    }
}
