package com.wildguard.app.ui.screens

import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildguard.app.WildGuardApp
import com.wildguard.app.core.model.UserObservations
import com.wildguard.app.modules.conditions.ConditionsCheckIn
import com.wildguard.app.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CheckInUiState(
    val currentStep: Int = 0,
    val temperatureC: Double = 15.0,
    val humidityLabel: String = "Moderate",
    val humidityPercent: Double = 50.0,
    val beaufortScale: Int = 2,
    val windDirectionDeg: Double? = null,
    val cloudType: String = "Clear",
    val compassHeading: Float? = null,
    val saved: Boolean = false
) {
    val totalSteps: Int get() = 6
    val isLastStep: Boolean get() = currentStep == totalSteps - 1
}

class ConditionsCheckInViewModel(application: Application) : AndroidViewModel(application) {

    private val conditionsCheckIn = ConditionsCheckIn(application)
    private val sensorHub = WildGuardApp.instance.sensorHub

    private val _state = MutableStateFlow(CheckInUiState())
    val state: StateFlow<CheckInUiState> = _state.asStateFlow()

    init {
        val cached = conditionsCheckIn.observations.value
        if (!cached.isStale) {
            _state.value = _state.value.copy(
                temperatureC = cached.temperatureC ?: 15.0,
                humidityPercent = cached.humidityPercent ?: 50.0,
                beaufortScale = cached.beaufortScale ?: 2,
                windDirectionDeg = cached.windDirectionDeg,
                cloudType = cached.cloudType ?: "Clear"
            )
        }
    }

    fun nextStep() {
        val s = _state.value
        if (s.currentStep < s.totalSteps - 1) {
            _state.value = s.copy(currentStep = s.currentStep + 1)
        }
    }

    fun prevStep() {
        val s = _state.value
        if (s.currentStep > 0) {
            _state.value = s.copy(currentStep = s.currentStep - 1)
        }
    }

    fun setTemperature(tempC: Double) {
        _state.value = _state.value.copy(temperatureC = tempC)
    }

    fun setHumidity(label: String, percent: Double) {
        _state.value = _state.value.copy(humidityLabel = label, humidityPercent = percent)
    }

    fun setBeaufort(scale: Int) {
        _state.value = _state.value.copy(beaufortScale = scale)
    }

    fun captureWindDirection() {
        val heading = sensorHub.state.value.compassHeadingDeg
        if (heading != null) {
            _state.value = _state.value.copy(windDirectionDeg = heading.toDouble())
        }
    }

    fun setCloudType(type: String) {
        _state.value = _state.value.copy(cloudType = type)
    }

    fun save() {
        val s = _state.value
        val windLevel = ConditionsCheckIn.BEAUFORT_SCALE.getOrNull(s.beaufortScale)
        val windSpeed = windLevel?.let { (it.speedRangeKmh.first + it.speedRangeKmh.last) / 2.0 }
            ?: 0.0

        conditionsCheckIn.update(
            UserObservations(
                temperatureC = s.temperatureC,
                humidityPercent = s.humidityPercent,
                windSpeedKmh = windSpeed,
                windDirectionDeg = s.windDirectionDeg,
                beaufortScale = s.beaufortScale,
                cloudType = s.cloudType
            )
        )
        _state.value = s.copy(saved = true)
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConditionsCheckInScreen(navController: NavController, vm: ConditionsCheckInViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    ModuleScaffold(title = "Conditions Check-In", navController = navController) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            StepIndicator(current = state.currentStep, total = state.totalSteps)

            Spacer(Modifier.height(16.dp))

            AnimatedContent(
                targetState = state.currentStep,
                modifier = Modifier.weight(1f),
                label = "step"
            ) { step ->
                when (step) {
                    0 -> TemperatureStep(state.temperatureC) { vm.setTemperature(it) }
                    1 -> HumidityStep(state.humidityLabel) { label, pct -> vm.setHumidity(label, pct) }
                    2 -> WindStep(state.beaufortScale) { vm.setBeaufort(it) }
                    3 -> WindDirectionStep(state.windDirectionDeg) { vm.captureWindDirection() }
                    4 -> CloudTypeStep(state.cloudType) { vm.setCloudType(it) }
                    5 -> SummaryStep(state, onSave = { vm.save() }, saved = state.saved)
                }
            }

            NavigationButtons(
                state = state,
                onBack = { vm.prevStep() },
                onNext = { vm.nextStep() },
                onSkip = { vm.nextStep() },
                onDone = { navController.popBackStack() }
            )
        }
    }
}

@Composable
private fun StepIndicator(current: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val labels = listOf("Temp", "Humidity", "Wind", "Direction", "Clouds", "Summary")
        for (i in 0 until total) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                LinearProgressIndicator(
                    progress = { if (i <= current) 1f else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = if (i <= current) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                )
                Text(
                    labels[i],
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == current) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
private fun TemperatureStep(tempC: Double, onChanged: (Double) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Estimate Temperature", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Use physical cues below to estimate the air temperature",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "${tempC.toInt()}°C",
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
            color = temperatureColor(tempC)
        )

        Slider(
            value = tempC.toFloat(),
            onValueChange = { onChanged(it.toDouble()) },
            valueRange = -30f..45f,
            steps = 74,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(Modifier.height(16.dp))

        val matchingCue = ConditionsCheckIn.TEMPERATURE_CUES.find { tempC.toInt() in it.rangeC }
        if (matchingCue != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(matchingCue.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(matchingCue.cue, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
private fun temperatureColor(tempC: Double): androidx.compose.ui.graphics.Color = when {
    tempC < 0 -> WildBlue
    tempC < 15 -> WildGreenLight
    tempC < 30 -> WildAmber
    else -> WildRed
}

@Composable
private fun HumidityStep(selectedLabel: String, onSelected: (String, Double) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Estimate Humidity", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Select the description that best matches current conditions",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        ConditionsCheckIn.HUMIDITY_LEVELS.forEach { level ->
            val isSelected = level.label == selectedLabel
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onSelected(level.label, level.estimatedPercent) },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surface
                ),
                border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = isSelected, onClick = { onSelected(level.label, level.estimatedPercent) })
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(level.label, style = MaterialTheme.typography.titleSmall)
                        Text(level.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
private fun WindStep(selectedScale: Int, onSelected: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Estimate Wind Speed", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Select the Beaufort level matching what you observe",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(ConditionsCheckIn.BEAUFORT_SCALE.take(9)) { level ->
                val isSelected = level.scale == selectedScale
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable { onSelected(level.scale) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surface
                    ),
                    border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${level.scale}",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(32.dp)
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(level.label, style = MaterialTheme.typography.titleSmall)
                            Text(level.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Text(
                            "${level.speedRangeKmh.first}-${level.speedRangeKmh.last} km/h",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WindDirectionStep(directionDeg: Double?, onCapture: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Wind Direction", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Point your phone into the wind and tap the button below",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        if (directionDeg != null) {
            Text(
                "${"%.0f".format(directionDeg)}°",
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Wind from ${com.wildguard.app.modules.compass.CompassCalculator.cardinalDirection(directionDeg)}",
                style = MaterialTheme.typography.bodyLarge
            )
        } else {
            Icon(
                Icons.Default.Explore,
                contentDescription = "Compass",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(onClick = onCapture) {
            Icon(Icons.Default.MyLocation, "Capture", modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (directionDeg != null) "Recapture Direction" else "Capture Direction")
        }
    }
}

@Composable
private fun CloudTypeStep(selectedType: String, onSelected: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Cloud Type", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Select the cloud type that best matches the sky right now",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(ConditionsCheckIn.CLOUD_TYPES) { (type, description) ->
                val isSelected = type == selectedType
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable { onSelected(type) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surface
                    ),
                    border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = isSelected, onClick = { onSelected(type) })
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(type, style = MaterialTheme.typography.titleSmall)
                            Text(
                                description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryStep(state: CheckInUiState, onSave: () -> Unit, saved: Boolean) {
    val windLevel = ConditionsCheckIn.BEAUFORT_SCALE.getOrNull(state.beaufortScale)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Observation Summary", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        SummaryRow("Temperature", "${state.temperatureC.toInt()}°C")
        SummaryRow("Humidity", "${state.humidityLabel} (~${state.humidityPercent.toInt()}%)")
        SummaryRow("Wind", "${windLevel?.label ?: "?"} (Beaufort ${state.beaufortScale})")
        SummaryRow("Wind Direction", state.windDirectionDeg?.let { "${"%.0f".format(it)}°" } ?: "Not recorded")
        SummaryRow("Clouds", state.cloudType)

        Spacer(Modifier.height(24.dp))

        if (saved) {
            Card(
                colors = CardDefaults.cardColors(containerColor = WildGreen.copy(alpha = 0.15f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, "Saved", tint = WildGreen)
                    Spacer(Modifier.width(8.dp))
                    Text("Observations saved! All modules updated.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Save, "Save", modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save Observations")
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun NavigationButtons(
    state: CheckInUiState,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onDone: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (state.currentStep > 0) {
            OutlinedButton(onClick = onBack) { Text("Back") }
        } else {
            Spacer(Modifier.width(1.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.isLastStep) {
                TextButton(onClick = onSkip) { Text("Skip") }
                Button(onClick = onNext) { Text("Next") }
            } else if (state.saved) {
                Button(onClick = onDone) { Text("Done") }
            }
        }
    }
}
