package com.wildguard.app.modules.celestial

import kotlin.math.*

data class MoonData(
    val altitudeDeg: Double,
    val azimuthDeg: Double,
    val illuminationPercent: Double,
    val phaseName: String,
    val isWaxing: Boolean,
    val moonriseUtcHours: Double?,
    val moonsetUtcHours: Double?,
    val ageInDays: Double
)

object MoonCalculator {

    private const val RAD = PI / 180.0
    private const val DEG = 180.0 / PI
    private const val J2000 = 2451545.0
    private const val SYNODIC_MONTH = 29.53058868

    fun compute(latDeg: Double, lonDeg: Double, utcMillis: Long): MoonData {
        val jd = toJulianDay(utcMillis)
        val T = (jd - J2000) / 36525.0

        val (moonRaDeg, moonDecDeg) = moonEquatorial(T)
        val (sunRaDeg, sunDecDeg) = sunEquatorial(T)

        val gmst = greenwichMeanSiderealTime(jd)
        val lst = gmst + lonDeg

        val (alt, az) = equatorialToHorizontal(moonRaDeg, moonDecDeg, latDeg, lst)

        val elongation = acos(
            clamp(
                sin(sunDecDeg * RAD) * sin(moonDecDeg * RAD) +
                    cos(sunDecDeg * RAD) * cos(moonDecDeg * RAD) *
                    cos((sunRaDeg - moonRaDeg) * RAD),
                -1.0, 1.0
            )
        )

        val illumination = (1.0 + cos(elongation)) / 2.0

        val moonAge = computeMoonAge(jd)
        val isWaxing = moonAge < SYNODIC_MONTH / 2.0
        val phaseName = phaseName(illumination, isWaxing)

        val jdMidnight = floor(jd - 0.5) + 0.5
        val (rise, set) = findRiseSet(latDeg, lonDeg, jdMidnight)

        return MoonData(
            altitudeDeg = alt,
            azimuthDeg = az,
            illuminationPercent = illumination * 100.0,
            phaseName = phaseName,
            isWaxing = isWaxing,
            moonriseUtcHours = rise,
            moonsetUtcHours = set,
            ageInDays = moonAge
        )
    }

    private fun moonEquatorial(T: Double): Pair<Double, Double> {
        val Lp = norm360(218.3165 + 481267.8813 * T)
        val D = norm360(297.8502 + 445267.1115 * T)
        val M = norm360(357.5291 + 35999.0503 * T)
        val Mp = norm360(134.9634 + 477198.8676 * T)
        val F = norm360(93.2720 + 483202.0175 * T)

        val lonTerms = arrayOf(
            doubleArrayOf(0.0, 0.0, 1.0, 0.0, 6288774.0),
            doubleArrayOf(2.0, 0.0, -1.0, 0.0, 1274027.0),
            doubleArrayOf(2.0, 0.0, 0.0, 0.0, 658314.0),
            doubleArrayOf(0.0, 0.0, 2.0, 0.0, 213618.0),
            doubleArrayOf(0.0, 1.0, 0.0, 0.0, -185116.0),
            doubleArrayOf(0.0, 0.0, 0.0, 2.0, -114332.0),
            doubleArrayOf(2.0, 0.0, -2.0, 0.0, 58793.0),
            doubleArrayOf(2.0, -1.0, -1.0, 0.0, 57066.0),
            doubleArrayOf(2.0, 0.0, 1.0, 0.0, 53322.0),
            doubleArrayOf(2.0, -1.0, 0.0, 0.0, 45758.0)
        )

        val latTerms = arrayOf(
            doubleArrayOf(0.0, 0.0, 0.0, 1.0, 5128122.0),
            doubleArrayOf(0.0, 0.0, 1.0, 1.0, 280602.0),
            doubleArrayOf(0.0, 0.0, 1.0, -1.0, 277693.0),
            doubleArrayOf(2.0, 0.0, 0.0, -1.0, 173237.0),
            doubleArrayOf(2.0, 0.0, -1.0, 1.0, 55413.0),
            doubleArrayOf(2.0, 0.0, -1.0, -1.0, 46271.0),
            doubleArrayOf(2.0, 0.0, 0.0, 1.0, 32573.0),
            doubleArrayOf(0.0, 0.0, 2.0, 1.0, 17198.0),
            doubleArrayOf(2.0, 0.0, 1.0, -1.0, 9266.0),
            doubleArrayOf(0.0, 0.0, 2.0, -1.0, 8822.0)
        )

        var sumL = 0.0
        for (term in lonTerms) {
            val arg = term[0] * D + term[1] * M + term[2] * Mp + term[3] * F
            sumL += term[4] * sin(arg * RAD)
        }

        var sumB = 0.0
        for (term in latTerms) {
            val arg = term[0] * D + term[1] * M + term[2] * Mp + term[3] * F
            sumB += term[4] * sin(arg * RAD)
        }

        val eclLon = Lp + sumL / 1_000_000.0
        val eclLat = sumB / 1_000_000.0

        val obliquity = 23.4393 - 0.0130 * T

        val ra = atan2(
            sin(eclLon * RAD) * cos(obliquity * RAD) - tan(eclLat * RAD) * sin(obliquity * RAD),
            cos(eclLon * RAD)
        ) * DEG
        val dec = asin(
            sin(eclLat * RAD) * cos(obliquity * RAD) +
                cos(eclLat * RAD) * sin(obliquity * RAD) * sin(eclLon * RAD)
        ) * DEG

        return Pair(norm360(ra), dec)
    }

    private fun sunEquatorial(T: Double): Pair<Double, Double> {
        val M = norm360(357.5291 + 35999.0503 * T)
        val C = 1.9146 * sin(M * RAD) + 0.0200 * sin(2 * M * RAD) + 0.0003 * sin(3 * M * RAD)
        val sunLon = norm360(M + C + 180.0 + 102.9372)
        val obliquity = 23.4393 - 0.0130 * T

        val ra = atan2(sin(sunLon * RAD) * cos(obliquity * RAD), cos(sunLon * RAD)) * DEG
        val dec = asin(sin(sunLon * RAD) * sin(obliquity * RAD)) * DEG
        return Pair(norm360(ra), dec)
    }

    internal fun equatorialToHorizontal(
        raDeg: Double,
        decDeg: Double,
        latDeg: Double,
        lstDeg: Double
    ): Pair<Double, Double> {
        val ha = (lstDeg - raDeg) * RAD
        val lat = latDeg * RAD
        val dec = decDeg * RAD

        val sinAlt = sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(ha)
        val alt = asin(clamp(sinAlt, -1.0, 1.0))

        val cosAz = (sin(dec) - sin(lat) * sin(alt)) / (cos(lat) * cos(alt) + 1e-12)
        var az = acos(clamp(cosAz, -1.0, 1.0))
        if (sin(ha) > 0) az = 2 * PI - az

        return Pair(alt * DEG, az * DEG)
    }

    private fun findRiseSet(latDeg: Double, lonDeg: Double, jdMidnight: Double): Pair<Double?, Double?> {
        var rise: Double? = null
        var set: Double? = null

        var prevAlt = moonAltitudeAt(latDeg, lonDeg, jdMidnight)
        for (h in 1..24) {
            val jd = jdMidnight + h / 24.0
            val alt = moonAltitudeAt(latDeg, lonDeg, jd)
            if (prevAlt < 0 && alt >= 0 && rise == null) {
                val frac = -prevAlt / (alt - prevAlt + 1e-12)
                rise = (h - 1) + frac
            }
            if (prevAlt >= 0 && alt < 0 && set == null) {
                val frac = prevAlt / (prevAlt - alt + 1e-12)
                set = (h - 1) + frac
            }
            prevAlt = alt
        }
        return Pair(rise, set)
    }

    private fun moonAltitudeAt(latDeg: Double, lonDeg: Double, jd: Double): Double {
        val T = (jd - J2000) / 36525.0
        val (ra, dec) = moonEquatorial(T)
        val gmst = greenwichMeanSiderealTime(jd)
        val lst = gmst + lonDeg
        val (alt, _) = equatorialToHorizontal(ra, dec, latDeg, lst)
        return alt - 0.5667 // atmospheric refraction correction
    }

    private fun computeMoonAge(jd: Double): Double {
        val knownNewMoon = 2451550.1
        val daysSinceNew = jd - knownNewMoon
        return ((daysSinceNew % SYNODIC_MONTH) + SYNODIC_MONTH) % SYNODIC_MONTH
    }

    private fun phaseName(illumination: Double, isWaxing: Boolean): String {
        val pct = illumination * 100.0
        return when {
            pct < 2.0 -> "New Moon"
            pct < 23.0 -> if (isWaxing) "Waxing Crescent" else "Waning Crescent"
            pct < 27.0 -> if (isWaxing) "First Quarter" else "Last Quarter"
            pct < 73.0 -> if (isWaxing) "Waxing Gibbous" else "Waning Gibbous"
            pct < 77.0 -> if (isWaxing) "Waxing Gibbous" else "Waning Gibbous"
            pct < 98.0 -> if (isWaxing) "Waxing Gibbous" else "Waning Gibbous"
            else -> "Full Moon"
        }
    }

    internal fun greenwichMeanSiderealTime(jd: Double): Double {
        val T = (jd - J2000) / 36525.0
        val gmst = 280.46061837 +
            360.98564736629 * (jd - J2000) +
            0.000387933 * T * T -
            T * T * T / 38710000.0
        return norm360(gmst)
    }

    internal fun toJulianDay(utcMillis: Long): Double {
        val daysSinceEpoch = utcMillis / 86_400_000.0
        return daysSinceEpoch + 2440587.5
    }

    private fun norm360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    private fun clamp(v: Double, lo: Double, hi: Double): Double = max(lo, min(hi, v))
}
