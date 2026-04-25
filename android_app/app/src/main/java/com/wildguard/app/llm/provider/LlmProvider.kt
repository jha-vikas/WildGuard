package com.wildguard.app.llm.provider

interface LlmProvider {
    val name: String
    val displayName: String
    suspend fun generate(prompt: String, systemPrompt: String? = null): LlmResponse
    fun isConfigured(): Boolean

    suspend fun generateWithTools(
        messages: List<com.wildguard.app.llm.agent.ProviderMessage>,
        tools: List<com.wildguard.app.llm.agent.ToolSchema>,
        systemPrompt: String?
    ): com.wildguard.app.llm.agent.ToolAwareResponse =
        com.wildguard.app.llm.agent.ToolAwareResponse(error = "Function calling not supported by this provider")
}

data class LlmResponse(
    val content: String,
    val model: String,
    val tokensUsed: Int?,
    val error: String? = null
)

enum class ApiFormat { OPENAI_COMPATIBLE, ANTHROPIC, GEMINI_NATIVE }
