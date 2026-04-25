package com.wildguard.app.llm.agent

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wildguard.app.core.model.SensorState
import com.wildguard.app.modules.weather.PressureLogger

interface Tool {
    val name: String
    val description: String
    val paramsSchema: JsonObject
    suspend fun execute(args: JsonObject, ctx: ToolContext): JsonElement
}

data class ToolContext(
    val sensor: SensorState,
    val appContext: Context,
    val pressureLogger: PressureLogger
)
