package com.wildguard.app.llm.provider

import com.wildguard.app.llm.agent.ProviderMessage
import com.wildguard.app.llm.agent.ToolAwareResponse
import com.wildguard.app.llm.agent.ToolSchema

/**
 * Wraps any LlmProvider and intercepts each generate() call.
 * Before sending the prompt to the LLM, it suspends and calls [onReview].
 * If [onReview] returns false the call is cancelled without hitting the network.
 *
 * For [generateWithTools], the delegate is called directly (no review per iteration).
 * The agent runner handles review once before the loop starts.
 */
class ReviewableProvider(
    private val delegate: LlmProvider,
    private val onReview: suspend (prompt: String) -> Boolean
) : LlmProvider {

    override val name: String get() = delegate.name
    override val displayName: String get() = delegate.displayName
    override fun isConfigured(): Boolean = delegate.isConfigured()

    override suspend fun generate(prompt: String, systemPrompt: String?): LlmResponse {
        val approved = onReview(prompt)
        if (!approved) {
            return LlmResponse(
                content = "",
                model = "",
                tokensUsed = null,
                error = "Cancelled by user"
            )
        }
        return delegate.generate(prompt, systemPrompt)
    }

    override suspend fun generateWithTools(
        messages: List<ProviderMessage>,
        tools: List<ToolSchema>,
        systemPrompt: String?
    ): ToolAwareResponse = delegate.generateWithTools(messages, tools, systemPrompt)
}
