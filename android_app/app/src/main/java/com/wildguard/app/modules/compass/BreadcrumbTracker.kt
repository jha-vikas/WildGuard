package com.wildguard.app.modules.compass

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val timestamp: Long
)

data class TrackStats(
    val totalDistanceM: Double,
    val elapsedMs: Long,
    val averageSpeedMps: Double,
    val wayBackBearingDeg: Double?,
    val pointCount: Int
)

class BreadcrumbTracker(private val context: Context) {

    private val gson = Gson()
    private val trackPoints = mutableListOf<TrackPoint>()
    private var lastRecordTime = 0L

    var intervalMs: Long = DEFAULT_INTERVAL_MS
    var minMovementM: Double = MIN_MOVEMENT_M

    val points: List<TrackPoint> get() = trackPoints.toList()
    val isActive: Boolean get() = trackPoints.isNotEmpty()

    fun recordIfDue(lat: Double, lon: Double, altitudeM: Double?) {
        val now = System.currentTimeMillis()
        if (now - lastRecordTime < intervalMs) return

        if (trackPoints.isNotEmpty()) {
            val last = trackPoints.last()
            val dist = CompassCalculator.distanceTo(last.lat, last.lon, lat, lon)
            if (dist < minMovementM) return
        }

        trackPoints.add(TrackPoint(lat, lon, altitudeM, now))
        lastRecordTime = now
        persistToFile()
    }

    fun getStats(currentLat: Double, currentLon: Double): TrackStats {
        var totalDist = 0.0
        for (i in 1 until trackPoints.size) {
            val prev = trackPoints[i - 1]
            val curr = trackPoints[i]
            totalDist += CompassCalculator.distanceTo(prev.lat, prev.lon, curr.lat, curr.lon)
        }

        val elapsed = if (trackPoints.size >= 2)
            trackPoints.last().timestamp - trackPoints.first().timestamp
        else 0L

        val avgSpeed = if (elapsed > 0) totalDist / (elapsed / 1000.0) else 0.0

        val wayBackBearing = if (trackPoints.isNotEmpty()) {
            CompassCalculator.bearingTo(currentLat, currentLon, trackPoints.first().lat, trackPoints.first().lon)
        } else null

        return TrackStats(
            totalDistanceM = totalDist,
            elapsedMs = elapsed,
            averageSpeedMps = avgSpeed,
            wayBackBearingDeg = wayBackBearing,
            pointCount = trackPoints.size
        )
    }

    fun clear() {
        trackPoints.clear()
        lastRecordTime = 0L
        getTrackFile().delete()
    }

    fun restoreFromFile() {
        val file = getTrackFile()
        if (!file.exists()) return
        try {
            val json = file.readText()
            val type = object : TypeToken<List<TrackPoint>>() {}.type
            val restored: List<TrackPoint> = gson.fromJson(json, type) ?: return
            trackPoints.clear()
            trackPoints.addAll(restored)
            if (trackPoints.isNotEmpty()) lastRecordTime = trackPoints.last().timestamp
        } catch (_: Exception) { }
    }

    private fun persistToFile() {
        try {
            getTrackFile().writeText(gson.toJson(trackPoints))
        } catch (_: Exception) { }
    }

    private fun getTrackFile(): File =
        File(context.filesDir, TRACK_FILENAME)

    companion object {
        private const val TRACK_FILENAME = "breadcrumb_track.json"
        const val DEFAULT_INTERVAL_MS = 30_000L
        const val MIN_MOVEMENT_M = 5.0
    }
}
