package com.wildguard.app.llm.agent.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wildguard.app.llm.agent.Tool
import com.wildguard.app.llm.agent.ToolContext
import com.wildguard.app.modules.weather.PressureTrend
import java.util.Calendar

class ForecastZambrettiTool : Tool {
    override val name = "forecast_zambretti"
    override val description = "Offline fallback weather forecast using the Zambretti algorithm. Use ONLY when fetch_online_weather fails or cannot be reached. Requires local barometer history."
    override val paramsSchema: JsonObject
        get() {
            val props = JsonObject()
            props.add("windDirectionDeg", JsonObject().apply {
                addProperty("type", "number")
                addProperty("description", "Optional observed wind direction in degrees (0=N, 90=E)")
            })
            return JsonObject().apply {
                addProperty("type", "object")
                add("properties", props)
                add("required", com.google.gson.JsonArray())
            }
        }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonElement {
        val pl = ctx.pressureLogger
        val pressure = pl.currentPressure
        val trend = pl.trendClassification
        val month = Calendar.getInstance().get(Calendar.MONTH) + 1
        val isSummer = month in 4..9

        if (pressure == null) {
            return JsonObject().apply { addProperty("error", "No barometer data available") }
        }

        val letter = zambrettiLetter(pressure, trend, isSummer)
        val verdict = ZAMBRETTI_TABLE[letter] ?: "Unknown conditions"

        val result = JsonObject()
        result.addProperty("pressureHpa", pressure)
        result.addProperty("trend", trend.name)
        result.addProperty("zambrettiCode", letter.toString())
        result.addProperty("forecast", verdict)
        result.addProperty("validityHours", "6-12")
        result.addProperty("note", "Zambretti accuracy ~70-80% for 6-12h window")
        return result
    }

    private fun zambrettiLetter(pressure: Float, trend: PressureTrend, summer: Boolean): Char {
        val p = pressure.toDouble()
        return when (trend) {
            PressureTrend.RAPID_RISE, PressureTrend.SLOW_RISE -> when {
                p >= 1030 -> 'A'
                p >= 1023 -> 'B'
                p >= 1015 -> if (summer) 'C' else 'D'
                p >= 1005 -> 'E'
                else -> 'F'
            }
            PressureTrend.STEADY -> when {
                p >= 1030 -> 'A'
                p >= 1023 -> 'H'
                p >= 1015 -> 'J'
                p >= 1005 -> 'K'
                p >= 995 -> 'L'
                else -> 'M'
            }
            PressureTrend.SLOW_DROP, PressureTrend.RAPID_DROP -> when {
                p >= 1030 -> 'N'
                p >= 1023 -> 'O'
                p >= 1015 -> 'P'
                p >= 1005 -> if (trend == PressureTrend.RAPID_DROP) 'R' else 'Q'
                p >= 995 -> 'S'
                else -> if (trend == PressureTrend.RAPID_DROP) 'U' else 'T'
            }
        }
    }

    companion object {
        private val ZAMBRETTI_TABLE = mapOf(
            'A' to "Settled fine weather",
            'B' to "Fine weather",
            'C' to "Fine, becoming less settled",
            'D' to "Fairly fine, showery later",
            'E' to "Showery, becoming more unsettled",
            'F' to "Unsettled, rain later",
            'H' to "Fine, possible showers",
            'J' to "Changeable, improving",
            'K' to "Fairly fine, showers likely",
            'L' to "Showery, bright intervals",
            'M' to "Changeable, some rain",
            'N' to "Fine weather, may worsen",
            'O' to "Fairly fine, worsening later",
            'P' to "Showery, early improvement",
            'Q' to "Unsettled, short fine intervals",
            'R' to "Rain at times, worse later",
            'S' to "Rain at times, becoming very unsettled",
            'T' to "Very unsettled, rain",
            'U' to "Stormy, much rain"
        )
    }
}
