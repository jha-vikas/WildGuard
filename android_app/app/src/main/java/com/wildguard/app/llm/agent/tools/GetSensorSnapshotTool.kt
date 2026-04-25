package com.wildguard.app.llm.agent.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wildguard.app.llm.agent.Tool
import com.wildguard.app.llm.agent.ToolContext

class GetSensorSnapshotTool : Tool {
    override val name = "get_sensor_snapshot"
    override val description = "Returns full sensor state: location, barometric pressure (current + 3h/6h trend + trend classification), light level, compass heading. Pressure trend is useful for detecting rapid local changes that online weather snapshots can miss."
    override val paramsSchema: JsonObject
        get() = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject())
            add("required", com.google.gson.JsonArray())
        }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonElement {
        val s = ctx.sensor
        val pl = ctx.pressureLogger
        val result = JsonObject()

        val loc = s.location
        if (loc != null) {
            result.addProperty("lat", "%.4f".format(loc.latitude).toDouble())
            result.addProperty("lon", "%.4f".format(loc.longitude).toDouble())
            result.addProperty("altM", loc.altitudeGps ?: 0.0)
            result.addProperty("gpsAccuracy", loc.accuracy)
        }

        s.pressureHpa?.let { result.addProperty("pressureHpa", it) }
        pl.trend3h?.let { result.addProperty("pressureTrend3hHpa", it) }
        pl.trend6h?.let { result.addProperty("pressureTrend6hHpa", it) }
        result.addProperty("trendClassification", pl.trendClassification.name)

        s.lightLux?.let { result.addProperty("lightLux", it) }
        s.compassHeadingDeg?.let { result.addProperty("compassDeg", it) }

        result.addProperty("hasBarometer", s.hasBarometer)
        result.addProperty("hasCompass", s.hasCompass)
        result.addProperty("hasLightSensor", s.hasLightSensor)

        return result
    }
}
