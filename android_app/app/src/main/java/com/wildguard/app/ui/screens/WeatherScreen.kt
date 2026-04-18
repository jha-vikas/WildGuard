package com.wildguard.app.ui.screens

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildguard.app.WildGuardApp
import com.wildguard.app.modules.weather.*
import com.wildguard.app.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── UI State ────────────────────────────────────────────────────────────────

data class WeatherUiState(
    val currentPressureHpa: Float? = null,
    val trend3h: Float? = null,
    val trend6h: Float? = null,
    val trendClassification: PressureTrend = PressureTrend.STEADY,
    val forecast: ZambrettiForecast? = null,
    val stormAlert: StormAlert? = null,
    val pressureHistory: List<PressureReading> = emptyList(),
    val lastUpdated: Long? = null,
    val windDirectionDeg: Double? = null,
    val onlineWeather: OnlineWeather? = null,
    val onlineWeatherLoading: Boolean = false
)

// ── ViewModel ───────────────────────────────────────────────────────────────

class WeatherViewModel(application: Application) : AndroidViewModel(application) {

    private val sensorHub = WildGuardApp.instance.sensorHub
    val pressureLogger = PressureLogger(application)
    private val forecaster = ZambrettiForecaster()
    private val stormDetector = StormAlertDetector(pressureLogger)

    private val _uiState = MutableStateFlow(WeatherUiState())
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sensorHub.state.collect { sensor ->
                sensor.pressureHpa?.let { pressure ->
                    pressureLogger.recordReading(pressure)
                    refreshState(pressure)
                }
                // Fetch online weather whenever location first becomes available or changes significantly.
                val loc = sensor.location
                if (loc != null && _uiState.value.onlineWeather == null && !_uiState.value.onlineWeatherLoading) {
                    fetchOnlineWeather(loc.latitude, loc.longitude)
                }
            }
        }
    }

    fun refreshOnlineWeather() {
        val loc = sensorHub.state.value.location ?: return
        fetchOnlineWeather(loc.latitude, loc.longitude)
    }

    private fun fetchOnlineWeather(lat: Double, lon: Double) {
        _uiState.update { it.copy(onlineWeatherLoading = true) }
        viewModelScope.launch {
            val result = WeatherApiClient.fetchCurrent(lat, lon)
            _uiState.update { it.copy(onlineWeather = result, onlineWeatherLoading = false) }
        }
    }

    fun setWindDirection(deg: Double) {
        _uiState.update { it.copy(windDirectionDeg = deg) }
        _uiState.value.currentPressureHpa?.let { refreshState(it) }
    }

    private fun refreshState(pressure: Float) {
        val trend = pressureLogger.trendClassification
        val windDir = _uiState.value.windDirectionDeg
        val forecast = forecaster.forecast(pressure, trend, windDir)
        val alert = stormDetector.evaluate()

        _uiState.update {
            it.copy(
                currentPressureHpa = pressure,
                trend3h = pressureLogger.trend3h,
                trend6h = pressureLogger.trend6h,
                trendClassification = trend,
                forecast = forecast,
                stormAlert = alert,
                pressureHistory = pressureLogger.history,
                lastUpdated = System.currentTimeMillis()
            )
        }
    }
}

// ── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun WeatherScreen(navController: NavController, vm: WeatherViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    var showWindPicker by remember { mutableStateOf(false) }

    ModuleScaffold(title = "Weather Forecast", navController = navController) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // Online conditions card (Open-Meteo, no key required)
            when {
                state.onlineWeatherLoading -> OnlineWeatherLoading()
                state.onlineWeather != null -> OnlineWeatherCard(
                    weather = state.onlineWeather!!,
                    onRefresh = { vm.refreshOnlineWeather() }
                )
            }

            // Storm alert banner
            state.stormAlert?.let { alert ->
                StormAlertBanner(alert)
            }

            // Current pressure (from barometer)
            PressureCard(state)

            // Zambretti forecast (needs ~1h of pressure history)
            if (state.forecast != null) {
                ForecastCard(state.forecast!!)
            } else if (state.pressureHistory.size < 3) {
                BarometerWarmupNote()
            }

            // 24-hour pressure chart
            if (state.pressureHistory.size >= 2) {
                Text("24-Hour Pressure", style = MaterialTheme.typography.titleMedium)
                PressureChart(
                    readings = state.pressureHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                )
            }

            // Wind direction picker
            WindDirectionCard(
                currentDeg = state.windDirectionDeg,
                onClick = { showWindPicker = true }
            )

            // Last updated
            state.lastUpdated?.let { ts ->
                val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                Text(
                    text = "Last observation: ${fmt.format(Date(ts))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showWindPicker) {
        WindDirectionDialog(
            onSelect = { deg ->
                vm.setWindDirection(deg)
                showWindPicker = false
            },
            onDismiss = { showWindPicker = false }
        )
    }
}

// ── Online weather components ───────────────────────────────────────────────

@Composable
private fun OnlineWeatherLoading() {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Fetching online conditions…", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun OnlineWeatherCard(weather: OnlineWeather, onRefresh: () -> Unit) {
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Online Conditions",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                    Text(
                        text = "via Open-Meteo · ${fmt.format(java.util.Date(weather.fetchedAt))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                }
                TextButton(onClick = onRefresh, contentPadding = PaddingValues(4.dp)) {
                    Text("Refresh", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "${"%.0f".format(weather.temperatureC)}°C",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Feels ${"%+.0f".format(weather.apparentTempC)}°C",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(weather.description, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text(
                        "${weather.humidityPercent}% RH",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                LabelValue("Wind", "${"%.0f".format(weather.windSpeedKmh)} km/h ${degToCompass(weather.windDirectionDeg).substringBefore(" ")}")
                LabelValue("Pressure", "${"%.0f".format(weather.pressureHpa)} hPa")
            }
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BarometerWarmupNote() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Default.Warning, "Info",
                tint = WildAmber,
                modifier = Modifier.size(20.dp)
            )
            Text(
                "Barometric forecast becomes available after ~1 hour of pressure readings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

// ── Components ──────────────────────────────────────────────────────────────

@Composable
private fun StormAlertBanner(alert: StormAlert) {
    val bg = when (alert.level) {
        StormAlertLevel.WATCH -> WildAmber
        StormAlertLevel.WARNING -> Color(0xFFE65100)
        StormAlertLevel.SEVERE -> WildRed
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = Color.White)
            Text(
                text = alert.message,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PressureCard(state: WeatherUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Current Pressure", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = state.currentPressureHpa?.let { "%.1f".format(it) } ?: "—",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "hPa",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrendIndicator(state.trendClassification, state.trend3h)

                state.trend3h?.let { t3 ->
                    TrendChip(label = "3h", value = t3)
                }
                state.trend6h?.let { t6 ->
                    TrendChip(label = "6h", value = t6)
                }
            }
        }
    }
}

@Composable
private fun TrendIndicator(trend: PressureTrend, rate: Float?) {
    val (icon, color) = when (trend) {
        PressureTrend.RAPID_RISE -> Icons.Default.KeyboardArrowUp to WildGreen
        PressureTrend.SLOW_RISE -> Icons.Default.KeyboardArrowUp to WildGreenLight
        PressureTrend.STEADY -> Icons.Default.Remove to WildAmber
        PressureTrend.SLOW_DROP -> Icons.Default.KeyboardArrowDown to WildAmber
        PressureTrend.RAPID_DROP -> Icons.Default.KeyboardArrowDown to WildRed
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = trend.name, tint = color, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = trend.name.replace('_', ' ').lowercase()
                .replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )
    }
}

@Composable
private fun TrendChip(label: String, value: Float) {
    val color = when {
        value < -2f -> WildRed
        value < 0f -> WildAmber
        value > 2f -> WildGreen
        value > 0f -> WildGreenLight
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    }
    val sign = if (value >= 0) "+" else ""
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = "$label: $sign${"%.1f".format(value)}",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
private fun ForecastCard(fc: ZambrettiForecast) {
    val accentColor = when (fc.severity) {
        ForecastSeverity.GOOD -> WildGreen
        ForecastSeverity.FAIR -> WildAmber
        ForecastSeverity.POOR -> Color(0xFFE65100)
        ForecastSeverity.STORM -> WildRed
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = accentColor.copy(alpha = 0.2f)
                ) {
                    Text(
                        fc.severity.name,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "Zambretti #${fc.number}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                fc.description,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PressureChart(readings: List<PressureReading>, modifier: Modifier = Modifier) {
    val now = System.currentTimeMillis()
    val dayAgo = now - 24 * 3_600_000L
    val filtered = remember(readings, now) {
        readings.filter { it.timestampMillis >= dayAgo }
    }

    if (filtered.size < 2) return

    val minP = filtered.minOf { it.pressureHpa }
    val maxP = filtered.maxOf { it.pressureHpa }
    val range = (maxP - minP).coerceAtLeast(5f)
    val paddedMin = minP - range * 0.1f
    val paddedMax = maxP + range * 0.1f
    val paddedRange = paddedMax - paddedMin

    val lineColor = WildBlue
    val gridColor = Color.White.copy(alpha = 0.08f)
    val labelColor = Color.White.copy(alpha = 0.5f)
    val timeSpan = (now - dayAgo).toFloat()

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val pad = 52f
            val cw = size.width - pad * 2
            val ch = size.height - pad - 24f

            // Horizontal grid lines (5 divisions)
            for (i in 0..4) {
                val y = 24f + ch * i / 4
                drawLine(gridColor, Offset(pad, y), Offset(pad + cw, y), strokeWidth = 1f)

                val pLabel = paddedMax - paddedRange * i / 4
                drawContext.canvas.nativeCanvas.drawText(
                    "%.0f".format(pLabel),
                    4f, y + 4f,
                    android.graphics.Paint().apply {
                        color = labelColor.toArgb()
                        textSize = 22f
                        isAntiAlias = true
                    }
                )
            }

            // Vertical grid lines (6h intervals)
            for (h in listOf(6, 12, 18, 24)) {
                val x = pad + cw * h / 24f
                drawLine(gridColor, Offset(x, 24f), Offset(x, 24f + ch), strokeWidth = 1f)
            }

            // Pressure line
            val path = Path()
            filtered.forEachIndexed { i, r ->
                val x = pad + cw * (r.timestampMillis - dayAgo) / timeSpan
                val y = 24f + ch * (1f - (r.pressureHpa - paddedMin) / paddedRange)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = Stroke(width = 2.5f))
        }
    }
}

@Composable
private fun WindDirectionCard(currentDeg: Double?, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Wind Direction",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    currentDeg?.let { degToCompass(it) }
                        ?: "Tap to record wind direction for better forecast",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (currentDeg != null) WildGreenLight
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun WindDirectionDialog(onSelect: (Double) -> Unit, onDismiss: () -> Unit) {
    val directions = listOf(
        "N" to 0.0, "NE" to 45.0, "E" to 90.0, "SE" to 135.0,
        "S" to 180.0, "SW" to 225.0, "W" to 270.0, "NW" to 315.0
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wind Direction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Select the direction the wind is blowing FROM:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.height(8.dp))
                directions.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (label, deg) ->
                            OutlinedButton(
                                onClick = { onSelect(deg) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(label, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun degToCompass(deg: Double): String = when {
    deg < 22.5 || deg >= 337.5 -> "North (${deg.toInt()}°)"
    deg < 67.5 -> "Northeast (${deg.toInt()}°)"
    deg < 112.5 -> "East (${deg.toInt()}°)"
    deg < 157.5 -> "Southeast (${deg.toInt()}°)"
    deg < 202.5 -> "South (${deg.toInt()}°)"
    deg < 247.5 -> "Southwest (${deg.toInt()}°)"
    deg < 292.5 -> "West (${deg.toInt()}°)"
    else -> "Northwest (${deg.toInt()}°)"
}
