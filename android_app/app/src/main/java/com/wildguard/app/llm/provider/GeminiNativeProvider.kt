package com.wildguard.app.llm.provider

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wildguard.app.llm.agent.AgentToolCall
import com.wildguard.app.llm.agent.ProviderMessage
import com.wildguard.app.llm.agent.ToolAwareResponse
import com.wildguard.app.llm.agent.ToolSchema
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

    override suspend fun generateWithTools(
        messages: List<ProviderMessage>,
        tools: List<ToolSchema>,
        systemPrompt: String?
    ): ToolAwareResponse = withContext(Dispatchers.IO) {
        try {
            val contents = mutableListOf<Map<String, Any>>()
            for (msg in messages) {
                when (msg.role) {
                    "system" -> { /* handled via systemInstruction */ }
                    "user" -> contents.add(mapOf(
                        "role" to "user",
                        "parts" to listOf(mapOf("text" to (msg.content ?: "")))
                    ))
                    "assistant" -> {
                        val parts = mutableListOf<Map<String, Any>>()
                        if (!msg.content.isNullOrBlank()) {
                            parts.add(mapOf("text" to msg.content))
                        }
                        msg.toolCalls?.forEach { call ->
                            parts.add(mapOf(
                                "functionCall" to mapOf(
                                    "name" to call.name,
                                    "args" to gson.fromJson(call.args.toString(), Map::class.java)
                                )
                            ))
                        }
                        if (parts.isNotEmpty()) {
                            contents.add(mapOf("role" to "model", "parts" to parts))
                        }
                    }
                    "tool" -> {
                        val resultMap = try {
                            gson.fromJson(msg.toolResult.toString(), Map::class.java)
                        } catch (_: Exception) {
                            mapOf("result" to msg.toolResult.toString())
                        }
                        contents.add(mapOf(
                            "role" to "function",
                            "parts" to listOf(mapOf(
                                "functionResponse" to mapOf(
                                    "name" to (msg.toolName ?: ""),
                                    "response" to resultMap
                                )
                            ))
                        ))
                    }
                }
            }

            val functionDeclarations = tools.map { tool ->
                val paramsMap = gson.fromJson(tool.parameters.toString(), Map::class.java)
                mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to paramsMap
                )
            }

            val body = buildMap<String, Any> {
                put("contents", contents)
                put("tools", listOf(mapOf("functionDeclarations" to functionDeclarations)))
                if (systemPrompt != null) {
                    put("systemInstruction", mapOf(
                        "parts" to listOf(mapOf("text" to systemPrompt))
                    ))
                }
                put("generationConfig", mapOf(
                    "temperature" to 0.3,
                    "maxOutputTokens" to 2000
                ))
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(gson.toJson(body).toRequestBody(jsonMedia))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext ToolAwareResponse(
                    error = "HTTP ${response.code}: ${extractErrorMessage(responseBody)}"
                )
            }

            val json = JsonParser.parseString(responseBody).asJsonObject
            val parts = json.getAsJsonArray("candidates")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("content")
                ?.getAsJsonArray("parts")

            val usageMeta = json.getAsJsonObject("usageMetadata")
            val tokensUsed = usageMeta?.get("totalTokenCount")?.asInt

            if (parts == null) {
                return@withContext ToolAwareResponse(text = "", tokensUsed = tokensUsed)
            }

            val textParts = mutableListOf<String>()
            val toolCalls = mutableListOf<AgentToolCall>()
            var callCounter = 0

            for (i in 0 until parts.size()) {
                val part = parts[i].asJsonObject
                if (part.has("functionCall")) {
                    val fc = part.getAsJsonObject("functionCall")
                    val fcName = fc.get("name")?.asString ?: continue
                    val fcArgs = fc.getAsJsonObject("args") ?: JsonObject()
                    callCounter++
                    toolCalls.add(AgentToolCall(
                        id = "gemini_$callCounter",
                        name = fcName,
                        args = fcArgs
                    ))
                } else if (part.has("text")) {
                    textParts.add(part.get("text").asString)
                }
            }

            ToolAwareResponse(
                text = textParts.joinToString("\n").ifBlank { null },
                toolCalls = toolCalls,
                tokensUsed = tokensUsed
            )
        } catch (e: IOException) {
            ToolAwareResponse(error = "Network error: ${e.message}")
        } catch (e: Exception) {
            ToolAwareResponse(error = "Error: ${e.message}")
        }
    }

    private fun extractErrorMessage(body: String): String = try {
        val json = JsonParser.parseString(body).asJsonObject
        json.getAsJsonObject("error")?.get("message")?.asString ?: body.take(200)
    } catch (_: Exception) {
        body.take(200)
    }
}
