package com.wildguard.app.llm.agent.tools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wildguard.app.llm.agent.Tool
import com.wildguard.app.llm.agent.ToolContext
import com.wildguard.app.modules.celestial.MoonCalculator
import com.wildguard.app.modules.uv.SunPositionCalculator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ComputeMoonAndTwilightTool : Tool {
    override val name = "compute_moon_and_twilight"
    override val description = "Returns moon phase, illumination, moonrise/set, and civil/nautical/astronomical twilight start/end for 1-7 days."
    override val paramsSchema: JsonObject
        get() {
            val props = JsonObject()
            props.add("date", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Start date yyyy-MM-dd") })
            props.add("lat", JsonObject().apply { addProperty("type", "number") })
            props.add("lon", JsonObject().apply { addProperty("type", "number") })
            props.add("days", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "Number of days (1-7, default 1)"); addProperty("minimum", 1); addProperty("maximum", 7) })
            val required = JsonArray(); required.add("date"); required.add("lat"); required.add("lon")
            return JsonObject().apply { addProperty("type", "object"); add("properties", props); add("required", required) }
        }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonElement {
        val dateStr = args.get("date")?.asString ?: return errorJson("missing 'date'")
        val lat = args.get("lat")?.asDouble ?: return errorJson("missing 'lat'")
        val lon = args.get("lon")?.asDouble ?: return errorJson("missing 'lon'")
        val days = (args.get("days")?.asInt ?: 1).coerceIn(1, 7)

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val startDate = try { sdf.parse(dateStr) } catch (_: Exception) { null }
            ?: return errorJson("Invalid date format")

        val tzOffsetH = TimeZone.getDefault().getOffset(startDate.time) / 3_600_000.0
        val results = JsonArray()

        for (d in 0 until days) {
            val dayMs = startDate.time + d * 86_400_000L
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = dayMs }
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) + 1
            val day = cal.get(Calendar.DAY_OF_MONTH)

            val moon = MoonCalculator.compute(lat, lon, dayMs + 12 * 3_600_000L)

            fun findTwilightBound(thresholdDeg: Double, evening: Boolean): String? {
                val startH = if (evening) 12.0 else 0.0
                val endH = if (evening) 24.0 else 12.0
                var prevAlt = sunAlt(lat, lon, year, month, day, startH - tzOffsetH)
                val step = 0.1
                var h = startH + step
                while (h <= endH) {
                    val alt = sunAlt(lat, lon, year, month, day, h - tzOffsetH)
                    if (evening && prevAlt > thresholdDeg && alt <= thresholdDeg) {
                        return "%02d:%02d".format(h.toInt(), ((h - h.toInt()) * 60).toInt())
                    }
                    if (!evening && prevAlt < thresholdDeg && alt >= thresholdDeg) {
                        return "%02d:%02d".format(h.toInt(), ((h - h.toInt()) * 60).toInt())
                    }
                    prevAlt = alt
                    h += step
                }
                return null
            }

            fun fmtMoonH(utcH: Double?): String? {
                utcH ?: return null
                val local = utcH + tzOffsetH
                val h = local.toInt().coerceIn(0, 23)
                val m = ((local - local.toInt()) * 60).toInt().coerceIn(0, 59)
                return "%02d:%02d".format(h, m)
            }

            val dayObj = JsonObject()
            dayObj.addProperty("date", sdf.format(java.util.Date(dayMs)))
            dayObj.addProperty("moonPhase", moon.phaseName)
            dayObj.addProperty("illuminationPct", "%.1f".format(moon.illuminationPercent).toDouble())
            dayObj.addProperty("isWaxing", moon.isWaxing)
            fmtMoonH(moon.moonriseUtcHours)?.let { dayObj.addProperty("moonrise", it) }
            fmtMoonH(moon.moonsetUtcHours)?.let { dayObj.addProperty("moonset", it) }

            findTwilightBound(-6.0, false)?.let { dayObj.addProperty("civilDawn", it) }
            findTwilightBound(-6.0, true)?.let { dayObj.addProperty("civilDusk", it) }
            findTwilightBound(-12.0, false)?.let { dayObj.addProperty("nauticalDawn", it) }
            findTwilightBound(-12.0, true)?.let { dayObj.addProperty("nauticalDusk", it) }
            findTwilightBound(-18.0, false)?.let { dayObj.addProperty("astroDawn", it) }
            findTwilightBound(-18.0, true)?.let { dayObj.addProperty("astroDusk", it) }

            results.add(dayObj)
        }

        return results
    }

    private fun sunAlt(lat: Double, lon: Double, y: Int, m: Int, d: Int, utcH: Double): Double {
        return SunPositionCalculator.compute(lat, lon, y, m, d, utcH).altitudeDeg
    }

    private fun errorJson(msg: String) = JsonObject().apply { addProperty("error", msg) }
}
