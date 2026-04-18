package com.wildguard.app.modules.uv

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

enum class SkinType(val med: Double, val vitDBaseRate: Double) {
    TYPE_I(2.0, 4.0),
    TYPE_II(2.5, 5.0),
    TYPE_III(3.5, 6.5),
    TYPE_IV(4.5, 8.0),
    TYPE_V(6.0, 12.0),
    TYPE_VI(10.0, 20.0)
}

enum class SurfaceType(val factor: Double) {
    GRASS(1.0),
    SAND(1.15),
    WATER(1.1),
    SNOW(1.25),
    CITY(1.05)
}

data class UVResult(
    val uvIndex: Double,
    val category: String,
    val safeExposureMin: Double,
    val safeWithSpfMin: Double,
    val vitaminDMin: Double,
    val dailyCurve: List<Pair<Int, Double>>
)

object UVIndexCalculator {

    fun compute(
        sunPosition: SunPosition,
        altitudeMeters: Double,
        lightLux: Float?,
        ozoneFactor: Double = 1.0,
        surfaceType: SurfaceType = SurfaceType.GRASS,
        skinType: SkinType = SkinType.TYPE_II,
        spf: Int = 30,
        lat: Double = 0.0,
        lon: Double = 0.0,
        timeMillis: Long = System.currentTimeMillis()
    ): UVResult {
        val zenithRad = Math.toRadians(sunPosition.zenithAngleDeg)
        val altitudeRad = Math.toRadians(sunPosition.altitudeDeg)

        val baseUV = if (sunPosition.zenithAngleDeg < 90.0)
            12.0 * cos(zenithRad).pow(2.4)
        else 0.0

        val altitudeCorrection = 1.0 + 0.06 * (altitudeMeters / 1000.0)

        val expectedClearSkyLux = if (sunPosition.altitudeDeg > 0)
            120_000.0 * sin(altitudeRad)
        else 0.0

        val cloudFactor = if (lightLux != null && expectedClearSkyLux > 0)
            (lightLux.toDouble() / expectedClearSkyLux).coerceIn(0.25, 1.0)
        else 1.0

        val uvIndex = (baseUV * altitudeCorrection * cloudFactor * ozoneFactor * surfaceType.factor)
            .coerceIn(0.0, 15.0)

        val category = categorize(uvIndex)

        val safeExposureMin = if (uvIndex > 0)
            (skinType.med * 40.0) / uvIndex
        else Double.MAX_VALUE

        val safeWithSpfMin = safeExposureMin * min(spf, 50).toDouble()

        val vitaminDMin = if (uvIndex > 0)
            1000.0 / (skinType.vitDBaseRate * uvIndex / 5.0)
        else Double.MAX_VALUE

        val dailyCurve = computeDailyCurve(lat, lon, timeMillis, altitudeMeters, ozoneFactor, surfaceType)

        return UVResult(
            uvIndex = uvIndex,
            category = category,
            safeExposureMin = safeExposureMin,
            safeWithSpfMin = safeWithSpfMin,
            vitaminDMin = vitaminDMin,
            dailyCurve = dailyCurve
        )
    }

    private fun categorize(uv: Double): String = when {
        uv < 3 -> "Low"
        uv < 6 -> "Moderate"
        uv < 8 -> "High"
        uv < 11 -> "Very High"
        else -> "Extreme"
    }

    private fun computeDailyCurve(
        lat: Double,
        lon: Double,
        timeMillis: Long,
        altitudeMeters: Double,
        ozoneFactor: Double,
        surfaceType: SurfaceType
    ): List<Pair<Int, Double>> {
        val cal = Calendar.getInstance().apply { this.timeInMillis = timeMillis }
        val tzOffsetHours = cal.timeZone.getOffset(timeMillis) / 3600_000.0

        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            this.timeInMillis = timeMillis
        }
        val year = utcCal.get(Calendar.YEAR)
        val month = utcCal.get(Calendar.MONTH) + 1
        val day = utcCal.get(Calendar.DAY_OF_MONTH)

        return (5..20).map { localHour ->
            val utcHour = localHour - tzOffsetHours
            val sp = SunPositionCalculator.compute(lat, lon, year, month, day, utcHour)
            val zenithRad = Math.toRadians(sp.zenithAngleDeg)
            val altCorr = 1.0 + 0.06 * (altitudeMeters / 1000.0)
            val base = if (sp.zenithAngleDeg < 90.0) 12.0 * cos(zenithRad).pow(2.4) else 0.0
            val uv = (base * altCorr * ozoneFactor * surfaceType.factor).coerceIn(0.0, 15.0)
            localHour to uv
        }
    }
}
