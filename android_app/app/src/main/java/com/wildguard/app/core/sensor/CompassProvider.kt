package com.wildguard.app.core.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class CompassProvider(
    private val sensorManager: SensorManager,
    private val rotationSensor: Sensor?,
    private val magneticSensor: Sensor?,
    private val accelerometerSensor: Sensor?
) : SensorEventListener {

    private var onHeading: ((Float) -> Unit)? = null
    private var filteredAzimuth: Float = 0f
    private var initialized = false

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasMagnetic = false

    private val useRotationVector = rotationSensor != null

    fun start(callback: (Float) -> Unit) {
        onHeading = callback
        if (useRotationVector) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            magneticSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            accelerometerSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        onHeading = null
        initialized = false
        hasGravity = false
        hasMagnetic = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event)
            Sensor.TYPE_ACCELEROMETER -> {
                lowPassCopy(event.values, gravity)
                hasGravity = true
                computeFallbackHeading()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lowPassCopy(event.values, geomagnetic)
                hasMagnetic = true
                computeFallbackHeading()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun handleRotationVector(event: SensorEvent) {
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val azimuthRad = orientation[0]
        val azimuthDeg = ((Math.toDegrees(azimuthRad.toDouble()).toFloat() + 360f) % 360f)
        emitFiltered(azimuthDeg)
    }

    private fun computeFallbackHeading() {
        if (!hasGravity || !hasMagnetic) return
        val r = FloatArray(9)
        val i = FloatArray(9)
        if (!SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) return
        val orientation = FloatArray(3)
        SensorManager.getOrientation(r, orientation)
        val azimuthDeg = ((Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f)
        emitFiltered(azimuthDeg)
    }

    private fun emitFiltered(rawDeg: Float) {
        if (!initialized) {
            filteredAzimuth = rawDeg
            initialized = true
        } else {
            filteredAzimuth = lowPassAngle(filteredAzimuth, rawDeg, ALPHA)
        }
        onHeading?.invoke(filteredAzimuth)
    }

    private fun lowPassCopy(input: FloatArray, output: FloatArray) {
        for (i in input.indices) {
            output[i] = output[i] + ALPHA * (input[i] - output[i])
        }
    }

    companion object {
        private const val ALPHA = 0.15f

        private fun lowPassAngle(prev: Float, next: Float, alpha: Float): Float {
            var delta = next - prev
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            return (prev + alpha * delta + 360f) % 360f
        }
    }
}
