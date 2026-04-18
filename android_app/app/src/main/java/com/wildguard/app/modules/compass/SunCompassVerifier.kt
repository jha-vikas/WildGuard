package com.wildguard.app.modules.compass

import kotlin.math.*

data class CompassVerification(
    val compassHeading: Double,
    val expectedSunBearing: Double,
    val discrepancy: Double,
    val isConsistent: Boolean,
    val sunAltitude: Double,
    val isValid: Boolean
)

/**
 * Compares compass heading with the computed sun azimuth to detect magnetic interference.
 * Uses a simplified solar position algorithm (accuracy ~1° for 2020-2030).
 */
object SunCompassVerifier {

    private const val DISCREPANCY_THRESHOLD = 15.0
    private const val MIN_SUN_ALTITUDE = 10.0

    fun verify(
        compassHeadingDeg: Double,
        lat: Double,
        lon: Double,
        timestampMs: Long = System.currentTimeMillis()
    ): CompassVerification {
        val sunPos = computeSunPosition(lat, lon, timestampMs)

        val isValid = sunPos.altitude >= MIN_SUN_ALTITUDE

        val discrepancy = if (isValid) {
            angleDifference(compassHeadingDeg, sunPos.azimuth)
        } else {
            0.0
        }

        return CompassVerification(
            compassHeading = compassHeadingDeg,
            expectedSunBearing = sunPos.azimuth,
            discrepancy = discrepancy,
            isConsistent = !isValid || discrepancy <= DISCREPANCY_THRESHOLD,
            sunAltitude = sunPos.altitude,
            isValid = isValid
        )
    }

    private data class SunPosition(val altitude: Double, val azimuth: Double)

    /**
     * Simplified solar position using the "Solar Calculations" spreadsheet algorithm
     * from NOAA. Sufficient accuracy for compass verification purposes.
     */
    private fun computeSunPosition(lat: Double, lon: Double, timestampMs: Long): SunPosition {
        val jd = timestampMs / 86_400_000.0 + 2_440_587.5
        val jc = (jd - 2_451_545.0) / 36_525.0

        val geomMeanLongSunDeg = (280.46646 + jc * (36_000.76983 + 0.0003032 * jc)) % 360.0
        val geomMeanAnomSunDeg = 357.52911 + jc * (35_999.05029 - 0.0001537 * jc)
        val eccentEarthOrbit = 0.016708634 - jc * (0.000042037 + 0.0000001267 * jc)
        val sunEqOfCtr = sin(Math.toRadians(geomMeanAnomSunDeg)) *
                (1.914602 - jc * (0.004817 + 0.000014 * jc)) +
                sin(Math.toRadians(2 * geomMeanAnomSunDeg)) * (0.019993 - 0.000101 * jc) +
                sin(Math.toRadians(3 * geomMeanAnomSunDeg)) * 0.000289
        val sunTrueLongDeg = geomMeanLongSunDeg + sunEqOfCtr
        val sunAppLongDeg = sunTrueLongDeg - 0.00569 -
                0.00478 * sin(Math.toRadians(125.04 - 1934.136 * jc))

        val meanObliqEclipticDeg = 23.0 + (26.0 + (21.448 - jc *
                (46.815 + jc * (0.00059 - jc * 0.001813))) / 60.0) / 60.0
        val obliqCorrDeg = meanObliqEclipticDeg +
                0.00256 * cos(Math.toRadians(125.04 - 1934.136 * jc))

        val sunDeclinDeg = Math.toDegrees(
            asin(sin(Math.toRadians(obliqCorrDeg)) * sin(Math.toRadians(sunAppLongDeg)))
        )

        val varY = tan(Math.toRadians(obliqCorrDeg / 2)).pow(2)
        val eqOfTimeMin = 4 * Math.toDegrees(
            varY * sin(2 * Math.toRadians(geomMeanLongSunDeg)) -
                    2 * eccentEarthOrbit * sin(Math.toRadians(geomMeanAnomSunDeg)) +
                    4 * eccentEarthOrbit * varY * sin(Math.toRadians(geomMeanAnomSunDeg)) *
                    cos(2 * Math.toRadians(geomMeanLongSunDeg)) -
                    0.5 * varY * varY * sin(4 * Math.toRadians(geomMeanLongSunDeg)) -
                    1.25 * eccentEarthOrbit * eccentEarthOrbit *
                    sin(2 * Math.toRadians(geomMeanAnomSunDeg))
        )

        val totalMinFromMidnightUtc = ((jd + 0.5) % 1.0) * 1440.0
        val trueSolarTimeMin = totalMinFromMidnightUtc + eqOfTimeMin + 4 * lon
        val hourAngleDeg = trueSolarTimeMin / 4.0 - 180.0

        val latRad = Math.toRadians(lat)
        val decRad = Math.toRadians(sunDeclinDeg)
        val haRad = Math.toRadians(hourAngleDeg)

        val zenithRad = acos(
            (sin(latRad) * sin(decRad) + cos(latRad) * cos(decRad) * cos(haRad))
                .coerceIn(-1.0, 1.0)
        )
        val altitude = 90.0 - Math.toDegrees(zenithRad)

        val azimuthRad = if (hourAngleDeg > 0) {
            (Math.toDegrees(acos(
                ((sin(latRad) * cos(zenithRad) - sin(decRad)) /
                        (cos(latRad) * sin(zenithRad))).coerceIn(-1.0, 1.0)
            )) + 180.0) % 360.0
        } else {
            (540.0 - Math.toDegrees(acos(
                ((sin(latRad) * cos(zenithRad) - sin(decRad)) /
                        (cos(latRad) * sin(zenithRad))).coerceIn(-1.0, 1.0)
            ))) % 360.0
        }

        return SunPosition(altitude = altitude, azimuth = azimuthRad)
    }

    private fun angleDifference(a: Double, b: Double): Double {
        var diff = abs(a - b) % 360.0
        if (diff > 180.0) diff = 360.0 - diff
        return diff
    }
}
