package com.wildguard.app.llm.provider

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class AnthropicProvider(
    override val name: String,
    override val displayName: String,
    private val apiKey: String,
    private val model: String
) : LlmProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override fun isConfigured(): Boolean = apiKey.isNotBlank()

    override suspend fun generate(prompt: String, systemPrompt: String?): LlmResponse =
        withContext(Dispatchers.IO) {
            try {
                val body = buildMap<String, Any> {
                    put("model", model)
                    put("max_tokens", 1000)
                    if (systemPrompt != null) put("system", systemPrompt)
                    put("messages", listOf(mapOf("role" to "user", "content" to prompt)))
                }

                val request = Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("content-type", "application/json")
                    .post(gson.toJson(body).toRequestBody(jsonMedia))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext LlmResponse(
                        content = "",
                        model = model,
                        tokensUsed = null,
                        error = "HTTP ${response.code}: ${extractErrorMessage(responseBody)}"
                    )
                }

                val json = JsonParser.parseString(responseBody).asJsonObject
                val content = json.getAsJsonArray("content")
                    ?.get(0)?.asJsonObject
                    ?.get("text")?.asString ?: ""

                val usage = json.getAsJsonObject("usage")
                val tokensUsed = if (usage != null) {
                    val input = usage.get("input_tokens")?.asInt ?: 0
                    val output = usage.get("output_tokens")?.asInt ?: 0
                    input + output
                } else null

                LlmResponse(content = content, model = model, tokensUsed = tokensUsed)
            } catch (e: IOException) {
                LlmResponse(content = "", model = model, tokensUsed = null, error = "Network error: ${e.message}")
            } catch (e: Exception) {
                LlmResponse(content = "", model = model, tokensUsed = null, error = "Error: ${e.message}")
            }
        }

    private fun extractErrorMessage(body: String): String = try {
        val json = JsonParser.parseString(body).asJsonObject
        json.getAsJsonObject("error")?.get("message")?.asString ?: body.take(200)
    } catch (_: Exception) {
        body.take(200)
    }
}
