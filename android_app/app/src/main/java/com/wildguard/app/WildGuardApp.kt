package com.wildguard.app

import android.app.Application
import com.wildguard.app.core.sensor.SensorHub
import com.wildguard.app.ui.theme.ModeController

class WildGuardApp : Application() {

    lateinit var sensorHub: SensorHub
        private set

    val modeController = ModeController()

    override fun onCreate() {
        super.onCreate()
        instance = this
        sensorHub = SensorHub(this)
        sensorHub.start()
    }

    override fun onTerminate() {
        sensorHub.stop()
        super.onTerminate()
    }

    companion object {
        lateinit var instance: WildGuardApp
            private set
    }
}
