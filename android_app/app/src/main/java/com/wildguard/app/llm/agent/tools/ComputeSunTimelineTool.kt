package com.wildguard.app.llm.agent.tools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wildguard.app.llm.agent.Tool
import com.wildguard.app.llm.agent.ToolContext
import com.wildguard.app.modules.uv.SunPositionCalculator
import com.wildguard.app.modules.uv.UVIndexCalculator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ComputeSunTimelineTool : Tool {
    override val name = "compute_sun_timeline"
    override val description = "Computes sun position (altitude, azimuth) and UV index every 15 minutes for a given date at a given location. Also returns sunrise, sunset, and solar noon times."
    override val paramsSchema: JsonObject
        get() {
            val props = JsonObject()
            props.add("date", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Date in yyyy-MM-dd format") })
            props.add("lat", JsonObject().apply { addProperty("type", "number") })
            props.add("lon", JsonObject().apply { addProperty("type", "number") })
            props.add("altM", JsonObject().apply { addProperty("type", "number"); addProperty("description", "Altitude in meters, default 0") })
            val required = JsonArray(); required.add("date"); required.add("lat"); required.add("lon")
            return JsonObject().apply { addProperty("type", "object"); add("properties", props); add("required", required) }
        }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonElement {
        val dateStr = args.get("date")?.asString ?: return errorJson("missing 'date'")
        val lat = args.get("lat")?.asDouble ?: return errorJson("missing 'lat'")
        val lon = args.get("lon")?.asDouble ?: return errorJson("missing 'lon'")
        val altM = args.get("altM")?.asDouble ?: 0.0

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val parsedDate = try { sdf.parse(dateStr) } catch (_: Exception) { null }
            ?: return errorJson("Invalid date format")

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = parsedDate }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val tzOffsetH = TimeZone.getDefault().getOffset(parsedDate.time) / 3_600_000.0

        val noonSun = SunPositionCalculator.compute(lat, lon, year, month, day, 12.0 - tzOffsetH)

        val points = JsonArray()
        for (i in 0 until 96) {
            val localHour = i * 0.25
            val utcHour = localHour - tzOffsetH
            val sun = SunPositionCalculator.compute(lat, lon, year, month, day, utcHour)
            val uv = UVIndexCalculator.compute(
                sunPosition = sun,
                altitudeMeters = altM,
                lightLux = null,
                lat = lat, lon = lon,
                timeMillis = parsedDate.time + (localHour * 3_600_000).toLong()
            )
            val pt = JsonObject()
            pt.addProperty("localHour", "%.2f".format(localHour).toDouble())
            pt.addProperty("sunAltDeg", "%.1f".format(sun.altitudeDeg).toDouble())
            pt.addProperty("sunAzDeg", "%.1f".format(sun.azimuthDeg).toDouble())
            pt.addProperty("uv", "%.1f".format(uv.uvIndex).toDouble())
            points.add(pt)
        }

        fun fmtH(utcH: Double): String {
            val local = utcH + tzOffsetH
            val h = local.toInt().coerceIn(0, 23)
            val m = ((local - local.toInt()) * 60).toInt().coerceIn(0, 59)
            return "%02d:%02d".format(h, m)
        }

        val result = JsonObject()
        result.addProperty("sunrise", fmtH(noonSun.sunriseUtc))
        result.addProperty("sunset", fmtH(noonSun.sunsetUtc))
        result.addProperty("solarNoon", fmtH(noonSun.solarNoonUtc))
        result.add("timeline", points)
        return result
    }

    private fun errorJson(msg: String) = JsonObject().apply { addProperty("error", msg) }
}
