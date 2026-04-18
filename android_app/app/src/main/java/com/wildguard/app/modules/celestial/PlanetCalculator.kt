package com.wildguard.app.modules.celestial

import kotlin.math.*

data class PlanetData(
    val name: String,
    val altitudeDeg: Double,
    val azimuthDeg: Double,
    val magnitude: Double,
    val isVisible: Boolean,
    val riseUtcHours: Double?,
    val setUtcHours: Double?
)

object PlanetCalculator {

    private const val RAD = PI / 180.0
    private const val DEG = 180.0 / PI
    private const val J2000 = 2451545.0

    private data class OrbitalElements(
        val name: String,
        val a: Double, val aRate: Double,           // semi-major axis (AU)
        val e: Double, val eRate: Double,           // eccentricity
        val I: Double, val IRate: Double,           // inclination (deg)
        val L: Double, val LRate: Double,           // mean longitude (deg)
        val wBar: Double, val wBarRate: Double,     // longitude of perihelion (deg)
        val omega: Double, val omegaRate: Double,   // longitude of ascending node (deg)
        val apparentMagV: Double                    // approximate visual magnitude at 1 AU
    )

    // J2000.0 Keplerian elements + centennial rates (JPL)
    private val EARTH = OrbitalElements(
        "Earth",
        a = 1.00000261, aRate = 0.00000562,
        e = 0.01671123, eRate = -0.00004392,
        I = -0.00001531, IRate = -0.01294668,
        L = 100.46457166, LRate = 35999.37244981,
        wBar = 102.93768193, wBarRate = 0.32327364,
        omega = 0.0, omegaRate = 0.0,
        apparentMagV = 0.0
    )

    private val PLANETS = arrayOf(
        OrbitalElements(
            "Mercury",
            a = 0.38709927, aRate = 0.00000037,
            e = 0.20563593, eRate = 0.00001906,
            I = 7.00497902, IRate = -0.00594749,
            L = 252.25032350, LRate = 149472.67411175,
            wBar = 77.45779628, wBarRate = 0.16047689,
            omega = 48.33076593, omegaRate = -0.12534081,
            apparentMagV = -0.36
        ),
        OrbitalElements(
            "Venus",
            a = 0.72333566, aRate = 0.00000390,
            e = 0.00677672, eRate = -0.00004107,
            I = 3.39467605, IRate = -0.00078890,
            L = 181.97909950, LRate = 58517.81538729,
            wBar = 131.60246718, wBarRate = 0.00268329,
            omega = 76.67984255, omegaRate = -0.27769418,
            apparentMagV = -4.34
        ),
        OrbitalElements(
            "Mars",
            a = 1.52371034, aRate = 0.00001847,
            e = 0.09339410, eRate = 0.00007882,
            I = 1.84969142, IRate = -0.00813131,
            L = -4.55343205, LRate = 19140.30268499,
            wBar = -23.94362959, wBarRate = 0.44441088,
            omega = 49.55953891, omegaRate = -0.29257343,
            apparentMagV = -1.60
        ),
        OrbitalElements(
            "Jupiter",
            a = 5.20288700, aRate = -0.00011607,
            e = 0.04838624, eRate = -0.00013253,
            I = 1.30439695, IRate = -0.00183714,
            L = 34.39644051, LRate = 3034.74612775,
            wBar = 14.72847983, wBarRate = 0.21252668,
            omega = 100.47390909, omegaRate = 0.20469106,
            apparentMagV = -2.94
        ),
        OrbitalElements(
            "Saturn",
            a = 9.53667594, aRate = -0.00125060,
            e = 0.05386179, eRate = -0.00050991,
            I = 2.48599187, IRate = 0.00193609,
            L = 49.95424423, LRate = 1222.49362201,
            wBar = 92.59887831, wBarRate = -0.41897216,
            omega = 113.66242448, omegaRate = -0.28867794,
            apparentMagV = -0.49
        )
    )

    fun computeAll(latDeg: Double, lonDeg: Double, utcMillis: Long): List<PlanetData> {
        val jd = MoonCalculator.toJulianDay(utcMillis)
        val T = (jd - J2000) / 36525.0

        val earthHelio = heliocentricPosition(EARTH, T)

        return PLANETS.map { planet ->
            computePlanet(planet, earthHelio, T, jd, latDeg, lonDeg)
        }
    }

    private fun computePlanet(
        planet: OrbitalElements,
        earthHelio: Triple<Double, Double, Double>,
        T: Double,
        jd: Double,
        latDeg: Double,
        lonDeg: Double
    ): PlanetData {
        val (xp, yp, zp) = heliocentricPosition(planet, T)
        val (xe, ye, ze) = earthHelio

        val xGeo = xp - xe
        val yGeo = yp - ye
        val zGeo = zp - ze

        val eclLon = atan2(yGeo, xGeo) * DEG
        val eclLat = atan2(zGeo, sqrt(xGeo * xGeo + yGeo * yGeo)) * DEG

        val obliquity = 23.4393 - 0.0130 * T
        val ra = atan2(
            sin(eclLon * RAD) * cos(obliquity * RAD) - tan(eclLat * RAD) * sin(obliquity * RAD),
            cos(eclLon * RAD)
        ) * DEG
        val dec = asin(
            clamp(
                sin(eclLat * RAD) * cos(obliquity * RAD) +
                    cos(eclLat * RAD) * sin(obliquity * RAD) * sin(eclLon * RAD),
                -1.0, 1.0
            )
        ) * DEG

        val gmst = MoonCalculator.greenwichMeanSiderealTime(jd)
        val lst = gmst + lonDeg
        val (alt, az) = MoonCalculator.equatorialToHorizontal(norm360(ra), dec, latDeg, lst)

        val dist = sqrt(xGeo * xGeo + yGeo * yGeo + zGeo * zGeo)
        val rHelio = sqrt(xp * xp + yp * yp + zp * zp)
        val magnitude = planet.apparentMagV + 5.0 * log10(rHelio * dist)

        val sunElongation = computeSunElongation(eclLon, eclLat, T)
        val isVisible = alt > 5.0 && sunElongation > 10.0

        val jdMidnight = floor(jd - 0.5) + 0.5
        val (rise, set) = findPlanetRiseSet(planet, earthHelio, T, jdMidnight, latDeg, lonDeg)

        return PlanetData(
            name = planet.name,
            altitudeDeg = alt,
            azimuthDeg = az,
            magnitude = magnitude,
            isVisible = isVisible,
            riseUtcHours = rise,
            setUtcHours = set
        )
    }

    private fun heliocentricPosition(elem: OrbitalElements, T: Double): Triple<Double, Double, Double> {
        val a = elem.a + elem.aRate * T
        val e = elem.e + elem.eRate * T
        val I = (elem.I + elem.IRate * T) * RAD
        val L = norm360(elem.L + elem.LRate * T)
        val wBar = norm360(elem.wBar + elem.wBarRate * T)
        val omega = norm360(elem.omega + elem.omegaRate * T) * RAD

        val w = (wBar - elem.omega - elem.omegaRate * T)
        val M = norm360(L - wBar) * RAD

        var E = M
        for (i in 0 until 15) {
            val dE = (M - E + e * sin(E)) / (1.0 - e * cos(E))
            E += dE
            if (abs(dE) < 1e-12) break
        }

        val xOrb = a * (cos(E) - e)
        val yOrb = a * sqrt(1.0 - e * e) * sin(E)

        val wRad = w * RAD
        val cosW = cos(wRad)
        val sinW = sin(wRad)
        val cosO = cos(omega)
        val sinO = sin(omega)
        val cosI = cos(I)
        val sinI = sin(I)

        val x = (cosW * cosO - sinW * sinO * cosI) * xOrb +
            (-sinW * cosO - cosW * sinO * cosI) * yOrb
        val y = (cosW * sinO + sinW * cosO * cosI) * xOrb +
            (-sinW * sinO + cosW * cosO * cosI) * yOrb
        val z = (sinW * sinI) * xOrb + (cosW * sinI) * yOrb

        return Triple(x, y, z)
    }

    private fun computeSunElongation(geoEclLon: Double, geoEclLat: Double, T: Double): Double {
        val sunLon = sunEclipticLon(T)
        val cosE = cos(geoEclLat * RAD) * cos((geoEclLon - sunLon) * RAD)
        return acos(clamp(cosE, -1.0, 1.0)) * DEG
    }

    private fun sunEclipticLon(T: Double): Double {
        val M = norm360(357.5291 + 35999.0503 * T)
        val C = 1.9146 * sin(M * RAD) + 0.02 * sin(2 * M * RAD)
        return norm360(M + C + 180.0 + 102.9372)
    }

    private fun findPlanetRiseSet(
        planet: OrbitalElements,
        earthHelio: Triple<Double, Double, Double>,
        T: Double,
        jdMidnight: Double,
        latDeg: Double,
        lonDeg: Double
    ): Pair<Double?, Double?> {
        var rise: Double? = null
        var set: Double? = null

        fun altAt(h: Int): Double {
            val jd = jdMidnight + h / 24.0
            val Th = (jd - J2000) / 36525.0
            val (xp, yp, zp) = heliocentricPosition(planet, Th)
            val xGeo = xp - earthHelio.first
            val yGeo = yp - earthHelio.second
            val zGeo = zp - earthHelio.third
            val eclLon = atan2(yGeo, xGeo) * DEG
            val eclLat = atan2(zGeo, sqrt(xGeo * xGeo + yGeo * yGeo)) * DEG
            val obliquity = 23.4393 - 0.0130 * Th
            val ra = norm360(
                atan2(
                    sin(eclLon * RAD) * cos(obliquity * RAD) - tan(eclLat * RAD) * sin(obliquity * RAD),
                    cos(eclLon * RAD)
                ) * DEG
            )
            val dec = asin(
                clamp(
                    sin(eclLat * RAD) * cos(obliquity * RAD) +
                        cos(eclLat * RAD) * sin(obliquity * RAD) * sin(eclLon * RAD),
                    -1.0, 1.0
                )
            ) * DEG
            val gmst = MoonCalculator.greenwichMeanSiderealTime(jd)
            val lst = gmst + lonDeg
            val (alt, _) = MoonCalculator.equatorialToHorizontal(ra, dec, latDeg, lst)
            return alt
        }

        var prevAlt = altAt(0)
        for (h in 1..24) {
            val alt = altAt(h)
            if (prevAlt < 0 && alt >= 0 && rise == null) {
                rise = (h - 1) + (-prevAlt / (alt - prevAlt + 1e-12))
            }
            if (prevAlt >= 0 && alt < 0 && set == null) {
                set = (h - 1) + (prevAlt / (prevAlt - alt + 1e-12))
            }
            prevAlt = alt
        }
        return Pair(rise, set)
    }

    private fun norm360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    private fun clamp(v: Double, lo: Double, hi: Double): Double = max(lo, min(hi, v))
}
