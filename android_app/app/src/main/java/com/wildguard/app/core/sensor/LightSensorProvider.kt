package com.wildguard.app.core.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class LightSensorProvider(
    private val sensorManager: SensorManager,
    private val sensor: Sensor
) : SensorEventListener {

    private var onLux: ((Float) -> Unit)? = null

    fun start(callback: (Float) -> Unit) {
        onLux = callback
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        onLux = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            onLux?.invoke(event.values[0])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
