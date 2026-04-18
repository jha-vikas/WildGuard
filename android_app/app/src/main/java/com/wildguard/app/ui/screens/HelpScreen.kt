package com.wildguard.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

private data class HelpSection(
    val title: String,
    val icon: ImageVector,
    val body: List<String>
)

@Composable
fun HelpScreen(navController: NavController) {
    val sections = listOf(
        HelpSection(
            title = "Using AI Insights",
            icon = Icons.Default.AutoFixHigh,
            body = listOf(
                "AI insights are optional. First add an LLM provider in Settings, save the API key, then open AI Insights and tap refresh.",
                "Tactical Windows: finds the best time blocks where multiple conditions line up, such as safer UV, usable daylight, and better weather stability.",
                "Drift Warning: watches trends over time and warns before conditions become bad. This is useful for storms, heat buildup, or fast weather shifts.",
                "Celestial Events: highlights good sky timing for navigation or photography based on your current location and time.",
                "Sensor Check: compares compass, sun position, and other available signals to flag when readings do not agree.",
                "Binding Constraints: analyzes a multi-leg outing and tells you which leg or condition is most likely to control the whole plan."
            )
        ),
        HelpSection(
            title = "How To Use Multi-Leg Plan",
            icon = Icons.Default.Route,
            body = listOf(
                "Open AI Insights and expand Multi-Leg Plan.",
                "Enter one row for each leg of the trip. Use a short name, the expected bearing in degrees, and the planned duration in minutes.",
                "Tap Analyze. The result shows the binding leg, the main constraint on that leg, and any cascade effects on the rest of the trip.",
                "Interpret the result as the bottleneck. If one leg is UV-limited, tide-limited, weather-limited, or timing-sensitive, improving that leg usually improves the full route more than changing easier legs.",
                "Use this when a trip has multiple segments with different headings or timings, such as out-and-back hikes, ridge traverses, coastal walks, or photo stops."
            )
        ),
        HelpSection(
            title = "Common Troubleshooting",
            icon = Icons.Default.Troubleshoot,
            body = listOf(
                "No AI results: make sure an LLM provider is configured in Settings and marked active.",
                "No celestial or planning output: wait for location to lock, then refresh AI Insights.",
                "Compass mismatch warning: move away from metal objects, recalibrate the phone, and compare again while standing still.",
                "Thermal screen missing data: complete Conditions Check-In because temperature, humidity, and wind are user-observed rather than phone-sensed.",
                "Weather or altitude feels off: give the barometer a little time to stabilize and avoid indoor pressure changes just before checking."
            )
        ),
        HelpSection(
            title = "Good Setup Checklist",
            icon = Icons.Default.Settings,
            body = listOf(
                "Grant location permission on first launch.",
                "Run Conditions Check-In before using Thermal and before asking AI to reason about comfort or exposure.",
                "Add your preferred LLM provider and test the connection in Settings.",
                "Refresh AI Insights after location or conditions have changed."
            )
        )
    )

    ModuleScaffold(title = "Help", navController = navController) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Text(
                    "Quick reference for setup, AI insights, planning, and common issues.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }

            items(sections) { section ->
                HelpSectionCard(section)
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        "Tip: AI insight quality depends on the freshness of your sensor data and manual check-in data.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun HelpSectionCard(section: HelpSection) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    section.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(0.dp))
                Text(
                    section.title,
                    modifier = Modifier.padding(start = 10.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(10.dp))

            section.body.forEach { line ->
                HelpBullet(line)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun HelpBullet(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Default.HelpOutline,
            contentDescription = null,
            modifier = Modifier.padding(top = 2.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            text,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
