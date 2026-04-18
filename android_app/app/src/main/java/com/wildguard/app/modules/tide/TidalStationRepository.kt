package com.wildguard.app.modules.tide

import com.wildguard.app.core.data.AssetRepository
import kotlin.math.*

class TidalStationRepository(private val assetRepository: AssetRepository) {

    private var stations: List<TidalStation>? = null

    private data class StationJson(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val meanSeaLevel: Double,
        val constituents: List<ConstituentJson>
    )

    private data class ConstituentJson(
        val name: String,
        val amplitude: Double,
        val phase: Double,
        val speed: Double
    )

    fun loadStations(): List<TidalStation> {
        stations?.let { return it }

        return try {
            val jsonList = assetRepository.readJsonList<StationJson>("tidal_stations.json")
            val loaded = jsonList.map { sj ->
                TidalStation(
                    id = sj.id,
                    name = sj.name,
                    lat = sj.lat,
                    lon = sj.lon,
                    meanSeaLevel = sj.meanSeaLevel,
                    constituents = sj.constituents.map { cj ->
                        HarmonicConstituent(
                            name = cj.name,
                            amplitude = cj.amplitude,
                            phase = cj.phase,
                            speed = cj.speed
                        )
                    }
                )
            }
            stations = loaded
            loaded
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun findNearest(lat: Double, lon: Double): TidalStation? {
        return findNearby(lat, lon, radiusKm = 100.0).minByOrNull {
            haversineKm(lat, lon, it.lat, it.lon)
        }
    }

    fun findNearby(lat: Double, lon: Double, radiusKm: Double): List<TidalStation> {
        val allStations = loadStations()
        return allStations.filter { station ->
            haversineKm(lat, lon, station.lat, station.lon) <= radiusKm
        }.sortedBy { haversineKm(lat, lon, it.lat, it.lon) }
    }

    fun distanceTo(lat: Double, lon: Double, station: TidalStation): Double {
        return haversineKm(lat, lon, station.lat, station.lon)
    }

    companion object {
        private const val EARTH_RADIUS_KM = 6371.0

        fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
            val c = 2 * asin(sqrt(a))
            return EARTH_RADIUS_KM * c
        }
    }
}
