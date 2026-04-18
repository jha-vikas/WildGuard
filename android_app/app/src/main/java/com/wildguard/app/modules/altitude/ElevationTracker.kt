package com.wildguard.app.modules.altitude

import kotlin.math.abs

data class AltitudePoint(
    val timestampMillis: Long,
    val altitudeM: Double
)

class ElevationTracker {

    private val _history = mutableListOf<AltitudePoint>()
    private var _totalGainM: Double = 0.0
    private var _totalLossM: Double = 0.0
    private var lastRecordedAltitude: Double? = null

    val totalGainM: Double get() = _totalGainM
    val totalLossM: Double get() = _totalLossM

    val currentAltitudeM: Double
        get() = _history.lastOrNull()?.altitudeM ?: 0.0

    val altitudeHistory: List<AltitudePoint>
        get() = _history.toList()

    fun recordAltitude(altitudeM: Double, timestampMillis: Long = System.currentTimeMillis()) {
        _history.add(AltitudePoint(timestampMillis, altitudeM))
        trimHistory()

        val prev = lastRecordedAltitude
        if (prev != null) {
            val delta = altitudeM - prev
            if (abs(delta) > NOISE_THRESHOLD_M) {
                if (delta > 0) _totalGainM += delta
                else _totalLossM += abs(delta)
                lastRecordedAltitude = altitudeM
            }
        } else {
            lastRecordedAltitude = altitudeM
        }
    }

    fun reset() {
        _history.clear()
        _totalGainM = 0.0
        _totalLossM = 0.0
        lastRecordedAltitude = null
    }

    private fun trimHistory() {
        val cutoff = System.currentTimeMillis() - MAX_HISTORY_MS
        _history.removeAll { it.timestampMillis < cutoff }
    }

    companion object {
        const val NOISE_THRESHOLD_M = 2.0
        private const val MAX_HISTORY_MS = 48 * 3_600_000L
    }
}
