package com.wildguard.app.ui.screens

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildguard.app.WildGuardApp
import com.wildguard.app.modules.compass.*
import com.wildguard.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

data class CompassUiState(
    val magneticHeading: Double = 0.0,
    val trueHeading: Double = 0.0,
    val declination: Double = 0.0,
    val cardinalDirection: String = "N",
    val showTrueNorth: Boolean = true,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val hasGps: Boolean = false,
    val waypoints: List<WaypointBearing> = emptyList(),
    val selectedWaypointId: String? = null,
    val verification: CompassVerification? = null,
    val trackStats: TrackStats? = null,
    val trackActive: Boolean = false,
    val showAddDialog: Boolean = false
)

class CompassViewModel(application: Application) : AndroidViewModel(application) {

    private val sensorHub = WildGuardApp.instance.sensorHub
    private val waypointManager = WaypointManager(application)
    private val breadcrumbTracker = BreadcrumbTracker(application)

    private val _state = MutableStateFlow(CompassUiState())
    val state: StateFlow<CompassUiState> = _state.asStateFlow()

    init {
        breadcrumbTracker.restoreFromFile()
        viewModelScope.launch {
            sensorHub.state.collect { sensor ->
                val loc = sensor.location
                val heading = sensor.compassHeadingDeg?.toDouble() ?: 0.0

                if (loc != null) {
                    val year = decimalYear()
                    val reading = CompassCalculator.getReading(heading, loc.latitude, loc.longitude, year)
                    val bearings = waypointManager.computeBearings(loc.latitude, loc.longitude)

                    breadcrumbTracker.recordIfDue(loc.latitude, loc.longitude, loc.altitudeGps)
                    val stats = breadcrumbTracker.getStats(loc.latitude, loc.longitude)

                    val verification = SunCompassVerifier.verify(
                        reading.trueHeading, loc.latitude, loc.longitude
                    )

                    _state.value = _state.value.copy(
                        magneticHeading = reading.magneticHeading,
                        trueHeading = reading.trueHeading,
                        declination = reading.declination,
                        cardinalDirection = reading.cardinalDirection,
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        hasGps = true,
                        waypoints = bearings,
                        verification = verification,
                        trackStats = stats,
                        trackActive = breadcrumbTracker.isActive
                    )
                } else {
                    _state.value = _state.value.copy(
                        magneticHeading = heading,
                        trueHeading = heading,
                        cardinalDirection = CompassCalculator.cardinalDirection(heading)
                    )
                }
            }
        }
    }

    fun toggleTrueNorth() {
        _state.value = _state.value.copy(showTrueNorth = !_state.value.showTrueNorth)
    }

    fun selectWaypoint(id: String?) {
        _state.value = _state.value.copy(selectedWaypointId = id)
    }

    fun addWaypoint(name: String) {
        val s = _state.value
        if (!s.hasGps) return
        val wp = Waypoint(name = name, lat = s.latitude, lon = s.longitude)
        waypointManager.save(wp)
        refreshWaypoints()
    }

    fun deleteWaypoint(id: String) {
        waypointManager.delete(id)
        if (_state.value.selectedWaypointId == id) {
            _state.value = _state.value.copy(selectedWaypointId = null)
        }
        refreshWaypoints()
    }

    fun clearTrack() {
        breadcrumbTracker.clear()
        _state.value = _state.value.copy(trackStats = null, trackActive = false)
    }

    fun showAddDialog(show: Boolean) {
        _state.value = _state.value.copy(showAddDialog = show)
    }

    private fun refreshWaypoints() {
        val s = _state.value
        if (s.hasGps) {
            _state.value = s.copy(waypoints = waypointManager.computeBearings(s.latitude, s.longitude))
        }
    }

    private fun decimalYear(): Double {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val daysInYear = if (cal.getActualMaximum(Calendar.DAY_OF_YEAR) > 365) 366.0 else 365.0
        return year + dayOfYear / daysInYear
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompassScreen(navController: NavController, vm: CompassViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    ModuleScaffold(title = "Compass", navController = navController) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item { HeadingDisplay(state) }
            item { CompassRose(state) }
            item { HeadingToggle(state.showTrueNorth) { vm.toggleTrueNorth() } }
            item { VerificationCard(state.verification) }
            item { TrackCard(state.trackStats, state.trackActive) { vm.clearTrack() } }
            item { WaypointHeader(state.hasGps) { vm.showAddDialog(true) } }

            items(state.waypoints, key = { it.waypoint.id }) { wpb ->
                WaypointRow(
                    wpb = wpb,
                    isSelected = wpb.waypoint.id == state.selectedWaypointId,
                    onSelect = { vm.selectWaypoint(wpb.waypoint.id) },
                    onDelete = { vm.deleteWaypoint(wpb.waypoint.id) }
                )
            }
        }
    }

    if (state.showAddDialog) {
        AddWaypointDialog(
            onDismiss = { vm.showAddDialog(false) },
            onConfirm = { name ->
                vm.addWaypoint(name)
                vm.showAddDialog(false)
            }
        )
    }
}

@Composable
private fun HeadingDisplay(state: CompassUiState) {
    val heading = if (state.showTrueNorth) state.trueHeading else state.magneticHeading
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "${heading.toInt()}°",
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "${state.cardinalDirection}  •  ${if (state.showTrueNorth) "True" else "Magnetic"}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        if (state.hasGps) {
            Text(
                text = "Declination: %+.1f°".format(state.declination),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun CompassRose(state: CompassUiState) {
    val heading = if (state.showTrueNorth) state.trueHeading else state.magneticHeading
    val selectedWp = state.waypoints.find { it.waypoint.id == state.selectedWaypointId }
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.onSurface
    val dimColor = surfaceColor.copy(alpha = 0.3f)
    val needleColor = WildRed
    val waypointColor = WildAmber

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(24.dp)
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension / 2f * 0.9f

        rotate(-heading.toFloat(), pivot = Offset(cx, cy)) {
            drawCircle(color = dimColor, radius = radius, center = Offset(cx, cy), style = Stroke(2f))
            drawCircle(color = dimColor, radius = radius * 0.7f, center = Offset(cx, cy), style = Stroke(1f))

            for (i in 0 until 360 step 5) {
                val angle = Math.toRadians(i.toDouble() - 90).toFloat()
                val isCardinal = i % 90 == 0
                val isIntercardinal = i % 45 == 0
                val tickLen = when {
                    isCardinal -> radius * 0.15f
                    isIntercardinal -> radius * 0.10f
                    i % 10 == 0 -> radius * 0.07f
                    else -> radius * 0.04f
                }
                val outerR = radius
                val innerR = radius - tickLen
                val tickColor = if (isCardinal || isIntercardinal) surfaceColor else dimColor
                val tickWidth = if (isCardinal) 3f else if (isIntercardinal) 2f else 1f

                drawLine(
                    color = tickColor,
                    start = Offset(cx + innerR * cos(angle), cy + innerR * sin(angle)),
                    end = Offset(cx + outerR * cos(angle), cy + outerR * sin(angle)),
                    strokeWidth = tickWidth
                )
            }

            val labels = listOf(0 to "N", 45 to "NE", 90 to "E", 135 to "SE", 180 to "S", 225 to "SW", 270 to "W", 315 to "NW")
            for ((deg, label) in labels) {
                val angle = Math.toRadians(deg.toDouble() - 90)
                val labelR = radius * 0.78f
                val lx = cx + (labelR * cos(angle)).toFloat()
                val ly = cy + (labelR * sin(angle)).toFloat()
                val paint = android.graphics.Paint().apply {
                    color = if (label == "N") android.graphics.Color.RED else
                        android.graphics.Color.argb(
                            (surfaceColor.alpha * 255).toInt(),
                            (surfaceColor.red * 255).toInt(),
                            (surfaceColor.green * 255).toInt(),
                            (surfaceColor.blue * 255).toInt()
                        )
                    textSize = if (deg % 90 == 0) radius * 0.12f else radius * 0.08f
                    textAlign = android.graphics.Paint.Align.CENTER
                    isFakeBoldText = deg % 90 == 0
                    isAntiAlias = true
                }
                drawContext.canvas.nativeCanvas.save()
                drawContext.canvas.nativeCanvas.translate(lx, ly)
                drawContext.canvas.nativeCanvas.rotate(deg.toFloat())
                drawContext.canvas.nativeCanvas.drawText(label, 0f, paint.textSize / 3f, paint)
                drawContext.canvas.nativeCanvas.restore()
            }

            drawNeedle(cx, cy, radius * 0.55f, needleColor, surfaceColor)

            if (selectedWp != null) {
                drawWaypointArrow(cx, cy, radius, selectedWp.bearingDeg.toFloat(), waypointColor)
            }
        }
    }
}

private fun DrawScope.drawNeedle(cx: Float, cy: Float, length: Float, northColor: Color, southColor: Color) {
    val width = length * 0.12f
    val northPath = Path().apply {
        moveTo(cx, cy - length)
        lineTo(cx - width, cy)
        lineTo(cx + width, cy)
        close()
    }
    drawPath(northPath, northColor)

    val southPath = Path().apply {
        moveTo(cx, cy + length * 0.6f)
        lineTo(cx - width, cy)
        lineTo(cx + width, cy)
        close()
    }
    drawPath(southPath, southColor.copy(alpha = 0.3f))

    drawCircle(color = southColor, radius = width * 0.6f, center = Offset(cx, cy))
}

private fun DrawScope.drawWaypointArrow(cx: Float, cy: Float, radius: Float, bearingDeg: Float, color: Color) {
    val angle = Math.toRadians((bearingDeg - 90).toDouble())
    val tipR = radius * 0.95f
    val baseR = radius * 0.82f
    val halfSpread = Math.toRadians(8.0)

    val tipX = cx + (tipR * cos(angle)).toFloat()
    val tipY = cy + (tipR * sin(angle)).toFloat()
    val baseLeftX = cx + (baseR * cos(angle - halfSpread)).toFloat()
    val baseLeftY = cy + (baseR * sin(angle - halfSpread)).toFloat()
    val baseRightX = cx + (baseR * cos(angle + halfSpread)).toFloat()
    val baseRightY = cy + (baseR * sin(angle + halfSpread)).toFloat()

    val path = Path().apply {
        moveTo(tipX, tipY)
        lineTo(baseLeftX, baseLeftY)
        lineTo(baseRightX, baseRightY)
        close()
    }
    drawPath(path, color)
}

@Composable
private fun HeadingToggle(showTrue: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Magnetic", style = MaterialTheme.typography.bodyMedium)
        Switch(checked = showTrue, onCheckedChange = { onToggle() }, modifier = Modifier.padding(horizontal = 8.dp))
        Text("True North", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun VerificationCard(verification: CompassVerification?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (verification == null || !verification.isValid) {
                Icon(Icons.Default.WbCloudy, "Sun not visible", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                Text("Sun compass check unavailable", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            } else if (verification.isConsistent) {
                Icon(Icons.Default.CheckCircle, "Consistent", tint = WildGreen)
                Text("Compass verified by sun position (Δ%.0f°)".format(verification.discrepancy),
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Icon(Icons.Default.Warning, "Interference", tint = WildAmber)
                Column {
                    Text("Possible magnetic interference", style = MaterialTheme.typography.bodySmall,
                        color = WildAmber)
                    Text("Discrepancy: %.0f° from expected sun bearing".format(verification.discrepancy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
private fun TrackCard(stats: TrackStats?, active: Boolean, onClear: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Route, "Track", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Breadcrumb Track", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                if (active) {
                    TextButton(onClick = onClear) { Text("Clear", color = WildRed) }
                }
            }
            if (stats != null && stats.pointCount > 0) {
                val elapsedMin = stats.elapsedMs / 60_000.0
                val speedKmh = stats.averageSpeedMps * 3.6
                Text(
                    "Distance: ${CompassCalculator.formatDistance(stats.totalDistanceM)}  •  " +
                            "Time: ${"%.0f".format(elapsedMin)} min  •  " +
                            "Avg: ${"%.1f".format(speedKmh)} km/h",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                stats.wayBackBearingDeg?.let { bearing ->
                    Text(
                        "Way back: ${"%.0f".format(bearing)}° ${CompassCalculator.cardinalDirection(bearing)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = WildAmber
                    )
                }
            } else {
                Text("Tracking will start when you move",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun WaypointHeader(hasGps: Boolean, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Waypoints", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onAdd, enabled = hasGps) {
            Icon(Icons.Default.AddLocation, "Add waypoint", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun WaypointRow(wpb: WaypointBearing, isSelected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(wpb.waypoint.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${"%.0f".format(wpb.bearingDeg)}° ${CompassCalculator.cardinalDirection(wpb.bearingDeg)}  •  " +
                            CompassCalculator.formatDistance(wpb.distanceM),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Delete", tint = WildRed.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun AddWaypointDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Waypoint") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.ifBlank { "Waypoint" }) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
