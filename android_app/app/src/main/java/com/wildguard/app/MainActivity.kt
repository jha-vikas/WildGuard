package com.wildguard.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.wildguard.app.ui.WildGuardNavigation
import com.wildguard.app.ui.theme.WildGuardTheme

class MainActivity : ComponentActivity() {

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* ViewModel observes permission state via sensor providers */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestLocationIfNeeded()

        val modeController = WildGuardApp.instance.modeController

        setContent {
            val visualMode by modeController.currentMode.collectAsState()

            WildGuardTheme(visualMode = visualMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WildGuardNavigation(
                        currentVisualMode = visualMode,
                        onVisualModeSelected = { modeController.setUserOverride(it) }
                    )
                }
            }
        }
    }

    private fun requestLocationIfNeeded() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }
}
