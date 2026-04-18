package com.wildguard.app.core.model

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val altitudeGps: Double?,
    val accuracy: Float,
    val speedMps: Float?,
    val bearingGps: Float?,
    val timestamp: Long
)

data class SensorState(
    val location: LocationData? = null,
    val pressureHpa: Float? = null,
    val lightLux: Float? = null,
    val compassHeadingDeg: Float? = null,
    val stepCount: Int? = null,
    val hasBarometer: Boolean = false,
    val hasLightSensor: Boolean = false,
    val hasCompass: Boolean = false,
    val hasStepCounter: Boolean = false,
    val gpsAcquired: Boolean = false
)

data class UserObservations(
    val temperatureC: Double? = null,
    val humidityPercent: Double? = null,
    val windSpeedKmh: Double? = null,
    val windDirectionDeg: Double? = null,
    val beaufortScale: Int? = null,
    val cloudType: String? = null,
    val observedAt: Long = System.currentTimeMillis()
) {
    val isStale: Boolean
        get() = System.currentTimeMillis() - observedAt > 2 * 3600_000L
}
