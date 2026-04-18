package com.wildguard.app.modules.tide

import kotlin.math.*

data class TidalStation(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val meanSeaLevel: Double,
    val constituents: List<HarmonicConstituent>
)

data class HarmonicConstituent(
    val name: String,
    val amplitude: Double,
    val phase: Double,
    val speed: Double
)

data class TideExtreme(
    val timeMillis: Long,
    val heightM: Double,
    val isHigh: Boolean
)

object TideCalculator {

    private const val RAD = PI / 180.0

    private data class ConstituentConstants(
        val name: String,
        val speed: Double,
        val doodsonMultipliers: IntArray
    )

    private val CONSTITUENT_DEFS = arrayOf(
        ConstituentConstants("M2", 28.9841042, intArrayOf(2, -2, 2, 0, 0, 0)),
        ConstituentConstants("S2", 30.0, intArrayOf(2, 0, 0, 0, 0, 0)),
        ConstituentConstants("N2", 28.4397295, intArrayOf(2, -3, 2, 1, 0, 0)),
        ConstituentConstants("K1", 15.0410686, intArrayOf(1, 0, 1, 0, 0, -1)),
        ConstituentConstants("O1", 13.9430356, intArrayOf(1, -2, 1, 0, 0, 1)),
        ConstituentConstants("K2", 30.0821373, intArrayOf(2, 0, 2, 0, 0, 0)),
        ConstituentConstants("P1", 14.9589314, intArrayOf(1, 0, -1, 0, 0, 1)),
        ConstituentConstants("M4", 57.9682084, intArrayOf(4, -4, 4, 0, 0, 0))
    )

    fun computeTideHeight(station: TidalStation, timeMillis: Long): Double {
        val T = julianCenturies(timeMillis)
        val N = lunarNodeLongitude(T)
        val hoursSinceEpoch = timeMillis / 3_600_000.0

        var h = station.meanSeaLevel

        for (constituent in station.constituents) {
            val (f, u) = nodalCorrections(constituent.name, N)
            val V = astronomicalArgument(constituent.name, T, hoursSinceEpoch)

            val angle = constituent.speed * hoursSinceEpoch + V + u - constituent.phase
            h += f * constituent.amplitude * cos(angle * RAD)
        }

        return h
    }

    fun computeTideCurve(
        station: TidalStation,
        startMillis: Long,
        endMillis: Long,
        intervalMinutes: Int = 10
    ): List<Pair<Long, Double>> {
        val intervalMs = intervalMinutes * 60_000L
        val result = mutableListOf<Pair<Long, Double>>()
        var t = startMillis
        while (t <= endMillis) {
            result.add(Pair(t, computeTideHeight(station, t)))
            t += intervalMs
        }
        return result
    }

    fun findHighLowTides(station: TidalStation, startMillis: Long, hours: Int = 48): List<TideExtreme> {
        val intervalMs = 6 * 60_000L // 6-minute intervals for precision
        val endMillis = startMillis + hours * 3_600_000L
        val extremes = mutableListOf<TideExtreme>()

        var prevH = computeTideHeight(station, startMillis)
        var prevPrevH = computeTideHeight(station, startMillis - intervalMs)
        var t = startMillis + intervalMs

        while (t <= endMillis) {
            val h = computeTideHeight(station, t)

            if (prevH > prevPrevH && prevH > h) {
                val refinedTime = refineExtreme(station, t - 2 * intervalMs, t, isHigh = true)
                extremes.add(
                    TideExtreme(
                        timeMillis = refinedTime,
                        heightM = computeTideHeight(station, refinedTime),
                        isHigh = true
                    )
                )
            } else if (prevH < prevPrevH && prevH < h) {
                val refinedTime = refineExtreme(station, t - 2 * intervalMs, t, isHigh = false)
                extremes.add(
                    TideExtreme(
                        timeMillis = refinedTime,
                        heightM = computeTideHeight(station, refinedTime),
                        isHigh = false
                    )
                )
            }

            prevPrevH = prevH
            prevH = h
            t += intervalMs
        }

        return extremes
    }

    private fun refineExtreme(
        station: TidalStation,
        startMs: Long,
        endMs: Long,
        isHigh: Boolean
    ): Long {
        var lo = startMs
        var hi = endMs

        repeat(20) {
            val m1 = lo + (hi - lo) / 3
            val m2 = hi - (hi - lo) / 3
            val h1 = computeTideHeight(station, m1)
            val h2 = computeTideHeight(station, m2)
            if (isHigh) {
                if (h1 < h2) lo = m1 else hi = m2
            } else {
                if (h1 > h2) lo = m1 else hi = m2
            }
        }

        return (lo + hi) / 2
    }

    private fun julianCenturies(timeMillis: Long): Double {
        val jd = timeMillis / 86_400_000.0 + 2440587.5
        return (jd - 2451545.0) / 36525.0
    }

    private fun lunarNodeLongitude(T: Double): Double {
        return ((259.1568 - 19.3282 * T * 36525.0 / 365.25) % 360.0 + 360.0) % 360.0
    }

    private fun nodalCorrections(constituentName: String, N: Double): Pair<Double, Double> {
        val Nrad = N * RAD
        return when (constituentName) {
            "M2" -> {
                val f = 1.0 - 0.0373 * cos(Nrad)
                val u = -2.14 * sin(Nrad)
                Pair(f, u)
            }
            "S2" -> Pair(1.0, 0.0)
            "N2" -> {
                val f = 1.0 - 0.0373 * cos(Nrad)
                val u = -2.14 * sin(Nrad)
                Pair(f, u)
            }
            "K1" -> {
                val f = 1.006 + 0.115 * cos(Nrad)
                val u = -8.86 * sin(Nrad)
                Pair(f, u)
            }
            "O1" -> {
                val f = 1.009 + 0.187 * cos(Nrad)
                val u = 10.8 * sin(Nrad)
                Pair(f, u)
            }
            "K2" -> {
                val f = 1.024 + 0.286 * cos(Nrad)
                val u = -17.74 * sin(Nrad)
                Pair(f, u)
            }
            "P1" -> Pair(1.0, 0.0)
            "M4" -> {
                val fM2 = 1.0 - 0.0373 * cos(Nrad)
                val uM2 = -2.14 * sin(Nrad)
                Pair(fM2 * fM2, 2 * uM2)
            }
            else -> Pair(1.0, 0.0)
        }
    }

    private fun astronomicalArgument(constituentName: String, T: Double, hoursSinceEpoch: Double): Double {
        val s = (218.3165 + 481267.8813 * T) % 360.0  // mean longitude of moon
        val h = (280.4664 + 36000.7698 * T) % 360.0   // mean longitude of sun
        val p = (83.3532 + 4069.0137 * T) % 360.0     // longitude of lunar perigee
        val N = (234.955 + 1934.1363 * T) % 360.0      // longitude of lunar node

        return when (constituentName) {
            "M2" -> (2 * (h - s)) % 360.0
            "S2" -> 0.0
            "N2" -> (2 * h - 3 * s + p) % 360.0
            "K1" -> (h + 90.0) % 360.0
            "O1" -> (-2 * s + h - 90.0) % 360.0
            "K2" -> (2 * h) % 360.0
            "P1" -> (h - 90.0) % 360.0
            "M4" -> (4 * (h - s)) % 360.0
            else -> 0.0
        }
    }

    fun isRising(station: TidalStation, timeMillis: Long): Boolean {
        val dt = 10 * 60_000L
        return computeTideHeight(station, timeMillis + dt) > computeTideHeight(station, timeMillis)
    }

    fun springNeapIndicator(moonIlluminationPercent: Double): String {
        return when {
            moonIlluminationPercent < 5 || moonIlluminationPercent > 95 -> "Spring Tide"
            moonIlluminationPercent in 45.0..55.0 -> "Neap Tide"
            moonIlluminationPercent < 25 || moonIlluminationPercent > 75 -> "Near Spring"
            else -> "Near Neap"
        }
    }
}
