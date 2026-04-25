package com.wildguard.app.llm.agent.tools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wildguard.app.core.data.AssetRepository
import com.wildguard.app.llm.agent.Tool
import com.wildguard.app.llm.agent.ToolContext
import com.wildguard.app.modules.tide.TideCalculator
import com.wildguard.app.modules.tide.TidalStationRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ComputeTideScheduleTool : Tool {
    override val name = "compute_tide_schedule"
    override val description = "Returns high/low tide times and heights for the nearest tidal station within 300 km. Returns station: null if user is inland."
    override val paramsSchema: JsonObject
        get() {
            val props = JsonObject()
            props.add("lat", JsonObject().apply { addProperty("type", "number") })
            props.add("lon", JsonObject().apply { addProperty("type", "number") })
            props.add("startMs", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "UTC epoch ms, default now") })
            props.add("hours", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 1); addProperty("maximum", 168); addProperty("description", "Hours to cover, default 48") })
            val required = JsonArray(); required.add("lat"); required.add("lon")
            return JsonObject().apply { addProperty("type", "object"); add("properties", props); add("required", required) }
        }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonElement {
        val lat = args.get("lat")?.asDouble ?: return errorJson("missing 'lat'")
        val lon = args.get("lon")?.asDouble ?: return errorJson("missing 'lon'")
        val startMs = args.get("startMs")?.asLong ?: System.currentTimeMillis()
        val hours = (args.get("hours")?.asInt ?: 48).coerceIn(1, 168)

        val repo = TidalStationRepository(AssetRepository(ctx.appContext))
        val station = repo.findNearby(lat, lon, 300.0)
            .minByOrNull { TidalStationRepository.haversineKm(lat, lon, it.lat, it.lon) }

        if (station == null) {
            return JsonObject().apply { add("station", com.google.gson.JsonNull.INSTANCE) }
        }

        val extremes = TideCalculator.findHighLowTides(station, startMs, hours)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

        val result = JsonObject()
        result.addProperty("station", station.name)
        result.addProperty("distanceKm", "%.1f".format(
            TidalStationRepository.haversineKm(lat, lon, station.lat, station.lon)
        ).toDouble())

        val tides = JsonArray()
        extremes.forEach { ext ->
            val t = JsonObject()
            t.addProperty("time", sdf.format(Date(ext.timeMillis)))
            t.addProperty("heightM", "%.2f".format(ext.heightM).toDouble())
            t.addProperty("type", if (ext.isHigh) "high" else "low")
            tides.add(t)
        }
        result.add("tides", tides)

        val currentHeight = TideCalculator.computeTideHeight(station, startMs)
        val isRising = TideCalculator.isRising(station, startMs)
        result.addProperty("currentHeightM", "%.2f".format(currentHeight).toDouble())
        result.addProperty("currentDirection", if (isRising) "rising" else "falling")

        return result
    }

    private fun errorJson(msg: String) = JsonObject().apply { addProperty("error", msg) }
}
