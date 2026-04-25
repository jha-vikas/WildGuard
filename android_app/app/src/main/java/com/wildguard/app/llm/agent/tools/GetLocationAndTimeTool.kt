package com.wildguard.app.llm.agent.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wildguard.app.llm.agent.Tool
import com.wildguard.app.llm.agent.ToolContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class GetLocationAndTimeTool : Tool {
    override val name = "get_location_and_time"
    override val description = "Returns the user's current GPS location, altitude, local date, timezone offset, and UTC timestamp."
    override val paramsSchema: JsonObject
        get() = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject())
            add("required", com.google.gson.JsonArray())
        }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonElement {
        val loc = ctx.sensor.location
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val offsetMs = tz.getOffset(now)
        val offsetH = offsetMs / 3_600_000
        val offsetSign = if (offsetH >= 0) "+" else ""

        val result = JsonObject()
        if (loc != null) {
            result.addProperty("lat", "%.4f".format(loc.latitude).toDouble())
            result.addProperty("lon", "%.4f".format(loc.longitude).toDouble())
            result.addProperty("altM", loc.altitudeGps ?: 0.0)
        } else {
            result.addProperty("error", "GPS not available")
        }
        result.addProperty("localDate", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now)))
        result.addProperty("tz", "$offsetSign$offsetH:00")
        result.addProperty("utcMs", now)
        return result
    }
}
