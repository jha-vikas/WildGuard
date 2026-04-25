package com.wildguard.app.llm.agent.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wildguard.app.llm.agent.Tool
import com.wildguard.app.llm.agent.ToolContext
import com.wildguard.app.modules.altitude.AltitudeSicknessCalculator
import com.wildguard.app.modules.altitude.LakeLouiseSymptoms

class ComputeAltitudeRiskTool : Tool {
    override val name = "compute_altitude_risk"
    override val description = "Evaluates altitude sickness risk using the Lake Louise scoring system. Returns ascent rate, risk level, recommendation, and Lake Louise score."
    override val paramsSchema: JsonObject
        get() {
            val props = JsonObject()
            props.add("currentAltM", JsonObject().apply { addProperty("type", "number"); addProperty("description", "Current altitude in meters") })
            props.add("headache", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "Lake Louise headache score 0-3") })
            props.add("giNausea", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "GI/nausea score 0-3") })
            props.add("fatigue", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "Fatigue score 0-3") })
            props.add("dizziness", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "Dizziness score 0-3") })
            val required = com.google.gson.JsonArray(); required.add("currentAltM")
            return JsonObject().apply { addProperty("type", "object"); add("properties", props); add("required", required) }
        }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonElement {
        val altM = args.get("currentAltM")?.asDouble ?: return errorJson("missing 'currentAltM'")
        val symptoms = LakeLouiseSymptoms(
            headache = args.get("headache")?.asInt ?: 0,
            giNausea = args.get("giNausea")?.asInt ?: 0,
            fatigue = args.get("fatigue")?.asInt ?: 0,
            dizziness = args.get("dizziness")?.asInt ?: 0
        )

        val calc = AltitudeSicknessCalculator()
        val risk = calc.evaluate(altM, symptoms)

        val result = JsonObject()
        result.addProperty("currentAltM", altM)
        result.addProperty("ascentRateMPerDay", risk.ascentRate)
        result.addProperty("riskLevel", risk.riskLevel.name)
        result.addProperty("recommendation", risk.recommendation)
        result.addProperty("lakeLouiseScore", risk.lakeLouiseScore)
        result.addProperty("boilingPointC", 100.0 - (altM / 300.0))
        return result
    }

    private fun errorJson(msg: String) = JsonObject().apply { addProperty("error", msg) }
}
