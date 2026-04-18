package com.wildguard.app.modules.compass

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class Waypoint(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class WaypointBearing(
    val waypoint: Waypoint,
    val bearingDeg: Double,
    val distanceM: Double
)

class WaypointManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<Waypoint>>() {}.type

    fun loadAll(): List<Waypoint> {
        val json = prefs.getString(KEY_WAYPOINTS, null) ?: return emptyList()
        return try {
            gson.fromJson<MutableList<Waypoint>>(json, listType) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(waypoint: Waypoint): Boolean {
        val list = loadAll().toMutableList()
        if (list.size >= MAX_WAYPOINTS) return false
        list.add(waypoint)
        persist(list)
        return true
    }

    fun delete(id: String) {
        val list = loadAll().toMutableList()
        list.removeAll { it.id == id }
        persist(list)
    }

    fun update(waypoint: Waypoint) {
        val list = loadAll().toMutableList()
        val idx = list.indexOfFirst { it.id == waypoint.id }
        if (idx >= 0) {
            list[idx] = waypoint
            persist(list)
        }
    }

    fun computeBearings(fromLat: Double, fromLon: Double): List<WaypointBearing> =
        loadAll().map { wp ->
            WaypointBearing(
                waypoint = wp,
                bearingDeg = CompassCalculator.bearingTo(fromLat, fromLon, wp.lat, wp.lon),
                distanceM = CompassCalculator.distanceTo(fromLat, fromLon, wp.lat, wp.lon)
            )
        }

    private fun persist(list: List<Waypoint>) {
        prefs.edit().putString(KEY_WAYPOINTS, gson.toJson(list)).apply()
    }

    companion object {
        private const val PREFS_NAME = "wildguard_waypoints"
        private const val KEY_WAYPOINTS = "waypoints"
        const val MAX_WAYPOINTS = 100
    }
}
