package com.wildguard.app.llm.agent.tools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wildguard.app.llm.agent.Tool
import com.wildguard.app.llm.agent.ToolContext
import com.wildguard.app.modules.celestial.PlanetCalculator
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ComputePlanetVisibilityTool : Tool {
    override val name = "compute_planet_visibility"
    override val description = "Returns altitude, azimuth, magnitude, visibility, rise/set times for visible planets (Mercury, Venus, Mars, Jupiter, Saturn) at a given location and time."
    override val paramsSchema: JsonObject
        get() {
            val props = JsonObject()
            props.add("lat", JsonObject().apply { addProperty("type", "number") })
            props.add("lon", JsonObject().apply { addProperty("type", "number") })
            props.add("utcMs", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "UTC epoch ms, default now") })
            props.add("planetName", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Optional: filter to a specific planet name") })
            val required = com.google.gson.JsonArray(); required.add("lat"); required.add("lon")
            return JsonObject().apply { addProperty("type", "object"); add("properties", props); add("required", required) }
        }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonElement {
        val lat = args.get("lat")?.asDouble ?: return errorJson("missing 'lat'")
        val lon = args.get("lon")?.asDouble ?: return errorJson("missing 'lon'")
        val utcMs = args.get("utcMs")?.asLong ?: System.currentTimeMillis()
        val filter = args.get("planetName")?.asString

        val planets = PlanetCalculator.computeAll(lat, lon, utcMs)
        val tzOffsetH = TimeZone.getDefault().getOffset(utcMs) / 3_600_000.0

        fun fmtH(utcH: Double?): String? {
            utcH ?: return null
            val local = utcH + tzOffsetH
            val h = local.toInt().coerceIn(0, 23)
            val m = ((local - local.toInt()) * 60).toInt().coerceIn(0, 59)
            return "%02d:%02d".format(h, m)
        }

        val arr = JsonArray()
        for (p in planets) {
            if (filter != null && !p.name.equals(filter, ignoreCase = true)) continue
            val obj = JsonObject()
            obj.addProperty("name", p.name)
            obj.addProperty("altitudeDeg", "%.1f".format(p.altitudeDeg).toDouble())
            obj.addProperty("azimuthDeg", "%.1f".format(p.azimuthDeg).toDouble())
            obj.addProperty("magnitude", "%.1f".format(p.magnitude).toDouble())
            obj.addProperty("isVisible", p.isVisible)
            fmtH(p.riseUtcHours)?.let { obj.addProperty("rise", it) }
            fmtH(p.setUtcHours)?.let { obj.addProperty("set", it) }
            arr.add(obj)
        }

        return if (arr.size() == 0 && filter != null) {
            JsonObject().apply { addProperty("error", "Planet '$filter' not found") }
        } else {
            arr
        }
    }

    private fun errorJson(msg: String) = JsonObject().apply { addProperty("error", msg) }
}
