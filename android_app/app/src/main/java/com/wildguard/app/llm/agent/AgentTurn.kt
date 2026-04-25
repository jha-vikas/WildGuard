package com.wildguard.app.llm.agent

import com.google.gson.JsonElement
import com.google.gson.JsonObject

sealed class AgentTurn {
    abstract val timestampMs: Long

    data class User(
        val content: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentTurn()

    data class AssistantThought(
        val thought: String,
        val toolCalls: List<AgentToolCall>,
        val tokensUsed: Int? = null,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentTurn()

    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val result: JsonElement,
        val elapsedMs: Long,
        val isError: Boolean = false,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentTurn()

    data class Final(
        val content: String,
        val tokensUsed: Int? = null,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentTurn()

    data class Error(
        val message: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentTurn()
}

data class AgentToolCall(
    val id: String,
    val name: String,
    val args: JsonObject
)

data class ToolAwareResponse(
    val text: String? = null,
    val toolCalls: List<AgentToolCall> = emptyList(),
    val tokensUsed: Int? = null,
    val error: String? = null
) {
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
    val hasFinalText: Boolean get() = text != null && toolCalls.isEmpty() && error == null
}

data class ProviderMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<AgentToolCall>? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolResult: JsonElement? = null
)

data class ToolSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject
)
