package com.wildguard.app.modules.compass

import kotlin.math.*

object CompassCalculator {

    data class CompassReading(
        val magneticHeading: Double,
        val trueHeading: Double,
        val declination: Double,
        val cardinalDirection: String
    )

    fun getReading(magneticHeadingDeg: Double, lat: Double, lon: Double, year: Double = 2025.0): CompassReading {
        val declination = MagneticDeclinationModel.getDeclination(lat, lon, year)
        val trueHeading = normalizeAngle(magneticHeadingDeg + declination)
        return CompassReading(
            magneticHeading = normalizeAngle(magneticHeadingDeg),
            trueHeading = trueHeading,
            declination = declination,
            cardinalDirection = cardinalDirection(trueHeading)
        )
    }

    fun cardinalDirection(heading: Double): String {
        val normalized = normalizeAngle(heading)
        return when {
            normalized < 22.5 || normalized >= 337.5 -> "N"
            normalized < 67.5 -> "NE"
            normalized < 112.5 -> "E"
            normalized < 157.5 -> "SE"
            normalized < 202.5 -> "S"
            normalized < 247.5 -> "SW"
            normalized < 292.5 -> "W"
            else -> "NW"
        }
    }

    /**
     * Initial bearing from point 1 to point 2 using the spherical law of cosines.
     * Returns degrees 0-360 (clockwise from north).
     */
    fun bearingTo(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val dLon = Math.toRadians(toLon - fromLon)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))
        return normalizeAngle(bearing)
    }

    /**
     * Haversine distance between two points on the Earth's surface.
     * Returns distance in meters.
     */
    fun distanceTo(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(toLat - fromLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)

        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    fun formatDistance(meters: Double): String = when {
        meters < 1000 -> "${meters.toInt()} m"
        else -> "%.1f km".format(meters / 1000.0)
    }

    private fun normalizeAngle(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
}
