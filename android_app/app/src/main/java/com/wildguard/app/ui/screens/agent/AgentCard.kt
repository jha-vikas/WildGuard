package com.wildguard.app.ui.screens.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wildguard.app.ui.theme.WildAmber
import com.wildguard.app.ui.theme.WildGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentCard(viewModel: AgentViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var userInput by remember { mutableStateOf("") }
    var showExportMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshProviderStatus()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
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
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = WildGreen,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Agent",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                if (state.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${state.iterationCount}/10",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (!state.hasProvider) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Configure an LLM provider in Settings to use the Agent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                return@Column
            }

            Spacer(Modifier.height(12.dp))

            // Preset selector
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                viewModel.presets.forEachIndexed { index, preset ->
                    SegmentedButton(
                        selected = state.activePresetId == preset.id,
                        onClick = {
                            viewModel.setActivePreset(preset.id)
                            viewModel.newConversation()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = viewModel.presets.size),
                        enabled = !state.isRunning
                    ) {
                        Text(preset.displayName, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Starter chips
            val activePreset = viewModel.presets.find { it.id == state.activePresetId }
            if (state.turns.isEmpty() && activePreset != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    activePreset.starterChips.forEach { chip ->
                        SuggestionChip(
                            onClick = {
                                if (!state.isRunning) {
                                    userInput = chip
                                }
                            },
                            label = { Text(chip, style = MaterialTheme.typography.labelSmall) },
                            border = SuggestionChipDefaults.suggestionChipBorder(
                                enabled = true,
                                borderColor = WildAmber.copy(alpha = 0.4f)
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Input row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = userInput,
                    onValueChange = { userInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask about outdoor conditions...") },
                    singleLine = true,
                    enabled = !state.isRunning,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.width(8.dp))
                if (state.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp
                    )
                } else {
                    FilledIconButton(
                        onClick = {
                            if (userInput.isNotBlank()) {
                                viewModel.runAgent(userInput.trim())
                                userInput = ""
                            }
                        },
                        enabled = userInput.isNotBlank(),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = WildGreen
                        )
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Run")
                    }
                }
            }

            // Turns
            if (state.turns.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(Modifier.height(8.dp))

                state.turns.forEachIndexed { idx, turn ->
                    TurnCard(turn = turn, index = idx)
                }

                Spacer(Modifier.height(8.dp))

                // Footer actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { viewModel.newConversation() },
                        enabled = !state.isRunning
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New")
                    }

                    Box {
                        TextButton(onClick = { showExportMenu = true }) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Export")
                        }
                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy to clipboard") },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                onClick = {
                                    showExportMenu = false
                                    val md = viewModel.exportMarkdown()
                                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clip.setPrimaryClip(ClipData.newPlainText("Agent Log", md))
                                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Save to Downloads") },
                                leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                                onClick = {
                                    showExportMenu = false
                                    val ok = viewModel.saveToDownloads()
                                    Toast.makeText(
                                        context,
                                        if (ok) "Saved to Downloads" else "Save failed",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = {
                                    showExportMenu = false
                                    val intent = viewModel.getShareIntent()
                                    context.startActivity(android.content.Intent.createChooser(intent, "Share log"))
                                }
                            )
                        }
                    }
                }

                if (!state.isRunning) {
                    Text(
                        "${state.iterationCount}/10 steps · ${state.providerName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
