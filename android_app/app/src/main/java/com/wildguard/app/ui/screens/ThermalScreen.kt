package com.wildguard.app.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildguard.app.WildGuardApp
import com.wildguard.app.core.model.UserObservations
import com.wildguard.app.modules.conditions.ConditionsCheckIn
import com.wildguard.app.modules.thermal.*
import com.wildguard.app.modules.weather.WeatherApiClient
import com.wildguard.app.modules.weather.OnlineWeather
import com.wildguard.app.ui.Routes
import com.wildguard.app.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ThermalUiState(
    val observations: UserObservations? = null,
    val windChill: WindChillResult? = null,
    val heatIndex: HeatIndexResult? = null,
    val wbgt: WBGTResult? = null,
    val hydration: HydrationResult? = null,
    val hasObservations: Boolean = false,
    val isStale: Boolean = false,
    val onlineWeather: OnlineWeather? = null,
    val onlineWeatherLoading: Boolean = false
)

class ThermalViewModel(application: Application) : AndroidViewModel(application) {

    private val conditionsCheckIn = ConditionsCheckIn(application)
    private val sensorHub = WildGuardApp.instance.sensorHub

    private val _state = MutableStateFlow(ThermalUiState())
    val state: StateFlow<ThermalUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            conditionsCheckIn.observations.collect { obs ->
                recalculate(obs)
            }
        }
        // Attempt to auto-populate from the internet whenever GPS becomes available.
        viewModelScope.launch {
            sensorHub.state.collect { sensor ->
                val loc = sensor.location ?: return@collect
                if (_state.value.onlineWeather == null && !_state.value.onlineWeatherLoading) {
                    fetchOnlineConditions(loc.latitude, loc.longitude)
                }
            }
        }
    }

    fun refreshOnlineConditions() {
        val loc = sensorHub.state.value.location ?: return
        fetchOnlineConditions(loc.latitude, loc.longitude)
    }

    private fun fetchOnlineConditions(lat: Double, lon: Double) {
        _state.value = _state.value.copy(onlineWeatherLoading = true)
        viewModelScope.launch {
            val ow: OnlineWeather? = when (val r = WeatherApiClient.fetchCurrent(lat, lon)) {
                is WeatherApiClient.Result.Success -> r.data
                is WeatherApiClient.Result.Error   -> null
            }
            if (ow != null) {
                // Silently update check-in with online values so downstream calculations use them,
                // but only if the user has NOT already recorded manual observations.
                val existing = conditionsCheckIn.observations.value
                if (existing.temperatureC == null) {
                    conditionsCheckIn.update(
                        existing.copy(
                            temperatureC    = ow.temperatureC,
                            humidityPercent = ow.humidityPercent.toDouble(),
                            windSpeedKmh    = ow.windSpeedKmh,
                            observedAt      = System.currentTimeMillis()
                        )
                    )
                }
            }
            _state.value = _state.value.copy(onlineWeather = ow, onlineWeatherLoading = false)
        }
    }

    private fun recalculate(obs: UserObservations) {
        val hasTemp = obs.temperatureC != null
        val hasHumidity = obs.humidityPercent != null
        val hasWind = obs.windSpeedKmh != null

        val windChill = if (hasTemp && hasWind) {
            WindChillCalculator.calculate(obs.temperatureC!!, obs.windSpeedKmh!!)
        } else null

        val heatIndex = if (hasTemp && hasHumidity) {
            HeatIndexCalculator.calculateMetric(obs.temperatureC!!, obs.humidityPercent!!)
        } else null

        val wbgt = if (hasTemp && hasHumidity) {
            WBGTCalculator.calculate(obs.temperatureC!!, obs.humidityPercent!!)
        } else null

        val hydration = if (hasTemp) {
            HydrationCalculator.calculate(tempC = obs.temperatureC!!, durationHours = 2.0)
        } else null

        _state.value = ThermalUiState(
            observations = obs,
            windChill = windChill,
            heatIndex = heatIndex,
            wbgt = wbgt,
            hydration = hydration,
            hasObservations = hasTemp || hasHumidity,
            isStale = obs.isStale
        )
    }
}

@Composable
fun ThermalScreen(navController: NavController, vm: ThermalViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    ModuleScaffold(title = "Thermal Risk", navController = navController) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // Loading indicator while fetching online data
            if (state.onlineWeatherLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (!state.hasObservations) {
                if (!state.onlineWeatherLoading) {
                    NoObservationsCard { navController.navigate(Routes.CONDITIONS) }
                }
            } else {
                // Show source banner when auto-populated from internet
                if (state.onlineWeather != null) {
                    OnlineSourceBanner(onRefresh = { vm.refreshOnlineConditions() })
                } else if (state.isStale) {
                    StaleWarning { navController.navigate(Routes.CONDITIONS) }
                }

                state.observations?.let { obs ->
                    ObservationsSummaryRow(obs)
                }

                state.windChill?.let { WindChillCard(it) }
                state.heatIndex?.let { HeatIndexCard(it) }
                state.wbgt?.let { WBGTCard(it) }
                state.hydration?.let { HydrationCard(it) }

                OutlinedButton(
                    onClick = { navController.navigate(Routes.CONDITIONS) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, "Update", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Override with Manual Conditions")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OnlineSourceBanner(onRefresh: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = WildBlue.copy(alpha = 0.10f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Cloud, "Online",
                tint = WildBlue,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Conditions auto-filled from internet (Open-Meteo)",
                style = MaterialTheme.typography.bodySmall,
                color = WildBlue,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRefresh, contentPadding = PaddingValues(4.dp)) {
                Text("Refresh", style = MaterialTheme.typography.labelSmall, color = WildBlue)
            }
        }
    }
}

@Composable
private fun NoObservationsCard(onRecord: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Thermostat,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Record conditions to see thermal risk analysis",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRecord) {
                Icon(Icons.Default.Checklist, "Check in", modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Conditions Check-In")
            }
        }
    }
}

@Composable
private fun StaleWarning(onUpdate: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = WildAmber.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, "Stale", tint = WildAmber)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Observations are over 2 hours old", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onUpdate) { Text("Update") }
        }
    }
}

@Composable
private fun ObservationsSummaryRow(obs: UserObservations) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        obs.temperatureC?.let {
            SurfaceChip("${it.toInt()}°C")
        }
        obs.humidityPercent?.let {
            SurfaceChip("${it.toInt()}% RH")
        }
        obs.windSpeedKmh?.let {
            SurfaceChip("${"%.0f".format(it)} km/h wind")
        }
    }
}

@Composable
private fun SurfaceChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun WindChillCard(result: WindChillResult) {
    val riskColor = when (result.riskLevel) {
        WindChillRisk.LOW -> WildGreen
        WindChillRisk.MODERATE -> WildAmber
        WindChillRisk.HIGH -> WildRed.copy(alpha = 0.8f)
        WindChillRisk.EXTREME -> WildRed
    }

    ThermalCard(
        title = "Wind Chill",
        icon = Icons.Default.AcUnit,
        riskColor = riskColor,
        riskLabel = result.riskLevel.name
    ) {
        Text(
            "${"%.0f".format(result.effectiveTempC)}°C",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = riskColor
        )
        Text("Feels-like temperature", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        result.frostbiteMinutes?.let { min ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Timer, "Timer", tint = WildRed, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    "Frostbite risk: ~${"%.0f".format(min)} min on exposed skin",
                    style = MaterialTheme.typography.bodySmall,
                    color = WildRed
                )
            }
        }
    }
}

@Composable
private fun HeatIndexCard(result: HeatIndexResult) {
    val riskColor = when (result.riskLevel) {
        null -> WildGreen
        HeatRisk.CAUTION -> WildAmber
        HeatRisk.EXTREME_CAUTION -> WildAmber
        HeatRisk.DANGER -> WildRed.copy(alpha = 0.8f)
        HeatRisk.EXTREME_DANGER -> WildRed
    }

    ThermalCard(
        title = "Heat Index",
        icon = Icons.Default.WbSunny,
        riskColor = riskColor,
        riskLabel = result.riskLevel?.name ?: "OK"
    ) {
        Text(
            "${"%.0f".format(result.heatIndexC)}°C",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = riskColor
        )
        Text(result.recommendation, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}

@Composable
private fun WBGTCard(result: WBGTResult) {
    val riskColor = when (result.activityGuidance) {
        ActivityGuidance.UNRESTRICTED -> WildGreen
        ActivityGuidance.USE_DISCRETION -> WildGreenLight
        ActivityGuidance.LIMIT_INTENSE_EXERCISE -> WildAmber
        ActivityGuidance.REST_WATER_BREAKS -> WildRed.copy(alpha = 0.8f)
        ActivityGuidance.SUSPEND_EXERCISE -> WildRed
    }

    ThermalCard(
        title = "WBGT (Activity Risk)",
        icon = Icons.Default.DirectionsWalk,
        riskColor = riskColor,
        riskLabel = result.activityGuidance.name.replace('_', ' ')
    ) {
        Text(
            "${"%.1f".format(result.wbgtC)}°C WBGT",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = riskColor
        )
        Text(result.description, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Spacer(Modifier.height(4.dp))
        Text(
            "Work/rest: ${result.restCycleMinutes} min on / ${60 - result.restCycleMinutes} min off",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun HydrationCard(result: HydrationResult) {
    ThermalCard(
        title = "Hydration Estimate",
        icon = Icons.Default.WaterDrop,
        riskColor = WildBlue,
        riskLabel = "2h hike"
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text(
                    "${"%.1f".format(result.waterNeededLiters)} L",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = WildBlue
                )
                Text("Water needed", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Column {
                Text(
                    "${"%.0f".format(result.caloriesBurned)} kcal",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = WildAmber
                )
                Text("Calories burned", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(result.electrolyteNote, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}

@Composable
private fun ThermalCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    riskColor: Color,
    riskLabel: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, title, tint = riskColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Surface(color = riskColor.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
                    Text(
                        riskLabel,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = riskColor
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
