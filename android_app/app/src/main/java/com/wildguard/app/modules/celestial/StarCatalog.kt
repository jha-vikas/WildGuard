package com.wildguard.app.modules.celestial

import kotlin.math.*

data class StarData(
    val name: String,
    val constellation: String,
    val magnitude: Double,
    val altitudeDeg: Double,
    val azimuthDeg: Double,
    val isAboveHorizon: Boolean
)

object StarCatalog {

    private const val RAD = PI / 180.0

    private data class CatalogEntry(
        val name: String,
        val raHours: Double,
        val decDeg: Double,
        val magnitude: Double,
        val constellation: String
    )

    private val STARS = listOf(
        CatalogEntry("Sirius", 6.752, -16.716, -1.46, "Canis Major"),
        CatalogEntry("Canopus", 6.399, -52.696, -0.74, "Carina"),
        CatalogEntry("Arcturus", 14.261, 19.182, -0.05, "Bootes"),
        CatalogEntry("Rigil Kentaurus", 14.660, -60.835, -0.01, "Centaurus"),
        CatalogEntry("Vega", 18.616, 38.784, 0.03, "Lyra"),
        CatalogEntry("Capella", 5.278, 45.998, 0.08, "Auriga"),
        CatalogEntry("Rigel", 5.242, -8.202, 0.13, "Orion"),
        CatalogEntry("Procyon", 7.655, 5.225, 0.34, "Canis Minor"),
        CatalogEntry("Achernar", 1.629, -57.237, 0.46, "Eridanus"),
        CatalogEntry("Betelgeuse", 5.920, 7.407, 0.50, "Orion"),
        CatalogEntry("Hadar", 14.064, -60.373, 0.61, "Centaurus"),
        CatalogEntry("Altair", 19.846, 8.868, 0.77, "Aquila"),
        CatalogEntry("Acrux", 12.443, -63.100, 0.77, "Crux"),
        CatalogEntry("Aldebaran", 4.599, 16.509, 0.85, "Taurus"),
        CatalogEntry("Antares", 16.490, -26.432, 0.96, "Scorpius"),
        CatalogEntry("Spica", 13.420, -11.161, 0.97, "Virgo"),
        CatalogEntry("Pollux", 7.755, 28.026, 1.14, "Gemini"),
        CatalogEntry("Fomalhaut", 22.961, -29.622, 1.16, "Piscis Austrinus"),
        CatalogEntry("Deneb", 20.690, 45.280, 1.25, "Cygnus"),
        CatalogEntry("Mimosa", 12.795, -59.689, 1.25, "Crux"),
        CatalogEntry("Regulus", 10.140, 11.967, 1.35, "Leo"),
        CatalogEntry("Adhara", 6.977, -28.972, 1.50, "Canis Major"),
        CatalogEntry("Shaula", 17.560, -37.104, 1.63, "Scorpius"),
        CatalogEntry("Castor", 7.577, 31.888, 1.58, "Gemini"),
        CatalogEntry("Gacrux", 12.519, -57.113, 1.63, "Crux"),
        CatalogEntry("Bellatrix", 5.419, 6.350, 1.64, "Orion"),
        CatalogEntry("Elnath", 5.438, 28.608, 1.65, "Taurus"),
        CatalogEntry("Miaplacidus", 9.220, -69.717, 1.68, "Carina"),
        CatalogEntry("Alnilam", 5.604, -1.202, 1.69, "Orion"),
        CatalogEntry("Alnair", 22.137, -46.961, 1.74, "Grus"),
        CatalogEntry("Alnitak", 5.679, -1.943, 1.77, "Orion"),
        CatalogEntry("Alioth", 12.900, 55.960, 1.77, "Ursa Major"),
        CatalogEntry("Dubhe", 11.062, 61.751, 1.79, "Ursa Major"),
        CatalogEntry("Mirfak", 3.405, 49.861, 1.80, "Perseus"),
        CatalogEntry("Wezen", 7.140, -26.393, 1.84, "Canis Major"),
        CatalogEntry("Sargas", 17.622, -42.998, 1.87, "Scorpius"),
        CatalogEntry("Kaus Australis", 18.403, -34.385, 1.85, "Sagittarius"),
        CatalogEntry("Avior", 8.375, -59.510, 1.86, "Carina"),
        CatalogEntry("Alkaid", 13.792, 49.313, 1.86, "Ursa Major"),
        CatalogEntry("Menkalinan", 5.992, 44.948, 1.90, "Auriga"),
        CatalogEntry("Atria", 16.811, -69.028, 1.92, "Triangulum Australe"),
        CatalogEntry("Alhena", 6.629, 16.399, 1.93, "Gemini"),
        CatalogEntry("Peacock", 20.427, -56.735, 1.94, "Pavo"),
        CatalogEntry("Alsephina", 8.158, -47.337, 1.96, "Vela"),
        CatalogEntry("Mirzam", 6.378, -17.956, 1.98, "Canis Major"),
        CatalogEntry("Alphard", 9.460, -8.659, 1.98, "Hydra"),
        CatalogEntry("Polaris", 2.530, 89.264, 1.98, "Ursa Minor"),
        CatalogEntry("Hamal", 2.120, 23.462, 2.00, "Aries"),
        CatalogEntry("Diphda", 0.727, -17.987, 2.02, "Cetus"),
        CatalogEntry("Nunki", 18.921, -26.297, 2.02, "Sagittarius")
    )

    fun computeVisible(latDeg: Double, lonDeg: Double, utcMillis: Long): List<StarData> {
        val jd = MoonCalculator.toJulianDay(utcMillis)
        val gmst = MoonCalculator.greenwichMeanSiderealTime(jd)
        val lst = gmst + lonDeg

        return STARS.map { star ->
            val raDeg = star.raHours * 15.0
            val (alt, az) = MoonCalculator.equatorialToHorizontal(raDeg, star.decDeg, latDeg, lst)
            StarData(
                name = star.name,
                constellation = star.constellation,
                magnitude = star.magnitude,
                altitudeDeg = alt,
                azimuthDeg = az,
                isAboveHorizon = alt > 0.0
            )
        }
    }

    fun getVisibleAbove(latDeg: Double, lonDeg: Double, utcMillis: Long, minAltDeg: Double = 10.0): List<StarData> {
        return computeVisible(latDeg, lonDeg, utcMillis)
            .filter { it.altitudeDeg > minAltDeg }
            .sortedBy { it.magnitude }
    }

    fun getNavigationStars(latDeg: Double, lonDeg: Double, utcMillis: Long): List<StarData> {
        val allStars = computeVisible(latDeg, lonDeg, utcMillis)
        val navNames = setOf(
            "Polaris", "Sirius", "Canopus", "Arcturus", "Vega",
            "Rigel", "Procyon", "Altair", "Deneb", "Acrux",
            "Mimosa", "Gacrux", "Fomalhaut", "Antares", "Spica"
        )
        return allStars.filter { it.name in navNames && it.isAboveHorizon }
    }

    fun findPolaris(latDeg: Double, lonDeg: Double, utcMillis: Long): StarData? {
        val allStars = computeVisible(latDeg, lonDeg, utcMillis)
        return allStars.find { it.name == "Polaris" && it.isAboveHorizon }
    }

    fun getSouthernCross(latDeg: Double, lonDeg: Double, utcMillis: Long): List<StarData> {
        val crossNames = setOf("Acrux", "Mimosa", "Gacrux")
        return computeVisible(latDeg, lonDeg, utcMillis)
            .filter { it.name in crossNames && it.isAboveHorizon }
    }
}
