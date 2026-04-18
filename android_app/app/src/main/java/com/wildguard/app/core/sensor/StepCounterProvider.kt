package com.wildguard.app.core.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class StepCounterProvider(
    private val sensorManager: SensorManager,
    private val sensor: Sensor
) : SensorEventListener {

    private var onSteps: ((Int) -> Unit)? = null
    private var initialSteps: Int? = null

    fun start(callback: (Int) -> Unit) {
        onSteps = callback
        initialSteps = null
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        onSteps = null
        initialSteps = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val cumulative = event.values[0].toInt()
            if (initialSteps == null) {
                initialSteps = cumulative
            }
            val delta = cumulative - (initialSteps ?: cumulative)
            onSteps?.invoke(delta)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
