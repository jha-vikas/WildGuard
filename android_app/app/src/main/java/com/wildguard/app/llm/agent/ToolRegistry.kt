package com.wildguard.app.llm.agent

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.withTimeoutOrNull

class ToolRegistry {

    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun registerAll(vararg toolList: Tool) {
        toolList.forEach { register(it) }
    }

    fun get(name: String): Tool? = tools[name]

    fun allTools(): List<Tool> = tools.values.toList()

    fun schemasFor(toolNames: List<String>): List<ToolSchema> =
        toolNames.mapNotNull { name ->
            tools[name]?.let { tool ->
                ToolSchema(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.paramsSchema
                )
            }
        }

    fun allSchemas(): List<ToolSchema> = tools.values.map { tool ->
        ToolSchema(
            name = tool.name,
            description = tool.description,
            parameters = tool.paramsSchema
        )
    }

    suspend fun dispatch(
        name: String,
        args: JsonObject,
        ctx: ToolContext,
        timeoutMs: Long = 10_000L
    ): DispatchResult {
        val tool = tools[name]
            ?: return DispatchResult(
                result = errorJson("Unknown tool: $name"),
                isError = true,
                elapsedMs = 0
            )

        val t0 = System.currentTimeMillis()
        val result = withTimeoutOrNull(timeoutMs) {
            try {
                tool.execute(args, ctx)
            } catch (e: Exception) {
                errorJson("Tool error: ${e.message?.take(200) ?: e::class.java.simpleName}")
            }
        }
        val elapsed = System.currentTimeMillis() - t0

        return if (result != null) {
            DispatchResult(result = result, isError = false, elapsedMs = elapsed)
        } else {
            DispatchResult(
                result = errorJson("Tool '$name' timed out after ${timeoutMs}ms"),
                isError = true,
                elapsedMs = elapsed
            )
        }
    }

    private fun errorJson(msg: String): JsonObject {
        val obj = JsonObject()
        obj.add("error", JsonPrimitive(msg))
        return obj
    }

    data class DispatchResult(
        val result: com.google.gson.JsonElement,
        val isError: Boolean,
        val elapsedMs: Long
    )
}
