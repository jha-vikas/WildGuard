package com.wildguard.app.llm.provider

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class ProviderConfig(
    val id: String,
    val type: ApiFormat,
    val displayName: String,
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val isActive: Boolean = false
)

object ProviderTemplates {
    val GEMINI_FLASH = ProviderConfig(
        id = "gemini-flash",
        type = ApiFormat.GEMINI_NATIVE,
        displayName = "Gemini Flash",
        endpoint = "https://generativelanguage.googleapis.com/v1beta",
        apiKey = "",
        model = "gemini-2.0-flash"
    )

    val OPENAI = ProviderConfig(
        id = "openai",
        type = ApiFormat.OPENAI_COMPATIBLE,
        displayName = "OpenAI",
        endpoint = "https://api.openai.com/v1",
        apiKey = "",
        model = "gpt-4o-mini"
    )

    val ANTHROPIC = ProviderConfig(
        id = "anthropic",
        type = ApiFormat.ANTHROPIC,
        displayName = "Anthropic",
        endpoint = "https://api.anthropic.com",
        apiKey = "",
        model = "claude-3-haiku-20240307"
    )

    val GROQ = ProviderConfig(
        id = "groq",
        type = ApiFormat.OPENAI_COMPATIBLE,
        displayName = "Groq",
        endpoint = "https://api.groq.com/openai/v1",
        apiKey = "",
        model = "llama-3.1-8b-instant"
    )

    val OLLAMA = ProviderConfig(
        id = "ollama-local",
        type = ApiFormat.OPENAI_COMPATIBLE,
        displayName = "Ollama (Local)",
        endpoint = "http://localhost:11434/v1",
        apiKey = "",
        model = "llama3.1"
    )

    val ALL = listOf(GEMINI_FLASH, OPENAI, ANTHROPIC, GROQ, OLLAMA)
}

class ProviderRegistry(context: Context) {

    private val gson = Gson()
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "wildguard_llm_providers",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private var configs = mutableListOf<ProviderConfig>()

    init {
        loadFromPreferences()
    }

    fun loadFromPreferences() {
        val json = prefs.getString("providers", null)
        configs = if (json != null) {
            val type = object : TypeToken<List<ProviderConfig>>() {}.type
            gson.fromJson<List<ProviderConfig>>(json, type).toMutableList()
        } else {
            mutableListOf()
        }
    }

    fun getAll(): List<ProviderConfig> = configs.toList()

    fun saveProvider(config: ProviderConfig) {
        val idx = configs.indexOfFirst { it.id == config.id }
        if (idx >= 0) {
            configs[idx] = config
        } else {
            configs.add(config)
        }
        persist()
    }

    fun removeProvider(id: String) {
        configs.removeAll { it.id == id }
        persist()
    }

    fun setActive(id: String) {
        configs = configs.map { it.copy(isActive = it.id == id) }.toMutableList()
        persist()
    }

    fun getActiveProvider(): LlmProvider? {
        val active = configs.firstOrNull { it.isActive } ?: return null
        return createProvider(active)
    }

    fun getActiveConfig(): ProviderConfig? = configs.firstOrNull { it.isActive }

    fun createProvider(config: ProviderConfig): LlmProvider = when (config.type) {
        ApiFormat.OPENAI_COMPATIBLE -> OpenAICompatibleProvider(
            name = config.id,
            displayName = config.displayName,
            endpoint = config.endpoint,
            apiKey = config.apiKey,
            model = config.model
        )
        ApiFormat.ANTHROPIC -> AnthropicProvider(
            name = config.id,
            displayName = config.displayName,
            apiKey = config.apiKey,
            model = config.model
        )
        ApiFormat.GEMINI_NATIVE -> GeminiNativeProvider(
            name = config.id,
            displayName = config.displayName,
            apiKey = config.apiKey,
            model = config.model
        )
    }

    suspend fun testProvider(config: ProviderConfig): Result<String> = try {
        val provider = createProvider(config)
        val response = provider.generate("Respond with exactly: OK")
        if (response.error != null) {
            Result.failure(Exception(response.error))
        } else if (response.content.isNotBlank()) {
            Result.success(response.content.trim())
        } else {
            Result.failure(Exception("Empty response from provider"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun persist() {
        prefs.edit().putString("providers", gson.toJson(configs)).apply()
    }
}
