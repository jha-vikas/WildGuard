package com.wildguard.app.llm.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ConversationState {

    private val _turns = MutableStateFlow<List<AgentTurn>>(emptyList())
    val turns: StateFlow<List<AgentTurn>> = _turns

    fun append(turn: AgentTurn) {
        _turns.value = _turns.value + turn
    }

    fun clear() {
        _turns.value = emptyList()
    }

    val turnCount: Int get() = _turns.value.size

    val iterationCount: Int
        get() = _turns.value.count { it is AgentTurn.AssistantThought || it is AgentTurn.Final }

    fun toProviderMessages(systemPrompt: String? = null): List<ProviderMessage> {
        val messages = mutableListOf<ProviderMessage>()

        if (systemPrompt != null) {
            messages.add(ProviderMessage(role = "system", content = systemPrompt))
        }

        for (turn in _turns.value) {
            when (turn) {
                is AgentTurn.User -> {
                    messages.add(ProviderMessage(role = "user", content = turn.content))
                }
                is AgentTurn.AssistantThought -> {
                    messages.add(
                        ProviderMessage(
                            role = "assistant",
                            content = turn.thought.ifBlank { null },
                            toolCalls = turn.toolCalls
                        )
                    )
                }
                is AgentTurn.ToolResult -> {
                    messages.add(
                        ProviderMessage(
                            role = "tool",
                            toolCallId = turn.toolCallId,
                            toolName = turn.toolName,
                            toolResult = turn.result
                        )
                    )
                }
                is AgentTurn.Final -> {
                    messages.add(ProviderMessage(role = "assistant", content = turn.content))
                }
                is AgentTurn.Error -> {
                    // Errors are not sent back to the provider
                }
            }
        }
        return messages
    }
}
