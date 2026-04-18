package com.wildguard.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildguard.app.WildGuardApp
import com.wildguard.app.core.model.SensorState
import com.wildguard.app.modules.uv.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

// ── ViewModel ────────────────────────────────────────────────────────

data class UVScreenState(
    val sensorState: SensorState = SensorState(),
    val sunPosition: SunPosition? = null,
    val uvResult: UVResult? = null,
    val skinType: SkinType = SkinType.TYPE_II,
    val spf: Int = 30,
    val surfaceType: SurfaceType = SurfaceType.GRASS,
    val ozoneFactor: Double = 1.0,
    val computedAtUtcMillis: Long = 0L
)

class UVViewModel : ViewModel() {

    private val sensorHub = WildGuardApp.instance.sensorHub

    private val _uiState = MutableStateFlow(UVScreenState())
    val uiState: StateFlow<UVScreenState> = _uiState.asStateFlow()

    fun tick() {
        val sensors = sensorHub.state.value
        val loc = sensors.location
        val now = System.currentTimeMillis()

        val sunPos = if (loc != null)
            SunPositionCalculator.computeForLocalTime(loc.latitude, loc.longitude, now)
        else null

        val uvResult = if (sunPos != null && loc != null)
            UVIndexCalculator.compute(
                sunPosition = sunPos,
                altitudeMeters = loc.altitudeGps ?: 0.0,
                lightLux = sensors.lightLux,
                ozoneFactor = _uiState.value.ozoneFactor,
                surfaceType = _uiState.value.surfaceType,
                skinType = _uiState.value.skinType,
                spf = _uiState.value.spf,
                lat = loc.latitude,
                lon = loc.longitude,
                timeMillis = now
            )
        else null

        _uiState.value = _uiState.value.copy(
            sensorState = sensors,
            sunPosition = sunPos,
            uvResult = uvResult,
            computedAtUtcMillis = now
        )
    }

    fun setSkinType(type: SkinType) {
        _uiState.value = _uiState.value.copy(skinType = type)
    }

    fun setSpf(spf: Int) {
        _uiState.value = _uiState.value.copy(spf = spf)
    }

}

// ── Screen ───────────────────────────────────────────────────────────

@Composable
fun UVScreen(navController: NavController, vm: UVViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            vm.tick()
            kotlinx.coroutines.delay(3_000L)
        }
    }

    ModuleScaffold(title = "UV Index", navController = navController) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            UVIndexHero(state.uvResult)
            RecommendationCard(state.uvResult)
            ExposureCard(state.uvResult)
            VitaminDCard(state.uvResult)
            DailyCurveChart(state.uvResult?.dailyCurve)
            SunPositionCard(state.sunPosition, state.computedAtUtcMillis)
            SensorStatusBar(state.sensorState)
        }
    }
}

// ── UV Hero ──────────────────────────────────────────────────────────

@Composable
private fun UVIndexHero(result: UVResult?) {
    val uv = result?.uvIndex ?: 0.0
    val color = uvColor(uv)
    val category = result?.category ?: "—"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (result != null) String.format(Locale.US, "%.1f", uv) else "—",
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = category,
                style = MaterialTheme.typography.titleMedium,
                color = color
            )
        }
    }
}

// ── Recommendation ───────────────────────────────────────────────────

@Composable
private fun RecommendationCard(result: UVResult?) {
    val uv = result?.uvIndex ?: 0.0
    val text = when {
        uv < 3 -> "Low UV — Enjoy the outdoors! Sunscreen optional for most skin types."
        uv < 6 -> "Moderate UV — Wear sunscreen SPF 30+, sunglasses. Seek shade during midday."
        uv < 8 -> "High UV — Reduce exposure 10am-4pm. SPF 30+ required, hat and sunglasses advised."
        uv < 11 -> "Very High UV — Minimize outdoor time. SPF 50+, protective clothing, seek shade."
        else -> "Extreme UV — Avoid outdoor exposure. Full protection required if you must be outside."
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Recommendation", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ── Safe Exposure ────────────────────────────────────────────────────

@Composable
private fun ExposureCard(result: UVResult?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Safe Exposure", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ExposureStat(
                    "No sunscreen",
                    formatMinutes(result?.safeExposureMin)
                )
                ExposureStat(
                    "With SPF 30",
                    formatMinutes(result?.safeWithSpfMin)
                )
            }
        }
    }
}

@Composable
private fun ExposureStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

// ── Vitamin D ────────────────────────────────────────────────────────

@Composable
private fun VitaminDCard(result: UVResult?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Vitamin D synthesis", style = MaterialTheme.typography.titleSmall)
            Text(
                formatMinutes(result?.vitaminDMin),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ── Daily Curve ──────────────────────────────────────────────────────

@Composable
private fun DailyCurveChart(curve: List<Pair<Int, Double>>?) {
    if (curve.isNullOrEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Daily UV Curve", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))

            val textMeasurer = rememberTextMeasurer()
            val labelStyle = TextStyle(fontSize = 10.sp, color = Color.Gray)
            val lineColor = MaterialTheme.colorScheme.primary

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            ) {
                val maxUV = curve.maxOf { it.second }.coerceAtLeast(1.0)
                val leftPad = 30.dp.toPx()
                val bottomPad = 20.dp.toPx()
                val chartWidth = size.width - leftPad
                val chartHeight = size.height - bottomPad
                val stepX = chartWidth / (curve.size - 1).coerceAtLeast(1)

                // Y-axis labels
                for (tick in 0..maxUV.roundToInt() step (maxUV.roundToInt() / 3).coerceAtLeast(1)) {
                    val y = chartHeight - (tick / maxUV * chartHeight).toFloat()
                    val label = textMeasurer.measure(tick.toString(), labelStyle)
                    drawText(label, topLeft = Offset(0f, y - label.size.height / 2))
                    drawLine(
                        Color.Gray.copy(alpha = 0.2f),
                        start = Offset(leftPad, y),
                        end = Offset(size.width, y)
                    )
                }

                // X-axis labels
                for ((i, point) in curve.withIndex()) {
                    if (i % 3 == 0 || i == curve.size - 1) {
                        val x = leftPad + i * stepX
                        val label = textMeasurer.measure("${point.first}h", labelStyle)
                        drawText(label, topLeft = Offset(x - label.size.width / 2, chartHeight + 4.dp.toPx()))
                    }
                }

                // Curve
                val path = Path()
                curve.forEachIndexed { i, (_, uv) ->
                    val x = leftPad + i * stepX
                    val y = chartHeight - (uv / maxUV * chartHeight).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, lineColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))

                // Current-hour dot
                val nowHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val idx = curve.indexOfFirst { it.first == nowHour }
                if (idx >= 0) {
                    val x = leftPad + idx * stepX
                    val y = chartHeight - (curve[idx].second / maxUV * chartHeight).toFloat()
                    drawCircle(lineColor, radius = 5.dp.toPx(), center = Offset(x, y))
                }
            }
        }
    }
}

// ── Sun Position ─────────────────────────────────────────────────────

@Composable
private fun SunPositionCard(sun: SunPosition?, anchorUtcMillis: Long) {
    if (sun == null) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Sun Position", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            InfoRow("Altitude", String.format(Locale.US, "%.1f°", sun.altitudeDeg))
            InfoRow("Azimuth", String.format(Locale.US, "%.1f°", sun.azimuthDeg))
            InfoRow("Sunrise", formatLocalTimeFromUtcHours(sun.sunriseUtc, anchorUtcMillis))
            InfoRow("Sunset", formatLocalTimeFromUtcHours(sun.sunsetUtc, anchorUtcMillis))
            InfoRow("Solar Noon", formatLocalTimeFromUtcHours(sun.solarNoonUtc, anchorUtcMillis))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

// ── Sensor Status ────────────────────────────────────────────────────

@Composable
private fun SensorStatusBar(state: SensorState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SensorDot("GPS", state.gpsAcquired)
            SensorDot("Baro", state.hasBarometer && state.pressureHpa != null)
            SensorDot("Light", state.hasLightSensor && state.lightLux != null)
            SensorDot("Compass", state.hasCompass && state.compassHeadingDeg != null)
            SensorDot("Steps", state.hasStepCounter && state.stepCount != null)
        }
    }
}

@Composable
private fun SensorDot(label: String, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(if (active) Color(0xFF4CAF50) else Color(0xFF757575))
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

// ── Utilities ────────────────────────────────────────────────────────

private fun uvColor(uv: Double): Color = when {
    uv < 3 -> Color(0xFF4CAF50)
    uv < 6 -> Color(0xFFFFC107)
    uv < 8 -> Color(0xFFFF9800)
    uv < 11 -> Color(0xFFF44336)
    else -> Color(0xFF9C27B0)
}

private fun formatMinutes(min: Double?): String {
    if (min == null || min >= 9999) return "—"
    val rounded = min.roundToInt()
    return if (rounded >= 60) "${rounded / 60}h ${rounded % 60}m" else "${rounded}m"
}

/**
 * Converts a fractional UTC hour (0..24, anchored to UTC midnight of the
 * computation day) into a local-time "HH:mm" string in the device's timezone.
 */
private fun formatLocalTimeFromUtcHours(hoursUtc: Double, anchorUtcMillis: Long): String {
    if (hoursUtc < 0 || hoursUtc >= 24) return "—"
    val utcMidnight = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = anchorUtcMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val eventMillis = utcMidnight + (hoursUtc * 3_600_000.0).toLong()
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }
    return fmt.format(Date(eventMillis))
}
