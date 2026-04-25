package com.wildguard.app.llm.agent.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wildguard.app.llm.agent.Tool
import com.wildguard.app.llm.agent.ToolContext
import com.wildguard.app.modules.uv.SkinType
import com.wildguard.app.modules.uv.SunPositionCalculator
import com.wildguard.app.modules.uv.UVIndexCalculator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ComputeUvDoseTool : Tool {
    override val name = "compute_uv_dose"
    override val description = "Calculates UV exposure for a time window at a given location. Returns peak UV, minutes to erythema (sunburn) by skin type, safe time with SPF, and a recommendation."
    override val paramsSchema: JsonObject
        get() {
            val props = JsonObject()
            props.add("date", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Date yyyy-MM-dd") })
            props.add("startHour", JsonObject().apply { addProperty("type", "number"); addProperty("description", "Local start hour (e.g. 10.0)") })
            props.add("endHour", JsonObject().apply { addProperty("type", "number"); addProperty("description", "Local end hour (e.g. 14.0)") })
            props.add("lat", JsonObject().apply { addProperty("type", "number") })
            props.add("lon", JsonObject().apply { addProperty("type", "number") })
            props.add("altM", JsonObject().apply { addProperty("type", "number"); addProperty("description", "Altitude in meters, default 0") })
            props.add("skinType", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "Fitzpatrick skin type 1-6, default 2") })
            props.add("spf", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "SPF value, default 30") })
            val required = com.google.gson.JsonArray()
            required.add("date"); required.add("startHour"); required.add("endHour"); required.add("lat"); required.add("lon")
            return JsonObject().apply { addProperty("type", "object"); add("properties", props); add("required", required) }
        }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonElement {
        val dateStr = args.get("date")?.asString ?: return errorJson("missing 'date'")
        val startH = args.get("startHour")?.asDouble ?: return errorJson("missing 'startHour'")
        val endH = args.get("endHour")?.asDouble ?: return errorJson("missing 'endHour'")
        val lat = args.get("lat")?.asDouble ?: return errorJson("missing 'lat'")
        val lon = args.get("lon")?.asDouble ?: return errorJson("missing 'lon'")
        val altM = args.get("altM")?.asDouble ?: 0.0
        val skinIdx = (args.get("skinType")?.asInt ?: 2).coerceIn(1, 6)
        val spf = (args.get("spf")?.asInt ?: 30).coerceIn(1, 100)

        val skinType = SkinType.entries[skinIdx - 1]

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val parsedDate = try { sdf.parse(dateStr) } catch (_: Exception) { null }
            ?: return errorJson("Invalid date format")

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = parsedDate }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val tzOffsetH = TimeZone.getDefault().getOffset(parsedDate.time) / 3_600_000.0

        var peakUv = 0.0
        var h = startH
        while (h <= endH) {
            val utcH = h - tzOffsetH
            val sun = SunPositionCalculator.compute(lat, lon, year, month, day, utcH)
            val uv = UVIndexCalculator.compute(
                sunPosition = sun, altitudeMeters = altM, lightLux = null,
                skinType = skinType, spf = spf, lat = lat, lon = lon,
                timeMillis = parsedDate.time + (h * 3_600_000).toLong()
            )
            if (uv.uvIndex > peakUv) peakUv = uv.uvIndex
            h += 0.25
        }

        val midH = (startH + endH) / 2.0
        val midUtcH = midH - tzOffsetH
        val midSun = SunPositionCalculator.compute(lat, lon, year, month, day, midUtcH)
        val midUv = UVIndexCalculator.compute(
            sunPosition = midSun, altitudeMeters = altM, lightLux = null,
            skinType = skinType, spf = spf, lat = lat, lon = lon,
            timeMillis = parsedDate.time + (midH * 3_600_000).toLong()
        )

        val result = JsonObject()
        result.addProperty("peakUv", "%.1f".format(peakUv).toDouble())
        result.addProperty("minutesToErythema", "%.0f".format(midUv.safeExposureMin).toDouble())
        result.addProperty("minutesWithSpf", "%.0f".format(midUv.safeWithSpfMin).toDouble())
        result.addProperty("skinType", skinIdx)
        result.addProperty("spf", spf)

        val rec = when {
            peakUv >= 11 -> "Extreme UV. Avoid outdoor exposure; full coverage required."
            peakUv >= 8 -> "Very high UV. Limit exposure. Sunscreen + hat + shade essential."
            peakUv >= 6 -> "High UV. Seek shade during peak. Reapply sunscreen every 2h."
            peakUv >= 3 -> "Moderate UV. Sunscreen recommended for extended exposure."
            else -> "Low UV. No special precautions needed."
        }
        result.addProperty("recommendation", rec)
        return result
    }

    private fun errorJson(msg: String) = JsonObject().apply { addProperty("error", msg) }
}
