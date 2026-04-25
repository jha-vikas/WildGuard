package com.wildguard.app.llm.agent.tools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wildguard.app.llm.agent.Tool
import com.wildguard.app.llm.agent.ToolContext
import com.wildguard.app.modules.celestial.StarCatalog

class ComputeStarsAboveTool : Tool {
    override val name = "compute_stars_above"
    override val description = "Returns bright stars currently above the horizon, sorted by brightness. Includes name, constellation, magnitude, altitude, azimuth."
    override val paramsSchema: JsonObject
        get() {
            val props = JsonObject()
            props.add("lat", JsonObject().apply { addProperty("type", "number") })
            props.add("lon", JsonObject().apply { addProperty("type", "number") })
            props.add("utcMs", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "UTC epoch ms, default now") })
            props.add("minAltDeg", JsonObject().apply { addProperty("type", "number"); addProperty("description", "Minimum altitude above horizon in degrees, default 10") })
            props.add("maxMagnitude", JsonObject().apply { addProperty("type", "number"); addProperty("description", "Maximum magnitude (lower = brighter), default 2.5") })
            val required = com.google.gson.JsonArray(); required.add("lat"); required.add("lon")
            return JsonObject().apply { addProperty("type", "object"); add("properties", props); add("required", required) }
        }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonElement {
        val lat = args.get("lat")?.asDouble ?: return errorJson("missing 'lat'")
        val lon = args.get("lon")?.asDouble ?: return errorJson("missing 'lon'")
        val utcMs = args.get("utcMs")?.asLong ?: System.currentTimeMillis()
        val minAlt = args.get("minAltDeg")?.asDouble ?: 10.0
        val maxMag = args.get("maxMagnitude")?.asDouble ?: 2.5

        val stars = StarCatalog.getVisibleAbove(lat, lon, utcMs, minAlt)
            .filter { it.magnitude <= maxMag }

        val arr = JsonArray()
        for (s in stars) {
            val obj = JsonObject()
            obj.addProperty("name", s.name)
            obj.addProperty("constellation", s.constellation)
            obj.addProperty("magnitude", s.magnitude)
            obj.addProperty("altitudeDeg", "%.1f".format(s.altitudeDeg).toDouble())
            obj.addProperty("azimuthDeg", "%.1f".format(s.azimuthDeg).toDouble())
            arr.add(obj)
        }

        val result = JsonObject()
        result.addProperty("count", arr.size())
        result.add("stars", arr)
        return result
    }

    private fun errorJson(msg: String) = JsonObject().apply { addProperty("error", msg) }
}
