package com.wildguard.app.modules.weather

import java.util.Calendar

data class ZambrettiForecast(
    val number: Int,
    val description: String,
    val shortText: String,
    val severity: ForecastSeverity
)

enum class ForecastSeverity { GOOD, FAIR, POOR, STORM }

class ZambrettiForecaster {

    fun forecast(
        pressureHpa: Float,
        trend: PressureTrend,
        windDirectionDeg: Double? = null,
        isNorthernHemisphere: Boolean = true,
        month: Int = Calendar.getInstance().get(Calendar.MONTH) + 1
    ): ZambrettiForecast {
        val pressure = pressureHpa.toDouble()

        var z = when (trend) {
            PressureTrend.RAPID_RISE, PressureTrend.SLOW_RISE ->
                (179.0 - (2.0 * pressure / 129.0)).toInt()
            PressureTrend.STEADY ->
                (147.0 - (5.0 * pressure / 376.0)).toInt()
            PressureTrend.RAPID_DROP, PressureTrend.SLOW_DROP ->
                (127.0 - (8.0 * pressure / 493.0)).toInt()
        }

        val isSummer = if (isNorthernHemisphere) month in 5..9
                       else month in 11..12 || month in 1..3
        z += if (isSummer) 2 else -2

        if (windDirectionDeg != null) {
            val northWind = windDirectionDeg in 315.0..360.0 || windDirectionDeg in 0.0..45.0
            val southWind = windDirectionDeg in 135.0..225.0
            if (isNorthernHemisphere) {
                if (northWind) z += 1
                if (southWind) z -= 1
            } else {
                if (southWind) z += 1
                if (northWind) z -= 1
            }
        }

        z = z.coerceIn(1, 26)
        val (desc, short, sev) = lookupForecast(z)
        return ZambrettiForecast(z, desc, short, sev)
    }

    private fun lookupForecast(z: Int): Triple<String, String, ForecastSeverity> = when (z) {
        1  -> Triple("Settled fine weather", "Settled fine", ForecastSeverity.GOOD)
        2  -> Triple("Fine weather", "Fine", ForecastSeverity.GOOD)
        3  -> Triple("Fine, becoming less settled", "Less settled", ForecastSeverity.GOOD)
        4  -> Triple("Fairly fine, showery later", "Showery later", ForecastSeverity.FAIR)
        5  -> Triple("Showery, becoming more unsettled", "Showery", ForecastSeverity.FAIR)
        6  -> Triple("Unsettled, rain later", "Rain later", ForecastSeverity.FAIR)
        7  -> Triple("Rain at times, worse later", "Rain at times", ForecastSeverity.FAIR)
        8  -> Triple("Rain at times, becoming very unsettled", "Very unsettled", ForecastSeverity.POOR)
        9  -> Triple("Very unsettled, rain", "Rain likely", ForecastSeverity.POOR)
        10 -> Triple("Changeable, some rain", "Changeable", ForecastSeverity.FAIR)
        11 -> Triple("Rather unsettled, clearing later", "Clearing later", ForecastSeverity.FAIR)
        12 -> Triple("Unsettled, probably improving", "Improving", ForecastSeverity.FAIR)
        13 -> Triple("Showery early, improving", "Improving", ForecastSeverity.FAIR)
        14 -> Triple("Changeable, mending", "Mending", ForecastSeverity.FAIR)
        15 -> Triple("Rather unsettled, rain at frequent intervals", "Frequent rain", ForecastSeverity.POOR)
        16 -> Triple("Rain at frequent intervals, very unsettled", "Frequent rain", ForecastSeverity.POOR)
        17 -> Triple("Stormy, probably improving", "Stormy, improving", ForecastSeverity.STORM)
        18 -> Triple("Stormy, much rain", "Stormy", ForecastSeverity.STORM)
        19 -> Triple("Very unsettled, rain", "Very unsettled", ForecastSeverity.POOR)
        20 -> Triple("Occasional rain, worsening", "Worsening", ForecastSeverity.POOR)
        21 -> Triple("Rain at times, severe weather possible", "Severe possible", ForecastSeverity.STORM)
        22 -> Triple("Fairly fine, rain later", "Rain later", ForecastSeverity.FAIR)
        23 -> Triple("Fairly fine, showers likely", "Showers likely", ForecastSeverity.FAIR)
        24 -> Triple("Showery, bright intervals", "Showery", ForecastSeverity.FAIR)
        25 -> Triple("Stormy, rain expected", "Stormy rain", ForecastSeverity.STORM)
        26 -> Triple("Storm force winds, very heavy rain", "Storm!", ForecastSeverity.STORM)
        else -> Triple("Uncertain forecast", "Unknown", ForecastSeverity.FAIR)
    }
}
