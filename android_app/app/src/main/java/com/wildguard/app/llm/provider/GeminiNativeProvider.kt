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

class GeminiNativeProvider(
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
                val fullPrompt = if (systemPrompt != null) "$systemPrompt\n\n$prompt" else prompt

                val body = mapOf(
                    "contents" to listOf(
                        mapOf("parts" to listOf(mapOf("text" to fullPrompt)))
                    ),
                    "generationConfig" to mapOf(
                        "temperature" to 0.3,
                        "maxOutputTokens" to 1000
                    )
                )

                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
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
                val content = json.getAsJsonArray("candidates")
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("content")
                    ?.getAsJsonArray("parts")
                    ?.get(0)?.asJsonObject
                    ?.get("text")?.asString ?: ""

                val usageMeta = json.getAsJsonObject("usageMetadata")
                val tokensUsed = usageMeta?.get("totalTokenCount")?.asInt

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
