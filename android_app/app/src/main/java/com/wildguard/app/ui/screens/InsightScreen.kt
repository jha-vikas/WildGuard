package com.wildguard.app.ui.screens

import android.app.Application
import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildguard.app.WildGuardApp
import com.wildguard.app.core.model.SensorState
import com.wildguard.app.llm.insight.*
import com.wildguard.app.llm.provider.ProviderRegistry
import com.wildguard.app.llm.provider.ReviewableProvider
import com.wildguard.app.ui.theme.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── View State ───────────────────────────────────────────────────────────

data class InsightUiState(
    val hasProvider: Boolean = false,
    val providerName: String = "",
    val isLoading: Boolean = false,
    val isOffline: Boolean = false,
    val tacticalWindows: List<TacticalWindow> = emptyList(),
    val tacticalSeriesStartMs: Long = 0L,
    val driftRisk: String = "none",
    val driftWarning: DriftWarning? = null,
    val celestialEvents: List<CelestialEvent> = emptyList(),
    val sensorHealth: SensorHealthState = SensorHealthState.Unknown,
    val consistencyAlert: ConsistencyAlert? = null,
    val bindingAnalysis: BindingAnalysis? = null,
    val showBindingInput: Boolean = false,
    val errorMessage: String? = null,
    val pendingPromptReview: String? = null
)

sealed class SensorHealthState {
    data object Unknown : SensorHealthState()
    data object Healthy : SensorHealthState()
    data class Warning(val message: String) : SensorHealthState()
    data class NoData(val reason: String) : SensorHealthState()
}

// ── ViewModel ────────────────────────────────────────────────────────────

class InsightViewModel(application: Application) : AndroidViewModel(application) {

    private val registry = ProviderRegistry(application)
    private val tacticalDetector = TacticalWindowDetector(application)
    private val driftAnalyzer = DriftAnalyzer()
    private val celestialFinder = CelestialAlignmentFinder()
    private val consistencyChecker = SensorConsistencyChecker()
    private val bindingPlanner = BindingConstraintPlanner()
    private val sensorHub = WildGuardApp.instance.sensorHub
    private val settingsPrefs = application.getSharedPreferences("wildguard_settings", Context.MODE_PRIVATE)

    private val reviewChannel = Channel<Boolean>(Channel.RENDEZVOUS)

    private val _state = MutableStateFlow(InsightUiState())
    val state: StateFlow<InsightUiState> = _state

    private var currentSensor = SensorState()

    init {
        refreshProviderStatus()
        viewModelScope.launch {
            sensorHub.state.collect { sensor ->
                updateSensor(sensor)
            }
        }
    }

    fun refreshProviderStatus() {
        val config = registry.getActiveConfig()
        _state.value = _state.value.copy(
            hasProvider = config != null,
            providerName = config?.displayName ?: ""
        )
    }

    fun updateSensor(sensor: SensorState) {
        currentSensor = sensor
        driftAnalyzer.recordSample(sensor, null)
        _state.value = _state.value.copy(driftRisk = driftAnalyzer.getRiskLevel())

        val check = consistencyChecker.check(sensor)
        _state.value = _state.value.copy(
            sensorHealth = when (check) {
                is ConsistencyCheckResult.Consistent -> SensorHealthState.Healthy
                is ConsistencyCheckResult.Divergent -> SensorHealthState.Warning(
                    "Compass diverges ${check.compassVsSunDelta.toInt()}° from expected"
                )
                is ConsistencyCheckResult.Inconclusive -> SensorHealthState.NoData(check.reason)
                is ConsistencyCheckResult.NoData -> SensorHealthState.NoData("Waiting for sensor data")
            }
        )
    }

    fun toggleBindingInput() {
        _state.value = _state.value.copy(showBindingInput = !_state.value.showBindingInput)
    }

    fun confirmPromptReview() { viewModelScope.launch { reviewChannel.send(true) } }
    fun cancelPromptReview()  { viewModelScope.launch { reviewChannel.send(false) } }

    private fun wrapWithReviewIfEnabled(base: com.wildguard.app.llm.provider.LlmProvider): com.wildguard.app.llm.provider.LlmProvider {
        val enabled = settingsPrefs.getBoolean("prompt_review", false)
        return if (!enabled) base else ReviewableProvider(base) { prompt ->
            _state.value = _state.value.copy(pendingPromptReview = prompt)
            val confirmed = reviewChannel.receive()
            _state.value = _state.value.copy(pendingPromptReview = null)
            confirmed
        }
    }

    fun runAllInsights() {
        val rawProvider = registry.getActiveProvider() ?: run {
            _state.value = _state.value.copy(errorMessage = "No provider configured")
            return
        }
        val provider = wrapWithReviewIfEnabled(rawProvider)

        _state.value = _state.value.copy(
            isLoading = true,
            errorMessage = null,
            isOffline = false,
            tacticalWindows = emptyList(),
            driftWarning = null,
            celestialEvents = emptyList(),
            consistencyAlert = null
        )
        var failureCount = 0

        viewModelScope.launch {
            val defaultProfile = tacticalDetector.loadConstraintProfiles().firstOrNull()
                ?: ConstraintProfile(name = "default", maxUV = 8.0, minSunElDeg = 5.0)
            tacticalDetector.detect(currentSensor, defaultProfile, provider)
                .onSuccess {
                    _state.value = _state.value.copy(
                        tacticalWindows = it.windows,
                        tacticalSeriesStartMs = it.seriesStartMs
                    )
                }
                .onFailure { failureCount++ }

            if (driftAnalyzer.shouldTriggerLlm()) {
                driftAnalyzer.analyze(provider)
                    .onSuccess { _state.value = _state.value.copy(driftWarning = it, driftRisk = it.riskLabel) }
                    .onFailure { failureCount++ }
            }

            val loc = currentSensor.location
            if (loc != null) {
                celestialFinder.find(loc.latitude, loc.longitude, provider)
                    .onSuccess { _state.value = _state.value.copy(celestialEvents = it) }
                    .onFailure { failureCount++ }
            }

            if (_state.value.sensorHealth is SensorHealthState.Warning) {
                consistencyChecker.checkAndAnalyze(currentSensor, provider)
                    .onSuccess { _state.value = _state.value.copy(consistencyAlert = it) }
                    .onFailure { failureCount++ }
            }

            val allFailed = failureCount > 0 &&
                _state.value.tacticalWindows.isEmpty() &&
                _state.value.celestialEvents.isEmpty() &&
                _state.value.driftWarning == null &&
                _state.value.consistencyAlert == null

            _state.value = _state.value.copy(
                isLoading = false,
                isOffline = allFailed,
                errorMessage = if (failureCount > 0 && !allFailed)
                    "$failureCount insight(s) failed — partial results shown"
                else if (allFailed) null
                else _state.value.errorMessage
            )
        }
    }

    fun runBindingAnalysis(legs: List<TripLeg>, startMs: Long) {
        val rawProvider = registry.getActiveProvider() ?: return
        val provider = wrapWithReviewIfEnabled(rawProvider)
        val loc = currentSensor.location ?: return

        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            bindingPlanner.analyze(legs, startMs, loc.latitude, loc.longitude, provider).onSuccess {
                _state.value = _state.value.copy(bindingAnalysis = it)
            }.onFailure {
                _state.value = _state.value.copy(errorMessage = it.message)
            }
            _state.value = _state.value.copy(isLoading = false)
        }
    }
}

// ── Screen Composable ────────────────────────────────────────────────────

@Composable
fun InsightScreen(navController: NavController) {
    val vm: InsightViewModel = viewModel()
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.refreshProviderStatus() }

    if (state.pendingPromptReview != null) {
        PromptReviewDialog(
            prompt = state.pendingPromptReview!!,
            onConfirm = { vm.confirmPromptReview() },
            onCancel = { vm.cancelPromptReview() }
        )
    }

    ModuleScaffold(title = "AI Insights", navController = navController) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            if (!state.hasProvider) {
                item { NoProviderCard() }
            } else {
                item { ProviderHeader(state.providerName, state.isLoading) { vm.runAllInsights() } }

                if (state.isLoading) {
                    item { SkeletonCard() }
                    item { SkeletonCard() }
                } else if (state.isOffline) {
                    item { OfflineCard() }
                } else {
                    // Tactical Windows
                    item { TacticalWindowsCard(state.tacticalWindows, state.tacticalSeriesStartMs) }

                    // Drift Status
                    item { DriftStatusCard(state.driftRisk, state.driftWarning) }

                    // Celestial Events
                    if (state.celestialEvents.isNotEmpty()) {
                        item { CelestialEventsCard(state.celestialEvents) }
                    }

                    // Sensor Health
                    item { SensorHealthCard(state.sensorHealth, state.consistencyAlert) }

                    // Binding Constraint
                    item {
                        BindingConstraintSection(
                            analysis = state.bindingAnalysis,
                            showInput = state.showBindingInput,
                            isLoading = state.isLoading,
                            onToggle = { vm.toggleBindingInput() },
                            onAnalyze = { legs, startMs -> vm.runBindingAnalysis(legs, startMs) }
                        )
                    }

                    // Error
                    if (state.errorMessage != null) {
                        item { ErrorCard(state.errorMessage!!) }
                    }
                }
            }
        }
    }
}

// ── Card Components ──────────────────────────────────────────────────────

@Composable
private fun NoProviderCard() {
    LlmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Key,
                contentDescription = null,
                tint = WildAmber,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text("LLM Provider Required", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Configure an LLM provider in Settings to enable AI insights",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun ProviderHeader(name: String, isLoading: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(WildGreen)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        IconButton(onClick = onRefresh, enabled = !isLoading) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh insights")
            }
        }
    }
}

@Composable
private fun TacticalWindowsCard(windows: List<TacticalWindow>, seriesStartMs: Long) {
    val timeSdf    = remember { SimpleDateFormat("h:mma", Locale.getDefault()) }
    val markerSdf  = remember { SimpleDateFormat("ha",    Locale.getDefault()) }
    val totalMs    = 24 * 3_600_000L

    LlmCard {
        Text("Tactical Windows", style = MaterialTheme.typography.titleSmall)
        Text(
            "Best time blocks where conditions align. Color = quality: green good, amber constrained, red poor.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
        Spacer(Modifier.height(10.dp))

        if (windows.isEmpty()) {
            Text(
                "No windows computed yet. Tap refresh to analyze.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        } else {
            // ── Positioned timeline bar ───────────────────────────────
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            ) {
                windows.forEach { w ->
                    if (seriesStartMs == 0L) return@forEach
                    val startFrac = ((w.startMs - seriesStartMs).toFloat() / totalMs).coerceIn(0f, 1f)
                    val widthFrac = ((w.endMs - w.startMs).toFloat() / totalMs).coerceIn(0.01f, 1f - startFrac)
                    val color = when {
                        w.qualityScore > 0.7 -> WildGreen
                        w.qualityScore > 0.4 -> WildAmber
                        else -> WildRed
                    }
                    Box(
                        modifier = Modifier
                            .offset(x = maxWidth * startFrac)
                            .width(maxWidth * widthFrac)
                            .height(20.dp)
                            .background(color.copy(alpha = 0.75f))
                    )
                }
            }

            // ── Hourly tick labels ────────────────────────────────────
            if (seriesStartMs != 0L) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf(0, 6, 12, 18, 24).forEach { offsetH ->
                        Text(
                            text = markerSdf.format(Date(seriesStartMs + offsetH * 3_600_000L)).lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Window list with actual clock times ───────────────────
            windows.take(3).forEach { w ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${timeSdf.format(Date(w.startMs)).lowercase()} – ${timeSdf.format(Date(w.endMs)).lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        w.bindingConstraint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DriftStatusCard(risk: String, warning: DriftWarning?) {
    LlmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Drift Status", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(12.dp))
            DriftPill(risk)
        }
        Text(
            "Trend watch — not current conditions, but which direction things are heading over time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
        if (warning != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Primary: ${warning.primaryDriver} | Lead: ${warning.leadTimeMin}min",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                warning.suggestedAction,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun DriftPill(risk: String) {
    val (color, label) = when (risk) {
        "red" -> WildRed to "HIGH"
        "amber" -> WildAmber to "ELEVATED"
        "low" -> WildGreenLight to "LOW"
        else -> Color.Gray to "STABLE"
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.2f),
        contentColor = color
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CelestialEventsCard(events: List<CelestialEvent>) {
    LlmCard {
        Text("Celestial Events", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(events) { event ->
                SuggestionChip(
                    onClick = {},
                    label = {
                        Column {
                            Text(event.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                            Text(
                                event.description.take(50),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    icon = {
                        Icon(Icons.Default.NightsStay, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
            }
        }
    }
}

@Composable
private fun SensorHealthCard(health: SensorHealthState, alert: ConsistencyAlert?) {
    LlmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sensor Health", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(12.dp))
            when (health) {
                is SensorHealthState.Healthy -> {
                    Icon(Icons.Default.CheckCircle, contentDescription = "OK", tint = WildGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Consistent", style = MaterialTheme.typography.labelSmall, color = WildGreen)
                }
                is SensorHealthState.Warning -> {
                    Icon(Icons.Default.Warning, contentDescription = "Warning", tint = WildAmber, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Divergent", style = MaterialTheme.typography.labelSmall, color = WildAmber)
                }
                is SensorHealthState.NoData -> {
                    Icon(Icons.Default.HelpOutline, contentDescription = "No data", tint = Color.Gray, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("No data", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                is SensorHealthState.Unknown -> {
                    Text("–", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
        if (alert != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Cause: ${alert.likelyCause}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                alert.recommendation,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun BindingConstraintSection(
    analysis: BindingAnalysis?,
    showInput: Boolean,
    isLoading: Boolean,
    onToggle: () -> Unit,
    onAnalyze: (List<TripLeg>, Long) -> Unit
) {
    LlmCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Multi-Leg Plan", style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (showInput) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (!showInput && analysis == null) {
            Text(
                "Define trip legs to find binding constraints",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }

        if (showInput) {
            Spacer(Modifier.height(8.dp))
            BindingLegInputForm(isLoading = isLoading, onAnalyze = onAnalyze)
        }

        if (analysis != null) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            Spacer(Modifier.height(8.dp))
            BindingResultContent(analysis)
        }
    }
}

@Composable
private fun BindingLegInputForm(isLoading: Boolean, onAnalyze: (List<TripLeg>, Long) -> Unit) {
    var legCount by remember { mutableIntStateOf(2) }
    val labels = remember { mutableStateListOf("Leg 1", "Leg 2") }
    val bearings = remember { mutableStateListOf("0", "180") }
    val durations = remember { mutableStateListOf("60", "60") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until legCount) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = labels.getOrElse(i) { "" },
                    onValueChange = { if (i < labels.size) labels[i] = it },
                    label = { Text("Name") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = bearings.getOrElse(i) { "" },
                    onValueChange = { if (i < bearings.size) bearings[i] = it },
                    label = { Text("Bearing°") },
                    modifier = Modifier.width(80.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = durations.getOrElse(i) { "" },
                    onValueChange = { if (i < durations.size) durations[i] = it },
                    label = { Text("Min") },
                    modifier = Modifier.width(64.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    legCount++
                    labels.add("Leg ${legCount}")
                    bearings.add("0")
                    durations.add("60")
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add leg")
            }

            Button(
                onClick = {
                    val legs = (0 until legCount).map { i ->
                        TripLeg(
                            label = labels.getOrElse(i) { "Leg ${i + 1}" },
                            bearingDeg = bearings.getOrElse(i) { "0" }.toDoubleOrNull() ?: 0.0,
                            durationMin = durations.getOrElse(i) { "60" }.toIntOrNull() ?: 60
                        )
                    }
                    onAnalyze(legs, System.currentTimeMillis())
                },
                modifier = Modifier.weight(1f),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Analytics, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text("Analyze")
            }
        }
    }
}

@Composable
private fun BindingResultContent(analysis: BindingAnalysis) {
    Text(
        "Binding leg: ${analysis.bindingLeg}",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium
    )
    Text(
        "Constraint: ${analysis.bindingConstraint}",
        style = MaterialTheme.typography.bodySmall
    )
    if (analysis.cascadeEffects.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text("Cascade:", style = MaterialTheme.typography.labelSmall, color = WildAmber)
        analysis.cascadeEffects.forEach {
            Text("  \u2022 $it", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (analysis.tradeoffSummary.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
            analysis.tradeoffSummary,
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun OfflineCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.WifiOff, contentDescription = null, tint = WildAmber)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("LLM Unavailable", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Showing raw factor matrix with relevant sensor values",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = WildRed.copy(alpha = 0.1f))
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = WildRed, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = WildRed)
        }
    }
}

@Composable
private fun SkeletonCard() {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = alpha)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            )
        }
    }
}

@Composable
private fun PromptReviewDialog(
    prompt: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.RateReview,
                    contentDescription = null,
                    tint = WildAmber,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Review Prompt", style = MaterialTheme.typography.titleSmall)
            }
        },
        text = {
            Column {
                Text(
                    "This prompt will be sent to your LLM provider. Review before sending.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                ) {
                    Text(
                        text = prompt,
                        modifier = Modifier
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Send") }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = WildRed)
            ) { Text("Cancel") }
        }
    )
}

@Composable
private fun LlmCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                ),
                start = Offset(0f, 0f),
                end = Offset(100f, 100f)
            )
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}
