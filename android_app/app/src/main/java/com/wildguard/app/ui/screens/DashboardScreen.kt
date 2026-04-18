package com.wildguard.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.wildguard.app.ui.Routes
import com.wildguard.app.ui.theme.VisualMode

data class ModuleCard(val title: String, val icon: ImageVector, val route: String, val subtitle: String)

private val modules = listOf(
    ModuleCard("UV Index", Icons.Default.WbSunny, Routes.UV, "Sun safety & exposure"),
    ModuleCard("Weather", Icons.Default.Cloud, Routes.WEATHER, "Zambretti forecast"),
    ModuleCard("Altitude", Icons.Default.Terrain, Routes.ALTITUDE, "Elevation & sickness"),
    ModuleCard("Sky", Icons.Default.NightsStay, Routes.CELESTIAL, "Moon, planets, stars"),
    ModuleCard("Tides", Icons.Default.Waves, Routes.TIDE, "Tide predictions"),
    ModuleCard("Compass", Icons.Default.Explore, Routes.COMPASS, "Navigation & bearing"),
    ModuleCard("Thermal", Icons.Default.Thermostat, Routes.THERMAL, "Heat & cold risk"),
    ModuleCard("Conditions", Icons.Default.Checklist, Routes.CONDITIONS, "Weather check-in"),
    ModuleCard("AI Insights", Icons.Default.AutoAwesome, Routes.INSIGHTS, "LLM-powered planning"),
    ModuleCard("Settings", Icons.Default.Settings, Routes.SETTINGS, "Preferences & API keys"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navController: NavController,
    currentVisualMode: VisualMode = VisualMode.STANDARD,
    onVisualModeSelected: (VisualMode) -> Unit = {}
) {
    var showModeMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WildGuard") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    Box {
                        IconButton(onClick = { showModeMenu = true }) {
                            Icon(
                                imageVector = iconForMode(currentVisualMode),
                                contentDescription = "Visual mode: ${labelForMode(currentVisualMode)}",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = showModeMenu,
                            onDismissRequest = { showModeMenu = false }
                        ) {
                            VisualMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(labelForMode(mode)) },
                                    onClick = {
                                        onVisualModeSelected(mode)
                                        showModeMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = iconForMode(mode),
                                            contentDescription = null
                                        )
                                    },
                                    trailingIcon = {
                                        if (mode == currentVisualMode) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Active"
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding)
        ) {
            items(modules) { module ->
                ModuleTile(module) { navController.navigate(module.route) }
            }
        }
    }
}

@Composable
private fun ModuleTile(module: ModuleCard, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.2f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                module.icon,
                contentDescription = module.title,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(module.title, style = MaterialTheme.typography.titleMedium)
            Text(
                module.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

private fun iconForMode(mode: VisualMode): ImageVector = when (mode) {
    VisualMode.STANDARD -> Icons.Default.Brightness6
    VisualMode.DAYLIGHT_CONTRAST -> Icons.Default.WbSunny
    VisualMode.GLANCEABLE -> Icons.Default.Visibility
    VisualMode.NIGHT_RED -> Icons.Default.NightsStay
    VisualMode.SNOW_GLARE -> Icons.Default.AcUnit
    VisualMode.MARINE_WET -> Icons.Default.Waves
}

private fun labelForMode(mode: VisualMode): String = when (mode) {
    VisualMode.STANDARD -> "Standard"
    VisualMode.DAYLIGHT_CONTRAST -> "Daylight"
    VisualMode.GLANCEABLE -> "Glanceable"
    VisualMode.NIGHT_RED -> "Night Red"
    VisualMode.SNOW_GLARE -> "Snow Glare"
    VisualMode.MARINE_WET -> "Marine"
}
