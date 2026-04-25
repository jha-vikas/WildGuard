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

class OpenAICompatibleProvider(
    override val name: String,
    override val displayName: String,
    private val endpoint: String,
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

    override fun isConfigured(): Boolean = apiKey.isNotBlank() && endpoint.isNotBlank()

    override suspend fun generate(prompt: String, systemPrompt: String?): LlmResponse =
        withContext(Dispatchers.IO) {
            try {
                val messages = buildList {
                    if (systemPrompt != null) {
                        add(mapOf("role" to "system", "content" to systemPrompt))
                    }
                    add(mapOf("role" to "user", "content" to prompt))
                }

                val body = mapOf(
                    "model" to model,
                    "messages" to messages,
                    "temperature" to 0.3,
                    "max_tokens" to 1000
                )

                val url = "${endpoint.trimEnd('/')}/chat/completions"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .apply {
                        if (apiKey.isNotBlank()) {
                            addHeader("Authorization", "Bearer $apiKey")
                        }
                    }
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
                val content = json.getAsJsonArray("choices")
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString ?: ""

                val tokensUsed = json.getAsJsonObject("usage")
                    ?.get("total_tokens")?.asInt

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
            val oaiMessages = mutableListOf<Map<String, Any?>>()
            for (msg in messages) {
                when (msg.role) {
                    "system" -> oaiMessages.add(mapOf("role" to "system", "content" to msg.content))
                    "user" -> oaiMessages.add(mapOf("role" to "user", "content" to msg.content))
                    "assistant" -> {
                        val m = mutableMapOf<String, Any?>("role" to "assistant")
                        if (msg.content != null) m["content"] = msg.content
                        msg.toolCalls?.let { calls ->
                            m["tool_calls"] = calls.map { call ->
                                mapOf(
                                    "id" to call.id,
                                    "type" to "function",
                                    "function" to mapOf(
                                        "name" to call.name,
                                        "arguments" to gson.toJson(call.args)
                                    )
                                )
                            }
                        }
                        oaiMessages.add(m)
                    }
                    "tool" -> {
                        oaiMessages.add(mapOf(
                            "role" to "tool",
                            "tool_call_id" to msg.toolCallId,
                            "content" to gson.toJson(msg.toolResult)
                        ))
                    }
                }
            }

            val oaiTools = tools.map { tool ->
                mapOf(
                    "type" to "function",
                    "function" to mapOf(
                        "name" to tool.name,
                        "description" to tool.description,
                        "parameters" to gson.fromJson(tool.parameters.toString(), Map::class.java)
                    )
                )
            }

            val body = buildMap<String, Any> {
                put("model", model)
                put("messages", oaiMessages)
                put("tools", oaiTools)
                put("tool_choice", "auto")
                put("temperature", 0.3)
                put("max_tokens", 2000)
            }

            val url = "${endpoint.trimEnd('/')}/chat/completions"
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .apply {
                    if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey")
                }
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
            val choice = json.getAsJsonArray("choices")?.get(0)?.asJsonObject
            val message = choice?.getAsJsonObject("message")
            val tokensUsed = json.getAsJsonObject("usage")?.get("total_tokens")?.asInt

            val content = message?.get("content")?.let { if (it.isJsonNull) null else it.asString }
            val rawToolCalls = message?.getAsJsonArray("tool_calls")

            if (rawToolCalls != null && rawToolCalls.size() > 0) {
                val toolCalls = (0 until rawToolCalls.size()).map { i ->
                    val tc = rawToolCalls[i].asJsonObject
                    val fn = tc.getAsJsonObject("function")
                    val argsStr = fn.get("arguments")?.asString ?: "{}"
                    val argsObj = try {
                        JsonParser.parseString(argsStr).asJsonObject
                    } catch (_: Exception) {
                        JsonObject()
                    }
                    AgentToolCall(
                        id = tc.get("id")?.asString ?: "oai_$i",
                        name = fn.get("name")?.asString ?: "",
                        args = argsObj
                    )
                }
                ToolAwareResponse(text = content, toolCalls = toolCalls, tokensUsed = tokensUsed)
            } else {
                ToolAwareResponse(text = content ?: "", tokensUsed = tokensUsed)
            }
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
