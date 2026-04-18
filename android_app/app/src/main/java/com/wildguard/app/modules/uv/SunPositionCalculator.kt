package com.wildguard.app.modules.uv

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

data class SunPosition(
    val altitudeDeg: Double,
    val azimuthDeg: Double,
    val zenithAngleDeg: Double,
    val solarNoonUtc: Double,
    val sunriseUtc: Double,
    val sunsetUtc: Double,
    val declinationDeg: Double,
    val equationOfTimeMin: Double
)

object SunPositionCalculator {

    /**
     * NOAA Solar Calculator – full port of the spreadsheet algorithm.
     *
     * @param lat  Latitude in degrees (north positive)
     * @param lon  Longitude in degrees (east positive)
     * @param year UTC year
     * @param month UTC month (1-12)
     * @param day  UTC day of month
     * @param hour UTC fractional hour (e.g. 14.5 = 2:30 PM)
     */
    fun compute(lat: Double, lon: Double, year: Int, month: Int, day: Int, hour: Double): SunPosition {
        val jd = julianDay(year, month, day) + hour / 24.0
        val jc = (jd - 2451545.0) / 36525.0

        val geomMeanLongSunDeg = (280.46646 + jc * (36000.76983 + 0.0003032 * jc)) % 360.0
        val geomMeanAnomSunDeg = 357.52911 + jc * (35999.05029 - 0.0001537 * jc)
        val eccentEarthOrbit = 0.016708634 - jc * (0.000042037 + 0.0000001267 * jc)

        val geomMeanAnomRad = Math.toRadians(geomMeanAnomSunDeg)
        val sunEqOfCenter = sin(geomMeanAnomRad) * (1.914602 - jc * (0.004817 + 0.000014 * jc)) +
                sin(2.0 * geomMeanAnomRad) * (0.019993 - 0.000101 * jc) +
                sin(3.0 * geomMeanAnomRad) * 0.000289

        val sunTrueLongDeg = geomMeanLongSunDeg + sunEqOfCenter
        val sunAppLongDeg = sunTrueLongDeg - 0.00569 - 0.00478 * sin(Math.toRadians(125.04 - 1934.136 * jc))
        val sunAppLongRad = Math.toRadians(sunAppLongDeg)

        val meanObliqEclipticDeg = 23.0 + (26.0 + (21.448 - jc * (46.815 + jc * (0.00059 - jc * 0.001813))) / 60.0) / 60.0
        val obliqCorrDeg = meanObliqEclipticDeg + 0.00256 * cos(Math.toRadians(125.04 - 1934.136 * jc))
        val obliqCorrRad = Math.toRadians(obliqCorrDeg)

        val sunDeclinRad = asin(sin(obliqCorrRad) * sin(sunAppLongRad))
        val sunDeclinDeg = Math.toDegrees(sunDeclinRad)

        val varY = tan(obliqCorrRad / 2.0).pow(2.0)
        val eqOfTimeMin = 4.0 * Math.toDegrees(
            varY * sin(2.0 * Math.toRadians(geomMeanLongSunDeg)) -
                    2.0 * eccentEarthOrbit * sin(geomMeanAnomRad) +
                    4.0 * eccentEarthOrbit * varY * sin(geomMeanAnomRad) * cos(2.0 * Math.toRadians(geomMeanLongSunDeg)) -
                    0.5 * varY * varY * sin(4.0 * Math.toRadians(geomMeanLongSunDeg)) -
                    1.25 * eccentEarthOrbit * eccentEarthOrbit * sin(2.0 * geomMeanAnomRad)
        )

        val latRad = Math.toRadians(lat)

        val haSunriseDeg = computeHourAngle(latRad, sunDeclinRad, 90.833)
        val solarNoonFrac = (720.0 - 4.0 * lon - eqOfTimeMin) / 1440.0
        val solarNoonUtcHours = solarNoonFrac * 24.0
        val sunriseUtcHours = solarNoonUtcHours - haSunriseDeg * 4.0 / 60.0
        val sunsetUtcHours = solarNoonUtcHours + haSunriseDeg * 4.0 / 60.0

        val trueSolarTimeMin = ((hour / 24.0) * 1440.0 + eqOfTimeMin + 4.0 * lon) % 1440.0
        val hourAngleDeg = if (trueSolarTimeMin < 0) trueSolarTimeMin / 4.0 + 180.0
        else trueSolarTimeMin / 4.0 - 180.0
        val hourAngleRad = Math.toRadians(hourAngleDeg)

        val zenithCos = sin(latRad) * sin(sunDeclinRad) +
                cos(latRad) * cos(sunDeclinRad) * cos(hourAngleRad)
        val zenithRad = acos(zenithCos.coerceIn(-1.0, 1.0))
        val zenithDeg = Math.toDegrees(zenithRad)
        val altitudeDeg = 90.0 - zenithDeg

        val azimuthDeg = computeAzimuth(latRad, sunDeclinRad, zenithRad, hourAngleDeg)

        return SunPosition(
            altitudeDeg = altitudeDeg,
            azimuthDeg = azimuthDeg,
            zenithAngleDeg = zenithDeg,
            solarNoonUtc = solarNoonUtcHours,
            sunriseUtc = sunriseUtcHours,
            sunsetUtc = sunsetUtcHours,
            declinationDeg = sunDeclinDeg,
            equationOfTimeMin = eqOfTimeMin
        )
    }

    fun computeForLocalTime(lat: Double, lon: Double, timeMillis: Long): SunPosition {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = timeMillis
        }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY) +
                cal.get(Calendar.MINUTE) / 60.0 +
                cal.get(Calendar.SECOND) / 3600.0
        return compute(lat, lon, year, month, day, hour)
    }

    private fun julianDay(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = y / 100
        val b = 2 - a + a / 4
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private fun computeHourAngle(latRad: Double, decRad: Double, zenithRefDeg: Double): Double {
        val zenithRefRad = Math.toRadians(zenithRefDeg)
        val cosHA = (cos(zenithRefRad) / (cos(latRad) * cos(decRad))) - tan(latRad) * tan(decRad)
        if (cosHA > 1.0) return 0.0   // sun never rises
        if (cosHA < -1.0) return 180.0 // sun never sets
        return Math.toDegrees(acos(cosHA))
    }

    private fun computeAzimuth(latRad: Double, decRad: Double, zenithRad: Double, hourAngleDeg: Double): Double {
        val sinZenith = sin(zenithRad)
        if (sinZenith == 0.0) return 0.0

        val cosAz = ((sin(latRad) * cos(zenithRad)) - sin(decRad)) / (cos(latRad) * sinZenith)
        val azRad = acos(cosAz.coerceIn(-1.0, 1.0))
        val azDeg = Math.toDegrees(azRad)

        return if (hourAngleDeg > 0) (azDeg + 180.0) % 360.0
        else (540.0 - azDeg) % 360.0
    }
}
