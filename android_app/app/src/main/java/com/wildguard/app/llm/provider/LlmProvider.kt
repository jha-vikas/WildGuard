package com.wildguard.app.llm.provider

interface LlmProvider {
    val name: String
    val displayName: String
    suspend fun generate(prompt: String, systemPrompt: String? = null): LlmResponse
    fun isConfigured(): Boolean
}

data class LlmResponse(
    val content: String,
    val model: String,
    val tokensUsed: Int?,
    val error: String? = null
)

enum class ApiFormat { OPENAI_COMPATIBLE, ANTHROPIC, GEMINI_NATIVE }
