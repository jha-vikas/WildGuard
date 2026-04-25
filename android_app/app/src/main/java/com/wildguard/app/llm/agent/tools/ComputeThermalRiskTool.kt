package com.wildguard.app.llm.agent.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wildguard.app.llm.agent.Tool
import com.wildguard.app.llm.agent.ToolContext
import com.wildguard.app.modules.thermal.HeatIndexCalculator
import com.wildguard.app.modules.thermal.WBGTCalculator
import com.wildguard.app.modules.thermal.WindChillCalculator

class ComputeThermalRiskTool : Tool {
    override val name = "compute_thermal_risk"
    override val description = "Computes thermal risk: wind chill (cold), heat index, and WBGT (heat). Automatically selects the relevant index based on temperature. Returns risk level and activity guidance."
    override val paramsSchema: JsonObject
        get() {
            val props = JsonObject()
            props.add("tempC", JsonObject().apply { addProperty("type", "number"); addProperty("description", "Air temperature in Celsius") })
            props.add("humidityPct", JsonObject().apply { addProperty("type", "number"); addProperty("description", "Relative humidity 0-100") })
            props.add("windSpeedKmh", JsonObject().apply { addProperty("type", "number"); addProperty("description", "Wind speed km/h") })
            val required = com.google.gson.JsonArray(); required.add("tempC"); required.add("humidityPct"); required.add("windSpeedKmh")
            return JsonObject().apply { addProperty("type", "object"); add("properties", props); add("required", required) }
        }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonElement {
        val tempC = args.get("tempC")?.asDouble ?: return errorJson("missing 'tempC'")
        val humidity = args.get("humidityPct")?.asDouble ?: return errorJson("missing 'humidityPct'")
        val wind = args.get("windSpeedKmh")?.asDouble ?: return errorJson("missing 'windSpeedKmh'")

        val result = JsonObject()
        result.addProperty("inputTempC", tempC)
        result.addProperty("inputHumidity", humidity)
        result.addProperty("inputWindKmh", wind)

        val wc = WindChillCalculator.calculate(tempC, wind)
        if (wc != null) {
            val wcObj = JsonObject()
            wcObj.addProperty("effectiveTempC", "%.1f".format(wc.effectiveTempC).toDouble())
            wcObj.addProperty("riskLevel", wc.riskLevel.name)
            wc.frostbiteMinutes?.let { wcObj.addProperty("frostbiteMinutes", "%.0f".format(it).toDouble()) }
            result.add("windChill", wcObj)
        }

        if (tempC >= 27) {
            val hi = HeatIndexCalculator.calculate(tempC, humidity)
            val hiObj = JsonObject()
            hiObj.addProperty("heatIndexC", "%.1f".format(hi.heatIndexC).toDouble())
            hi.riskLevel?.let { hiObj.addProperty("riskLevel", it.name) }
            hiObj.addProperty("recommendation", hi.recommendation)
            result.add("heatIndex", hiObj)

            val wbgt = WBGTCalculator.calculate(tempC, humidity)
            val wbgtObj = JsonObject()
            wbgtObj.addProperty("wbgtC", "%.1f".format(wbgt.wbgtC).toDouble())
            wbgtObj.addProperty("activityGuidance", wbgt.activityGuidance.name)
            wbgtObj.addProperty("restCycleMinutes", wbgt.restCycleMinutes)
            wbgtObj.addProperty("description", wbgt.description)
            result.add("wbgt", wbgtObj)
        }

        return result
    }

    private fun errorJson(msg: String) = JsonObject().apply { addProperty("error", msg) }
}
