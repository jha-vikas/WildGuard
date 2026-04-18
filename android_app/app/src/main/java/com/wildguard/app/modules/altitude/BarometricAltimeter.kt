package com.wildguard.app.modules.altitude

import kotlin.math.pow

enum class AltitudeSource { BAROMETER, GPS, ESTIMATED }

data class AltitudeReading(
    val altitudeM: Double,
    val source: AltitudeSource,
    val timestampMillis: Long
)

class BarometricAltimeter {

    private var referencePressureOffset: Double? = null
    private var lastGpsAltitude: Double? = null
    private var lastBaroAltitude: Double? = null
    private var currentSource: AltitudeSource = AltitudeSource.ESTIMATED

    val altitudeMeters: Double
        get() = lastBaroAltitude ?: lastGpsAltitude ?: 0.0

    val altitudeSource: AltitudeSource
        get() = currentSource

    fun updatePressure(pressureHpa: Float): Double {
        val rawAltitude = pressureToAltitude(pressureHpa.toDouble())
        val calibrated = referencePressureOffset?.let { rawAltitude + it } ?: rawAltitude
        lastBaroAltitude = calibrated
        currentSource = if (referencePressureOffset != null) AltitudeSource.BAROMETER
                        else AltitudeSource.ESTIMATED
        return calibrated
    }

    fun calibrateWithGps(gpsAltitudeM: Double, currentPressureHpa: Float? = null) {
        lastGpsAltitude = gpsAltitudeM
        if (currentPressureHpa != null) {
            val baroRaw = pressureToAltitude(currentPressureHpa.toDouble())
            referencePressureOffset = gpsAltitudeM - baroRaw
            currentSource = AltitudeSource.BAROMETER
        } else if (referencePressureOffset == null) {
            currentSource = AltitudeSource.GPS
        }
    }

    fun currentReading(): AltitudeReading = AltitudeReading(
        altitudeM = altitudeMeters,
        source = altitudeSource,
        timestampMillis = System.currentTimeMillis()
    )

    companion object {
        private const val STANDARD_PRESSURE = 1013.25
        private const val EXPONENT = 1.0 / 5.255

        fun pressureToAltitude(pressureHpa: Double): Double =
            44330.0 * (1.0 - (pressureHpa / STANDARD_PRESSURE).pow(EXPONENT))
    }
}
