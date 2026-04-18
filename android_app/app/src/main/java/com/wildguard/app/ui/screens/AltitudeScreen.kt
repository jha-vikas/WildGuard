package com.wildguard.app.ui.screens

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildguard.app.WildGuardApp
import com.wildguard.app.modules.altitude.*
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

data class AltitudeUiState(
    val currentAltitudeM: Double = 0.0,
    val altitudeSource: AltitudeSource = AltitudeSource.ESTIMATED,
    val totalGainM: Double = 0.0,
    val totalLossM: Double = 0.0,
    val ascentRateMPerDay: Double = 0.0,
    val sicknessRisk: AltitudeSicknessRisk? = null,
    val boilingPointC: Double = 100.0,
    val cookingMultiplier: Double = 1.0,
    val cookingNote: String = "",
    val altitudeHistory: List<AltitudePoint> = emptyList(),
    val lastUpdated: Long? = null,
    val showSymptomDialog: Boolean = false
)

// ── ViewModel ───────────────────────────────────────────────────────────────

class AltitudeViewModel(application: Application) : AndroidViewModel(application) {

    private val sensorHub = WildGuardApp.instance.sensorHub
    private val altimeter = BarometricAltimeter()
    private val tracker = ElevationTracker()
    private val sicknessCalc = AltitudeSicknessCalculator()
    private var currentSymptoms = LakeLouiseSymptoms()

    private val _uiState = MutableStateFlow(AltitudeUiState())
    val uiState: StateFlow<AltitudeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sensorHub.state.collect { sensor ->
                sensor.pressureHpa?.let { pressure ->
                    val alt = altimeter.updatePressure(pressure)
                    tracker.recordAltitude(alt)
                    refreshBoilingPoint(alt, pressure)
                }
                sensor.location?.let { loc ->
                    loc.altitudeGps?.let { gpsAlt ->
                        altimeter.calibrateWithGps(gpsAlt, sensor.pressureHpa)
                    }
                }
                refreshState()
            }
        }
    }

    fun updateSymptoms(symptoms: LakeLouiseSymptoms) {
        currentSymptoms = symptoms
        refreshState()
    }

    fun toggleSymptomDialog(show: Boolean) {
        _uiState.update { it.copy(showSymptomDialog = show) }
    }

    private fun refreshBoilingPoint(altitudeM: Double, pressureHpa: Float) {
        val summary = BoilingPointCalculator.summary(altitudeM, pressureHpa.toDouble())
        _uiState.update {
            it.copy(
                boilingPointC = summary.boilingPointC,
                cookingMultiplier = summary.cookingTimeMultiplier,
                cookingNote = summary.cookingNote
            )
        }
    }

    private fun refreshState() {
        val altitude = altimeter.altitudeMeters
        val risk = sicknessCalc.evaluate(altitude, currentSymptoms)

        _uiState.update {
            it.copy(
                currentAltitudeM = altitude,
                altitudeSource = altimeter.altitudeSource,
                totalGainM = tracker.totalGainM,
                totalLossM = tracker.totalLossM,
                ascentRateMPerDay = risk.ascentRate,
                sicknessRisk = risk,
                altitudeHistory = tracker.altitudeHistory,
                lastUpdated = System.currentTimeMillis()
            )
        }
    }
}

// ── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun AltitudeScreen(navController: NavController, vm: AltitudeViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()

    ModuleScaffold(title = "Altitude", navController = navController) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            AltitudeCard(state)

            ElevationGainLossRow(state)

            state.sicknessRisk?.let { risk ->
                SicknessRiskCard(risk, onOpenQuestionnaire = { vm.toggleSymptomDialog(true) })
            }

            AscentRateCard(state)

            BoilingPointCard(state)

            if (state.altitudeHistory.size >= 2) {
                Text("Elevation Profile", style = MaterialTheme.typography.titleMedium)
                ElevationChart(
                    points = state.altitudeHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                )
            }

            state.lastUpdated?.let { ts ->
                val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                Text(
                    text = "Last update: ${fmt.format(Date(ts))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (state.showSymptomDialog) {
        LakeLouiseDialog(
            onSubmit = { symptoms ->
                vm.updateSymptoms(symptoms)
                vm.toggleSymptomDialog(false)
            },
            onDismiss = { vm.toggleSymptomDialog(false) }
        )
    }
}

// ── Components ──────────────────────────────────────────────────────────────

@Composable
private fun AltitudeCard(state: AltitudeUiState) {
    val sourceColor = when (state.altitudeSource) {
        AltitudeSource.BAROMETER -> WildGreen
        AltitudeSource.GPS -> WildBlue
        AltitudeSource.ESTIMATED -> WildAmber
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Current Altitude", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "%.0f".format(state.currentAltitudeM),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "m",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = sourceColor.copy(alpha = 0.2f)
            ) {
                Text(
                    state.altitudeSource.name,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = sourceColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ElevationGainLossRow(state: AltitudeUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevationStatCard(
            label = "Gain",
            value = "%.0f m".format(state.totalGainM),
            icon = Icons.Default.TrendingUp,
            color = WildGreen,
            modifier = Modifier.weight(1f)
        )
        ElevationStatCard(
            label = "Loss",
            value = "%.0f m".format(state.totalLossM),
            icon = Icons.Default.TrendingDown,
            color = WildRed,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ElevationStatCard(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SicknessRiskCard(risk: AltitudeSicknessRisk, onOpenQuestionnaire: () -> Unit) {
    val riskColor = when (risk.riskLevel) {
        AcclimatizationStatus.SAFE -> WildGreen
        AcclimatizationStatus.CAUTION -> WildAmber
        AcclimatizationStatus.DANGER -> WildRed
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Altitude Sickness Risk", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = riskColor.copy(alpha = 0.2f)
                ) {
                    Text(
                        risk.riskLevel.name,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = riskColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                risk.recommendation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            if (risk.lakeLouiseScore > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Lake Louise Score: ${risk.lakeLouiseScore}/15",
                    style = MaterialTheme.typography.bodySmall,
                    color = riskColor
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenQuestionnaire) {
                Text("Log Symptoms")
            }
        }
    }
}

@Composable
private fun AscentRateCard(state: AltitudeUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Ascent Rate", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Text(
                    "${"%.0f".format(state.ascentRateMPerDay)} m/day",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (state.currentAltitudeM > 2500 && state.ascentRateMPerDay > 500) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = WildRed.copy(alpha = 0.2f)
                ) {
                    Text(
                        "TOO FAST",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = WildRed,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun BoilingPointCard(state: AltitudeUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Boiling Point at Altitude", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${"%.1f".format(state.boilingPointC)}°C",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Cooking: x${"%.2f".format(state.cookingMultiplier)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            if (state.cookingNote.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    state.cookingNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = WildAmber
                )
            }
        }
    }
}

@Composable
private fun ElevationChart(points: List<AltitudePoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) return

    val minAlt = points.minOf { it.altitudeM }
    val maxAlt = points.maxOf { it.altitudeM }
    val range = (maxAlt - minAlt).coerceAtLeast(20.0)
    val paddedMin = minAlt - range * 0.1
    val paddedMax = maxAlt + range * 0.1
    val paddedRange = paddedMax - paddedMin

    val startTime = points.first().timestampMillis
    val endTime = points.last().timestampMillis
    val timeSpan = (endTime - startTime).toFloat().coerceAtLeast(1f)

    val lineColor = WildGreen
    val fillColor = WildGreen.copy(alpha = 0.1f)
    val gridColor = Color.White.copy(alpha = 0.08f)
    val labelColor = Color.White.copy(alpha = 0.5f)

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val pad = 52f
            val cw = size.width - pad * 2
            val ch = size.height - pad - 24f

            for (i in 0..4) {
                val y = 24f + ch * i / 4
                drawLine(gridColor, Offset(pad, y), Offset(pad + cw, y), strokeWidth = 1f)

                val altLabel = paddedMax - paddedRange * i / 4
                drawContext.canvas.nativeCanvas.drawText(
                    "%.0f".format(altLabel),
                    4f, y + 4f,
                    android.graphics.Paint().apply {
                        color = labelColor.hashCode()
                        textSize = 22f
                        isAntiAlias = true
                    }
                )
            }

            val linePath = Path()
            val fillPath = Path()
            val baseY = 24f + ch

            points.forEachIndexed { i, pt ->
                val x = pad + cw * (pt.timestampMillis - startTime) / timeSpan
                val y = (24f + ch * (1.0 - (pt.altitudeM - paddedMin) / paddedRange)).toFloat()
                if (i == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, baseY)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }

            val lastX = pad + cw * (points.last().timestampMillis - startTime) / timeSpan
            fillPath.lineTo(lastX, baseY)
            fillPath.close()

            drawPath(fillPath, fillColor)
            drawPath(linePath, lineColor, style = Stroke(width = 2.5f))
        }
    }
}

// ── Lake Louise Symptom Dialog ──────────────────────────────────────────────

@Composable
private fun LakeLouiseDialog(
    onSubmit: (LakeLouiseSymptoms) -> Unit,
    onDismiss: () -> Unit
) {
    var headache by remember { mutableIntStateOf(0) }
    var giNausea by remember { mutableIntStateOf(0) }
    var fatigue by remember { mutableIntStateOf(0) }
    var dizziness by remember { mutableIntStateOf(0) }
    var sleepDifficulty by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lake Louise Symptom Assessment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Rate each symptom 0 (none) to 3 (severe):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                SymptomSlider("Headache", headache) { headache = it }
                SymptomSlider("GI / Nausea", giNausea) { giNausea = it }
                SymptomSlider("Fatigue / Weakness", fatigue) { fatigue = it }
                SymptomSlider("Dizziness", dizziness) { dizziness = it }
                SymptomSlider("Sleep difficulty", sleepDifficulty) { sleepDifficulty = it }

                val total = headache + giNausea + fatigue + dizziness + sleepDifficulty
                Text(
                    "Total score: $total / 15",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        total >= 6 -> WildRed
                        total >= 3 -> WildAmber
                        else -> WildGreen
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSubmit(LakeLouiseSymptoms(headache, giNausea, fatigue, dizziness, sleepDifficulty))
            }) { Text("Submit") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SymptomSlider(label: String, value: Int, onValueChange: (Int) -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..3f,
            steps = 2
        )
    }
}
