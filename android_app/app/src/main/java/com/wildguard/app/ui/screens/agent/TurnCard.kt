package com.wildguard.app.ui.screens.agent

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.GsonBuilder
import com.wildguard.app.llm.agent.AgentTurn
import com.wildguard.app.ui.theme.WildAmber
import com.wildguard.app.ui.theme.WildBlue
import com.wildguard.app.ui.theme.WildGreen
import com.wildguard.app.ui.theme.WildRed

private val prettyGson = GsonBuilder().setPrettyPrinting().create()

@Composable
fun TurnCard(turn: AgentTurn, index: Int) {
    when (turn) {
        is AgentTurn.User -> UserTurnCard(turn, index)
        is AgentTurn.AssistantThought -> ThoughtTurnCard(turn, index)
        is AgentTurn.ToolResult -> ToolResultTurnCard(turn, index)
        is AgentTurn.Final -> FinalTurnCard(turn, index)
        is AgentTurn.Error -> ErrorTurnCard(turn, index)
    }
}

@Composable
private fun UserTurnCard(turn: AgentTurn.User, index: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = WildBlue
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "You",
                style = MaterialTheme.typography.labelSmall,
                color = WildBlue,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                turn.content,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ThoughtTurnCard(turn: AgentTurn.AssistantThought, index: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Psychology,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Thinking",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (turn.thought.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                turn.thought,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        turn.toolCalls.forEach { call ->
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Build,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = WildAmber
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "${call.name}(...)",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = WildAmber
                )
            }
        }
    }
}

@Composable
private fun ToolResultTurnCard(turn: AgentTurn.ToolResult, index: Int) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .animateContentSize()
            .clickable { expanded = !expanded }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (turn.isError) Icons.Default.Error else Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (turn.isError) WildRed else WildGreen
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    turn.toolName,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                "${if (turn.isError) "error" else "✓"} ${turn.elapsedMs}ms",
                style = MaterialTheme.typography.labelSmall,
                color = if (turn.isError) WildRed else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }

        if (expanded) {
            Spacer(Modifier.height(4.dp))
            val prettyJson = try {
                val el = com.google.gson.JsonParser.parseString(turn.result.toString())
                prettyGson.toJson(el)
            } catch (_: Exception) {
                turn.result.toString()
            }
            Text(
                prettyJson,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                    .padding(8.dp)
            )
        }
    }
}

@Composable
private fun FinalTurnCard(turn: AgentTurn.Final, index: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(WildGreen.copy(alpha = 0.08f))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Assistant,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = WildGreen
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Answer",
                style = MaterialTheme.typography.labelSmall,
                color = WildGreen,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            turn.content,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ErrorTurnCard(turn: AgentTurn.Error, index: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(WildRed.copy(alpha = 0.08f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = WildRed
        )
        Spacer(Modifier.width(8.dp))
        Text(
            turn.message,
            style = MaterialTheme.typography.bodySmall,
            color = WildRed
        )
    }
}
