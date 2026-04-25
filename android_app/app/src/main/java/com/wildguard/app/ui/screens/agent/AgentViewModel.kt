package com.wildguard.app.ui.screens.agent

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildguard.app.WildGuardApp
import com.wildguard.app.core.model.SensorState
import com.wildguard.app.llm.agent.*
import com.wildguard.app.llm.agent.presets.*
import com.wildguard.app.llm.agent.tools.*
import com.wildguard.app.llm.provider.ProviderRegistry
import com.wildguard.app.modules.weather.PressureLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AgentUiState(
    val hasProvider: Boolean = false,
    val providerName: String = "",
    val isRunning: Boolean = false,
    val turns: List<AgentTurn> = emptyList(),
    val iterationCount: Int = 0,
    val activePresetId: String = "outdoor_window",
    val errorMessage: String? = null,
    val exportedMarkdown: String? = null
)

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val registry = ProviderRegistry(application)
    private val toolRegistry = ToolRegistry()
    private val logger = AgentLogger(application)
    private val pressureLogger = PressureLogger(application)
    private val sensorHub = WildGuardApp.instance.sensorHub
    private val settingsPrefs = application.getSharedPreferences("wildguard_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state

    private var currentSensor = SensorState()
    private var conversationState = ConversationState()

    val presets = listOf(
        OutdoorWindowPreset.preset,
        SummitGoNoGoPreset.preset,
        StargazerPreset.preset
    )

    init {
        toolRegistry.registerAll(
            GetLocationAndTimeTool(),
            GetSensorSnapshotTool(),
            ComputeSunTimelineTool(),
            ComputeMoonAndTwilightTool(),
            ComputeTideScheduleTool(),
            FetchOnlineWeatherTool(),
            ForecastZambrettiTool(),
            ComputeAltitudeRiskTool(),
            ComputeThermalRiskTool(),
            ComputeUvDoseTool(),
            ComputePlanetVisibilityTool(),
            ComputeStarsAboveTool()
        )
        refreshProviderStatus()
        viewModelScope.launch {
            sensorHub.state.collect { sensor ->
                currentSensor = sensor
                sensor.pressureHpa?.let { pressureLogger.recordReading(it) }
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

    fun setActivePreset(presetId: String) {
        _state.value = _state.value.copy(activePresetId = presetId)
    }

    fun newConversation() {
        conversationState = ConversationState()
        _state.value = _state.value.copy(
            turns = emptyList(),
            iterationCount = 0,
            errorMessage = null,
            exportedMarkdown = null
        )
    }

    fun runAgent(userMessage: String) {
        val provider = registry.getActiveProvider() ?: run {
            _state.value = _state.value.copy(errorMessage = "No LLM provider configured")
            return
        }

        val preset = presets.find { it.id == _state.value.activePresetId } ?: presets.first()
        logger.startNewConversation()
        conversationState = ConversationState()

        _state.value = _state.value.copy(
            isRunning = true,
            turns = emptyList(),
            iterationCount = 0,
            errorMessage = null,
            exportedMarkdown = null
        )

        val ctx = ToolContext(
            sensor = currentSensor,
            appContext = getApplication(),
            pressureLogger = pressureLogger
        )

        val runner = AgentRunner(registry = toolRegistry, logger = logger)

        viewModelScope.launch {
            runner.run(
                userMessage = userMessage,
                presetTools = preset.toolNames,
                systemPrompt = preset.systemPrompt,
                provider = provider,
                ctx = ctx,
                state = conversationState,
                onTurnAppended = { _ ->
                    _state.value = _state.value.copy(
                        turns = conversationState.turns.value,
                        iterationCount = conversationState.iterationCount
                    )
                }
            )
            _state.value = _state.value.copy(isRunning = false)
        }
    }

    fun exportMarkdown(): String {
        val md = logger.exportAsMarkdown(_state.value.turns, _state.value.providerName)
        _state.value = _state.value.copy(exportedMarkdown = md)
        return md
    }

    fun saveToDownloads(): Boolean {
        val md = exportMarkdown()
        return logger.saveToDownloads(md)
    }

    fun getShareIntent(): android.content.Intent {
        val md = exportMarkdown()
        return logger.createShareIntent(md)
    }
}
