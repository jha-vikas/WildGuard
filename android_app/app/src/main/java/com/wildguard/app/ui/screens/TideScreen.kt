package com.wildguard.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildguard.app.WildGuardApp
import com.wildguard.app.core.data.AssetRepository
import com.wildguard.app.modules.celestial.MoonCalculator
import com.wildguard.app.modules.tide.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class TideUiState(
    val station: TidalStation? = null,
    val distanceKm: Double? = null,
    val currentHeight: Double? = null,
    val isRising: Boolean = true,
    val nextExtremes: List<TideExtreme> = emptyList(),
    val tideCurve: List<Pair<Long, Double>> = emptyList(),
    val springNeapLabel: String = "",
    val moonIllumination: Double = 0.0,
    val noStationNearby: Boolean = false,
    val isLoaded: Boolean = false
)

class TideViewModel : ViewModel() {

    var uiState by mutableStateOf(TideUiState())
        private set

    private val sensorHub = WildGuardApp.instance.sensorHub
    private val stationRepo = TidalStationRepository(AssetRepository(WildGuardApp.instance))
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastUpdateMs = 0L

    init {
        viewModelScope.launch {
            sensorHub.state.collect { sensor ->
                val loc = sensor.location ?: return@collect
                val now = System.currentTimeMillis()
                val moved = lastLat.isNaN() ||
                    kotlin.math.abs(loc.latitude - lastLat) > 0.005 ||
                    kotlin.math.abs(loc.longitude - lastLon) > 0.005
                val elapsed = now - lastUpdateMs > 5 * 60_000L
                if (!moved && !elapsed) return@collect

                lastLat = loc.latitude
                lastLon = loc.longitude
                lastUpdateMs = now

                val nearest = stationRepo.findNearest(loc.latitude, loc.longitude)
                val distKm = nearest?.let { stationRepo.distanceTo(loc.latitude, loc.longitude, it) }
                val moonData = MoonCalculator.compute(loc.latitude, loc.longitude, now)
                update(nearest, distKm, now, moonData.illuminationPercent)
            }
        }
    }

    fun update(
        station: TidalStation?,
        distanceKm: Double?,
        nowMillis: Long,
        moonIlluminationPercent: Double
    ) {
        if (station == null) {
            uiState = TideUiState(noStationNearby = true, isLoaded = true)
            return
        }

        val currentHeight = TideCalculator.computeTideHeight(station, nowMillis)
        val rising = TideCalculator.isRising(station, nowMillis)

        val extremes = TideCalculator.findHighLowTides(station, nowMillis, hours = 48)

        val curveStart = nowMillis - 6 * 3_600_000L
        val curveEnd = nowMillis + 18 * 3_600_000L
        val curve = TideCalculator.computeTideCurve(station, curveStart, curveEnd, intervalMinutes = 15)

        val springNeap = TideCalculator.springNeapIndicator(moonIlluminationPercent)

        uiState = TideUiState(
            station = station,
            distanceKm = distanceKm,
            currentHeight = currentHeight,
            isRising = rising,
            nextExtremes = extremes,
            tideCurve = curve,
            springNeapLabel = springNeap,
            moonIllumination = moonIlluminationPercent,
            noStationNearby = false,
            isLoaded = true
        )
    }
}

@Composable
fun TideScreen(navController: NavController, vm: TideViewModel = viewModel()) {
    val state = vm.uiState

    // Station resolution is handled by the ViewModel's sensor collection

    ModuleScaffold(title = "Tides", navController = navController) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!state.isLoaded) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                return@ModuleScaffold
            }

            if (state.noStationNearby) {
                NoStationCard()
                return@ModuleScaffold
            }

            state.station?.let { station ->
                StationHeader(station, state.distanceKm)
            }

            CurrentTideCard(state)
            TideCurveCard(state)
            NextTidesCard(state)
            SpringNeapCard(state)
        }
    }
}

@Composable
private fun NoStationCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("\uD83C\uDF0A", style = MaterialTheme.typography.displayMedium)
            Text("No Tidal Stations Nearby", style = MaterialTheme.typography.titleMedium)
            Text(
                "No tidal stations found within 100 km of your location. " +
                    "Tide predictions require proximity to a monitored station.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun StationHeader(station: TidalStation, distanceKm: Double?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(station.name, style = MaterialTheme.typography.titleMedium)
            distanceKm?.let {
                Text(
                    "%.1f km away".format(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        Text(
            station.id,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
    }
}

@Composable
private fun CurrentTideCard(state: TideUiState) {
    val arrowIcon = if (state.isRising) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward
    val arrowTint = if (state.isRising) Color(0xFF42A5F5) else Color(0xFFEF5350)
    val directionLabel = if (state.isRising) "Rising" else "Falling"

    val nextExtreme = state.nextExtremes.firstOrNull()
    val timeUntilNext = nextExtreme?.let {
        val diffMs = it.timeMillis - System.currentTimeMillis()
        if (diffMs > 0) {
            val hours = diffMs / 3_600_000
            val mins = (diffMs % 3_600_000) / 60_000
            "${hours}h ${mins}m"
        } else null
    }
    val nextLabel = nextExtreme?.let {
        if (it.isHigh) "high" else "low"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column {
                    Text("Current Tide", style = MaterialTheme.typography.labelMedium)
                    Text(
                        state.currentHeight?.let { "%.2f m".format(it) } ?: "—",
                        style = MaterialTheme.typography.displaySmall
                    )
                }
                Icon(
                    arrowIcon,
                    contentDescription = directionLabel,
                    tint = arrowTint,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    directionLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = arrowTint
                )
            }

            if (timeUntilNext != null && nextLabel != null) {
                Text(
                    "Next $nextLabel tide in $timeUntilNext",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun TideCurveCard(state: TideUiState) {
    val curve = state.tideCurve
    if (curve.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val lineColor = Color(0xFF42A5F5)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val nowColor = Color(0xFFFFA726)
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("24-Hour Tide Curve", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                val leftPad = 40f
                val bottomPad = 24f
                val topPad = 8f
                val rightPad = 8f
                val chartW = size.width - leftPad - rightPad
                val chartH = size.height - topPad - bottomPad

                val minH = curve.minOf { it.second }
                val maxH = curve.maxOf { it.second }
                val range = (maxH - minH).coerceAtLeast(0.5)
                val minT = curve.first().first
                val maxT = curve.last().first
                val tRange = (maxT - minT).coerceAtLeast(1L)

                fun xOf(t: Long) = leftPad + chartW * ((t - minT).toFloat() / tRange)
                fun yOf(h: Double) = topPad + chartH * (1f - ((h - minH) / range).toFloat())

                val gridSteps = 4
                for (i in 0..gridSteps) {
                    val y = topPad + chartH * i / gridSteps
                    drawLine(gridColor, Offset(leftPad, y), Offset(leftPad + chartW, y), 0.5f)
                    val label = "%.1f".format(maxH - i * range / gridSteps)
                    drawText(
                        textMeasurer, label,
                        Offset(2f, y - 6f),
                        style = TextStyle(fontSize = 9.sp, color = labelColor)
                    )
                }

                val sdf = SimpleDateFormat("HH", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val hourMs = 3_600_000L
                var tickT = ((minT / hourMs) + 1) * hourMs
                while (tickT < maxT) {
                    val x = xOf(tickT)
                    drawLine(gridColor, Offset(x, topPad), Offset(x, topPad + chartH), 0.5f)
                    drawText(
                        textMeasurer, sdf.format(Date(tickT)),
                        Offset(x - 6f, topPad + chartH + 4f),
                        style = TextStyle(fontSize = 8.sp, color = labelColor)
                    )
                    tickT += 3 * hourMs
                }

                val path = Path()
                curve.forEachIndexed { i, (t, h) ->
                    val x = xOf(t)
                    val y = yOf(h)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, lineColor, style = Stroke(2.5f))

                val nowMs = System.currentTimeMillis()
                if (nowMs in minT..maxT) {
                    val nx = xOf(nowMs)
                    drawLine(nowColor, Offset(nx, topPad), Offset(nx, topPad + chartH), 1.5f)
                    drawCircle(nowColor, 4f, Offset(nx, topPad))
                }

                for (ext in state.nextExtremes) {
                    if (ext.timeMillis in minT..maxT) {
                        val ex = xOf(ext.timeMillis)
                        val ey = yOf(ext.heightM)
                        val dotColor = if (ext.isHigh) Color(0xFF66BB6A) else Color(0xFFEF5350)
                        drawCircle(dotColor, 5f, Offset(ex, ey))
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ChartLegendDot(Color(0xFF42A5F5), "Tide")
                ChartLegendDot(Color(0xFFFFA726), "Now")
                ChartLegendDot(Color(0xFF66BB6A), "High")
                ChartLegendDot(Color(0xFFEF5350), "Low")
            }
        }
    }
}

@Composable
private fun ChartLegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = color, radius = size.minDimension / 2)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun NextTidesCard(state: TideUiState) {
    if (state.nextExtremes.isEmpty()) return

    val sdf = remember {
        SimpleDateFormat("EEE HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Next 48h Tides", style = MaterialTheme.typography.titleMedium)

            state.nextExtremes.forEach { extreme ->
                val label = if (extreme.isHigh) "\u25B2 High" else "\u25BC Low"
                val labelColor = if (extreme.isHigh) Color(0xFF66BB6A) else Color(0xFFEF5350)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = labelColor)
                    Text(
                        "%.2f m".format(extreme.heightM),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        sdf.format(Date(extreme.timeMillis)) + " UTC",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SpringNeapCard(state: TideUiState) {
    val bgColor = when {
        state.springNeapLabel.contains("Spring") -> Color(0xFF1565C0).copy(alpha = 0.15f)
        state.springNeapLabel.contains("Neap") -> Color(0xFF66BB6A).copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.springNeapLabel, style = MaterialTheme.typography.titleMedium)

            val desc = when {
                state.springNeapLabel.contains("Spring") ->
                    "Larger tidal range expected. New or full moon creates stronger gravitational pull."
                state.springNeapLabel.contains("Neap") ->
                    "Smaller tidal range expected. Quarter moon means sun and moon pull at right angles."
                else ->
                    "Transitioning between spring and neap tides."
            }
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            Text(
                "Moon illumination: %.0f%%".format(state.moonIllumination),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}
