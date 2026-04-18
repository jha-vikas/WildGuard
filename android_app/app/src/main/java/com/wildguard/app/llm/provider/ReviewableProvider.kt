package com.wildguard.app.llm.provider

/**
 * Wraps any LlmProvider and intercepts each generate() call.
 * Before sending the prompt to the LLM, it suspends and calls [onReview].
 * If [onReview] returns false the call is cancelled without hitting the network.
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
}
