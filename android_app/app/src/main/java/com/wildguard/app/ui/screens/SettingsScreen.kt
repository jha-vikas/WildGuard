package com.wildguard.app.ui.screens

import android.app.Application
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildguard.app.llm.provider.*
import com.wildguard.app.modules.uv.SkinType
import com.wildguard.app.modules.uv.SurfaceType
import com.wildguard.app.ui.Routes
import com.wildguard.app.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ── View State ───────────────────────────────────────────────────────────

data class SettingsUiState(
    val skinType: SkinType = SkinType.TYPE_II,
    val spf: Int = 30,
    val surfaceType: SurfaceType = SurfaceType.GRASS,
    val bodyWeightKg: Int = 70,
    val providers: List<ProviderConfig> = emptyList(),
    val showAddProvider: Boolean = false,
    val editingProvider: ProviderConfig? = null,
    val testResult: String? = null,
    val isTesting: Boolean = false,
    val coordinateRounding: Boolean = false,
    val promptReview: Boolean = false,
    val appVersion: String = "1.0"
)

// ── ViewModel ────────────────────────────────────────────────────────────

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val registry = ProviderRegistry(application)
    private val prefs = application.getSharedPreferences("wildguard_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    init {
        loadSettings()
    }

    private fun loadSettings() {
        _state.value = _state.value.copy(
            skinType = SkinType.entries.getOrNull(prefs.getInt("skin_type", 1)) ?: SkinType.TYPE_II,
            spf = prefs.getInt("spf", 30),
            surfaceType = SurfaceType.entries.getOrNull(prefs.getInt("surface_type", 0)) ?: SurfaceType.GRASS,
            bodyWeightKg = prefs.getInt("body_weight_kg", 70),
            providers = registry.getAll(),
            coordinateRounding = prefs.getBoolean("coordinate_rounding", false),
            promptReview = prefs.getBoolean("prompt_review", false)
        )
    }

    fun setSkinType(type: SkinType) {
        prefs.edit().putInt("skin_type", type.ordinal).apply()
        _state.value = _state.value.copy(skinType = type)
    }

    fun setSpf(spf: Int) {
        prefs.edit().putInt("spf", spf).apply()
        _state.value = _state.value.copy(spf = spf)
    }

    fun setSurfaceType(type: SurfaceType) {
        prefs.edit().putInt("surface_type", type.ordinal).apply()
        _state.value = _state.value.copy(surfaceType = type)
    }

    fun setBodyWeight(kg: Int) {
        prefs.edit().putInt("body_weight_kg", kg).apply()
        _state.value = _state.value.copy(bodyWeightKg = kg)
    }

    fun setCoordinateRounding(enabled: Boolean) {
        prefs.edit().putBoolean("coordinate_rounding", enabled).apply()
        _state.value = _state.value.copy(coordinateRounding = enabled)
    }

    fun setPromptReview(enabled: Boolean) {
        prefs.edit().putBoolean("prompt_review", enabled).apply()
        _state.value = _state.value.copy(promptReview = enabled)
    }

    fun showAddProvider(template: ProviderConfig? = null) {
        _state.value = _state.value.copy(
            showAddProvider = true,
            editingProvider = template?.copy(id = template.id + "_" + System.currentTimeMillis()) ?: ProviderConfig(
                id = "custom_${System.currentTimeMillis()}",
                type = ApiFormat.OPENAI_COMPATIBLE,
                displayName = "Custom",
                endpoint = "",
                apiKey = "",
                model = ""
            ),
            testResult = null
        )
    }

    fun editProvider(config: ProviderConfig) {
        _state.value = _state.value.copy(
            showAddProvider = true,
            editingProvider = config,
            testResult = null
        )
    }

    fun dismissProviderDialog() {
        _state.value = _state.value.copy(showAddProvider = false, editingProvider = null, testResult = null)
    }

    fun updateEditingProvider(config: ProviderConfig) {
        _state.value = _state.value.copy(editingProvider = config)
    }

    fun saveProvider() {
        val config = _state.value.editingProvider ?: return
        registry.saveProvider(config)
        if (registry.getAll().size == 1) registry.setActive(config.id)
        _state.value = _state.value.copy(
            providers = registry.getAll(),
            showAddProvider = false,
            editingProvider = null
        )
    }

    fun deleteProvider(id: String) {
        registry.removeProvider(id)
        _state.value = _state.value.copy(providers = registry.getAll())
    }

    fun setActiveProvider(id: String) {
        registry.setActive(id)
        _state.value = _state.value.copy(providers = registry.getAll())
    }

    fun testConnection() {
        val config = _state.value.editingProvider ?: return
        _state.value = _state.value.copy(isTesting = true, testResult = null)
        viewModelScope.launch {
            val result = registry.testProvider(config)
            _state.value = _state.value.copy(
                isTesting = false,
                testResult = result.fold(
                    onSuccess = { "Connected: $it" },
                    onFailure = { "Failed: ${it.message}" }
                )
            )
        }
    }
}

// ── Screen Composable ────────────────────────────────────────────────────

@Composable
fun SettingsScreen(navController: NavController) {
    val vm: SettingsViewModel = viewModel()
    val state by vm.state.collectAsState()

    ModuleScaffold(title = "Settings", navController = navController) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // ── User Profile ─────────────────────────────────────────
            item { SectionHeader("User Profile") }
            item { SkinTypeSelector(state.skinType) { vm.setSkinType(it) } }
            item { SpfSelector(state.spf) { vm.setSpf(it) } }
            item { SurfaceTypeSelector(state.surfaceType) { vm.setSurfaceType(it) } }
            item { BodyWeightInput(state.bodyWeightKg) { vm.setBodyWeight(it) } }

            // ── LLM Providers ────────────────────────────────────────
            item { SectionHeader("LLM Providers") }
            items(state.providers) { config ->
                ProviderRow(
                    config = config,
                    onTap = { vm.editProvider(config) },
                    onActivate = { vm.setActiveProvider(config.id) }
                )
            }
            item { AddProviderButton { vm.showAddProvider() } }

            // ── Privacy ──────────────────────────────────────────────
            item { SectionHeader("Privacy") }
            item {
                ToggleRow(
                    label = "Round coordinates (~1km)",
                    description = "Rounds lat/lon to 0.01° before sending to LLM",
                    checked = state.coordinateRounding,
                    onChecked = { vm.setCoordinateRounding(it) }
                )
            }
            item {
                ToggleRow(
                    label = "Prompt review",
                    description = "Review prompt before each LLM call",
                    checked = state.promptReview,
                    onChecked = { vm.setPromptReview(it) }
                )
            }

            // ── Help ─────────────────────────────────────────────────
            item { SectionHeader("Help") }
            item {
                HelpRow(
                    onOpen = { navController.navigate(Routes.HELP) }
                )
            }

            // ── About ────────────────────────────────────────────────
            item { SectionHeader("About") }
            item { AboutRow(state.appVersion) }
        }
    }

    // Provider dialog
    if (state.showAddProvider && state.editingProvider != null) {
        ProviderDialog(
            config = state.editingProvider!!,
            testResult = state.testResult,
            isTesting = state.isTesting,
            onUpdate = { vm.updateEditingProvider(it) },
            onTest = { vm.testConnection() },
            onSave = { vm.saveProvider() },
            onDelete = {
                vm.deleteProvider(state.editingProvider!!.id)
                vm.dismissProviderDialog()
            },
            onDismiss = { vm.dismissProviderDialog() }
        )
    }
}

// ── Section Components ───────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkinTypeSelector(current: SkinType, onSelect: (SkinType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = "Type ${current.ordinal + 1} (${current.name.replace("TYPE_", "")})",
            onValueChange = {},
            readOnly = true,
            label = { Text("Skin Type (Fitzpatrick)") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SkinType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text("Type ${type.ordinal + 1} – ${type.name.replace("TYPE_", "")}") },
                    onClick = { onSelect(type); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpfSelector(current: Int, onSelect: (Int) -> Unit) {
    val options = listOf(0, 15, 30, 50)
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = if (current == 0) "None" else "SPF $current",
            onValueChange = {},
            readOnly = true,
            label = { Text("SPF") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { spf ->
                DropdownMenuItem(
                    text = { Text(if (spf == 0) "None" else "SPF $spf") },
                    onClick = { onSelect(spf); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SurfaceTypeSelector(current: SurfaceType, onSelect: (SurfaceType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = current.name.lowercase().replaceFirstChar { it.uppercase() },
            onValueChange = {},
            readOnly = true,
            label = { Text("Surface Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SurfaceType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    onClick = { onSelect(type); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun BodyWeightInput(current: Int, onSet: (Int) -> Unit) {
    var text by remember(current) { mutableStateOf(current.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { it.isDigit() }
            text.toIntOrNull()?.let { if (it in 20..300) onSet(it) }
        },
        label = { Text("Body Weight (kg)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ProviderRow(config: ProviderConfig, onTap: () -> Unit, onActivate: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = config.isActive, onClick = onActivate)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(config.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "${config.model} • ${config.type.name.lowercase().replace("_", " ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            if (config.isActive) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(WildGreen)
                )
            }
        }
    }
}

@Composable
private fun AddProviderButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Add Provider")
    }
}

@Composable
private fun ToggleRow(label: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun AboutRow(version: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("WildGuard v$version", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = WildGreen.copy(alpha = 0.15f)
                ) {
                    Text(
                        "OFFLINE-FIRST",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = WildGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "All core calculations run on-device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun HelpRow(onOpen: () -> Unit) {
    OutlinedButton(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Open Help & Troubleshooting")
    }
}

// ── Provider Dialog ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDialog(
    config: ProviderConfig,
    testResult: String?,
    isTesting: Boolean,
    onUpdate: (ProviderConfig) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    var templateExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("LLM Provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Template dropdown
                ExposedDropdownMenuBox(
                    expanded = templateExpanded,
                    onExpandedChange = { templateExpanded = it }
                ) {
                    OutlinedTextField(
                        value = config.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Template") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(templateExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = templateExpanded,
                        onDismissRequest = { templateExpanded = false }
                    ) {
                        val templates = ProviderTemplates.ALL + listOf(
                            ProviderConfig(
                                id = "custom", type = ApiFormat.OPENAI_COMPATIBLE,
                                displayName = "Custom", endpoint = "", apiKey = "", model = ""
                            )
                        )
                        templates.forEach { tmpl ->
                            DropdownMenuItem(
                                text = { Text(tmpl.displayName) },
                                onClick = {
                                    onUpdate(
                                        config.copy(
                                            type = tmpl.type,
                                            displayName = tmpl.displayName,
                                            endpoint = tmpl.endpoint,
                                            model = tmpl.model
                                        )
                                    )
                                    templateExpanded = false
                                }
                            )
                        }
                    }
                }

                // API Key
                OutlinedTextField(
                    value = config.apiKey,
                    onValueChange = { onUpdate(config.copy(apiKey = it)) },
                    label = { Text("API Key") },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle key visibility"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Endpoint
                OutlinedTextField(
                    value = config.endpoint,
                    onValueChange = { onUpdate(config.copy(endpoint = it)) },
                    label = { Text("Endpoint URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Model
                OutlinedTextField(
                    value = config.model,
                    onValueChange = { onUpdate(config.copy(model = it)) },
                    label = { Text("Model") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Test connection
                OutlinedButton(
                    onClick = onTest,
                    enabled = !isTesting && config.apiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isTesting) "Testing..." else "Test Connection")
                }

                if (testResult != null) {
                    val isSuccess = testResult.startsWith("Connected")
                    Text(
                        testResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSuccess) WildGreen else WildRed
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = WildRed)) {
                    Text("Delete")
                }
                Button(onClick = onSave) {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
