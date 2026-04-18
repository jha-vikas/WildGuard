package com.wildguard.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.wildguard.app.ui.screens.*
import com.wildguard.app.ui.theme.VisualMode

object Routes {
    const val DASHBOARD = "dashboard"
    const val UV = "uv"
    const val WEATHER = "weather"
    const val ALTITUDE = "altitude"
    const val CELESTIAL = "celestial"
    const val TIDE = "tide"
    const val COMPASS = "compass"
    const val THERMAL = "thermal"
    const val CONDITIONS = "conditions"
    const val INSIGHTS = "insights"
    const val SETTINGS = "settings"
    const val HELP = "help"
}

@Composable
fun WildGuardNavigation(
    currentVisualMode: VisualMode = VisualMode.STANDARD,
    onVisualModeSelected: (VisualMode) -> Unit = {}
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) {
            DashboardScreen(navController, currentVisualMode, onVisualModeSelected)
        }
        composable(Routes.UV) { UVScreen(navController) }
        composable(Routes.WEATHER) { WeatherScreen(navController) }
        composable(Routes.ALTITUDE) { AltitudeScreen(navController) }
        composable(Routes.CELESTIAL) { CelestialScreen(navController) }
        composable(Routes.TIDE) { TideScreen(navController) }
        composable(Routes.COMPASS) { CompassScreen(navController) }
        composable(Routes.THERMAL) { ThermalScreen(navController) }
        composable(Routes.CONDITIONS) { ConditionsCheckInScreen(navController) }
        composable(Routes.INSIGHTS) { InsightScreen(navController) }
        composable(Routes.SETTINGS) { SettingsScreen(navController) }
        composable(Routes.HELP) { HelpScreen(navController) }
    }
}
