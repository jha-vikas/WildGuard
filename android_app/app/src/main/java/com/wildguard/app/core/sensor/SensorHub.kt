package com.wildguard.app.core.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.wildguard.app.core.model.SensorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SensorHub(private val context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val _state = MutableStateFlow(SensorState())
    val state: StateFlow<SensorState> = _state.asStateFlow()

    private var gpsProvider: GpsProvider? = null
    private var barometerProvider: BarometerProvider? = null
    private var lightProvider: LightSensorProvider? = null
    private var compassProvider: CompassProvider? = null
    private var stepProvider: StepCounterProvider? = null

    init {
        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        gpsProvider = GpsProvider(context)

        if (pressureSensor != null) {
            barometerProvider = BarometerProvider(sensorManager, pressureSensor)
            update { it.copy(hasBarometer = true) }
        }

        if (lightSensor != null) {
            lightProvider = LightSensorProvider(sensorManager, lightSensor)
            update { it.copy(hasLightSensor = true) }
        }

        val hasCompassHardware = rotationSensor != null || (magneticSensor != null && accelSensor != null)
        if (hasCompassHardware) {
            compassProvider = CompassProvider(sensorManager, rotationSensor, magneticSensor, accelSensor)
            update { it.copy(hasCompass = true) }
        }

        if (stepSensor != null) {
            stepProvider = StepCounterProvider(sensorManager, stepSensor)
            update { it.copy(hasStepCounter = true) }
        }
    }

    fun start() {
        gpsProvider?.start { loc ->
            update { it.copy(location = loc, gpsAcquired = true) }
        }
        barometerProvider?.start { hPa ->
            update { it.copy(pressureHpa = hPa) }
        }
        lightProvider?.start { lux ->
            update { it.copy(lightLux = lux) }
        }
        compassProvider?.start { heading ->
            update { it.copy(compassHeadingDeg = heading) }
        }
        stepProvider?.start { steps ->
            update { it.copy(stepCount = steps) }
        }
    }

    fun stop() {
        gpsProvider?.stop()
        barometerProvider?.stop()
        lightProvider?.stop()
        compassProvider?.stop()
        stepProvider?.stop()
    }

    private inline fun update(transform: (SensorState) -> SensorState) {
        _state.value = transform(_state.value)
    }
}
