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

    override suspend fun generateWithTools(
        messages: List<ProviderMessage>,
        tools: List<ToolSchema>,
        systemPrompt: String?
    ): ToolAwareResponse = withContext(Dispatchers.IO) {
        try {
            val anthropicMessages = mutableListOf<Map<String, Any?>>()
            for (msg in messages) {
                when (msg.role) {
                    "system" -> { /* handled via top-level 'system' field */ }
                    "user" -> anthropicMessages.add(mapOf("role" to "user", "content" to (msg.content ?: "")))
                    "assistant" -> {
                        val contentBlocks = mutableListOf<Map<String, Any?>>()
                        if (!msg.content.isNullOrBlank()) {
                            contentBlocks.add(mapOf("type" to "text", "text" to msg.content))
                        }
                        msg.toolCalls?.forEach { call ->
                            contentBlocks.add(mapOf(
                                "type" to "tool_use",
                                "id" to call.id,
                                "name" to call.name,
                                "input" to gson.fromJson(call.args.toString(), Map::class.java)
                            ))
                        }
                        if (contentBlocks.isNotEmpty()) {
                            anthropicMessages.add(mapOf("role" to "assistant", "content" to contentBlocks))
                        }
                    }
                    "tool" -> {
                        anthropicMessages.add(mapOf(
                            "role" to "user",
                            "content" to listOf(mapOf(
                                "type" to "tool_result",
                                "tool_use_id" to msg.toolCallId,
                                "content" to gson.toJson(msg.toolResult)
                            ))
                        ))
                    }
                }
            }

            val anthropicTools = tools.map { tool ->
                mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "input_schema" to gson.fromJson(tool.parameters.toString(), Map::class.java)
                )
            }

            val body = buildMap<String, Any> {
                put("model", model)
                put("max_tokens", 2000)
                if (systemPrompt != null) put("system", systemPrompt)
                put("messages", anthropicMessages)
                put("tools", anthropicTools)
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
                return@withContext ToolAwareResponse(
                    error = "HTTP ${response.code}: ${extractErrorMessage(responseBody)}"
                )
            }

            val json = JsonParser.parseString(responseBody).asJsonObject
            val contentArr = json.getAsJsonArray("content") ?: return@withContext ToolAwareResponse(text = "")

            val usage = json.getAsJsonObject("usage")
            val tokensUsed = if (usage != null) {
                (usage.get("input_tokens")?.asInt ?: 0) + (usage.get("output_tokens")?.asInt ?: 0)
            } else null

            val textParts = mutableListOf<String>()
            val toolCalls = mutableListOf<AgentToolCall>()

            for (i in 0 until contentArr.size()) {
                val block = contentArr[i].asJsonObject
                when (block.get("type")?.asString) {
                    "text" -> textParts.add(block.get("text")?.asString ?: "")
                    "tool_use" -> {
                        val input = block.getAsJsonObject("input") ?: JsonObject()
                        toolCalls.add(AgentToolCall(
                            id = block.get("id")?.asString ?: "anth_$i",
                            name = block.get("name")?.asString ?: "",
                            args = input
                        ))
                    }
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
