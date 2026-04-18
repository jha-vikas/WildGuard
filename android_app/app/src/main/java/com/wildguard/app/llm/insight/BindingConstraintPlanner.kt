package com.wildguard.app.llm.insight

import com.google.gson.JsonParser
import com.wildguard.app.llm.prompt.PromptTemplates
import com.wildguard.app.llm.provider.LlmProvider
import com.wildguard.app.modules.uv.SunPositionCalculator
import com.wildguard.app.modules.uv.UVIndexCalculator
import java.util.Calendar
import java.util.TimeZone

data class TripLeg(
    val label: String,
    val bearingDeg: Double,
    val durationMin: Int
)

data class LegTimeline(
    val leg: TripLeg,
    val startMs: Long,
    val endMs: Long,
    val peakUV: Double,
    val avgSunEl: Double,
    val sunBearingAtStart: Double,
    val sunBearingAtEnd: Double,
    val violations: List<String>
)

data class BindingAnalysis(
    val bindingLeg: String,
    val bindingConstraint: String,
    val cascadeEffects: List<String>,
    val restructuredLegs: List<String>,
    val tradeoffSummary: String
)

class BindingConstraintPlanner {

    fun overlayTimeline(
        legs: List<TripLeg>,
        startMs: Long,
        lat: Double,
        lon: Double,
        altitudeM: Double = 0.0,
        maxUV: Double = 8.0,
        maxSunEl: Double = 75.0
    ): List<LegTimeline> {
        var currentMs = startMs
        return legs.map { leg ->
            val legStart = currentMs
            val legEnd = currentMs + leg.durationMin * 60_000L
            val midMs = legStart + (legEnd - legStart) / 2

            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            val points = (0..leg.durationMin step 5).map { min ->
                val ms = legStart + min * 60_000L
                cal.timeInMillis = ms
                val year = cal.get(Calendar.YEAR)
                val month = cal.get(Calendar.MONTH) + 1
                val day = cal.get(Calendar.DAY_OF_MONTH)
                val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
                val sun = SunPositionCalculator.compute(lat, lon, year, month, day, hour)
                val uv = UVIndexCalculator.compute(
                    sunPosition = sun, altitudeMeters = altitudeM, lightLux = null,
                    lat = lat, lon = lon, timeMillis = ms
                )
                Triple(sun, uv, ms)
            }

            val peakUV = points.maxOfOrNull { it.second.uvIndex } ?: 0.0
            val avgSunEl = points.map { it.first.altitudeDeg }.average()
            val sunAzStart = points.firstOrNull()?.first?.azimuthDeg ?: 0.0
            val sunAzEnd = points.lastOrNull()?.first?.azimuthDeg ?: 0.0

            val violations = mutableListOf<String>()
            if (peakUV > maxUV) violations.add("UV exceeds $maxUV (peak: ${"%.1f".format(peakUV)})")
            if (avgSunEl > maxSunEl) violations.add("Sun elevation >$maxSunEl° (avg: ${"%.1f".format(avgSunEl)})")

            // Check bearing into sun
            val midSun = points.getOrNull(points.size / 2)?.first
            if (midSun != null && midSun.altitudeDeg > 5.0) {
                val bearingDelta = angleDelta(leg.bearingDeg, midSun.azimuthDeg)
                if (bearingDelta < 20.0) {
                    violations.add("Walking into sun (bearing delta: ${"%.0f".format(bearingDelta)}°)")
                }
            }

            currentMs = legEnd
            LegTimeline(
                leg = leg,
                startMs = legStart,
                endMs = legEnd,
                peakUV = peakUV,
                avgSunEl = avgSunEl,
                sunBearingAtStart = sunAzStart,
                sunBearingAtEnd = sunAzEnd,
                violations = violations
            )
        }
    }

    fun buildPrompt(timelines: List<LegTimeline>): String {
        val timelineStr = timelines.joinToString("\n\n") { tl ->
            val v = if (tl.violations.isEmpty()) "none" else tl.violations.joinToString("; ")
            "LEG [${tl.leg.label}] bearing=${tl.leg.bearingDeg}° duration=${tl.leg.durationMin}min\n" +
                "  peakUV=${tl.peakUV} avgSunEl=${"%.1f".format(tl.avgSunEl)}°\n" +
                "  sunAz: ${"%.0f".format(tl.sunBearingAtStart)}°→${"%.0f".format(tl.sunBearingAtEnd)}°\n" +
                "  violations: $v"
        }

        return PromptTemplates.BINDING_CONSTRAINT_USER_TEMPLATE
            .replace("{TIMELINE}", timelineStr)
            .replace("{LEG_COUNT}", timelines.size.toString())
    }

    suspend fun analyze(
        legs: List<TripLeg>,
        startMs: Long,
        lat: Double,
        lon: Double,
        provider: LlmProvider,
        altitudeM: Double = 0.0
    ): Result<BindingAnalysis> = try {
        val timelines = overlayTimeline(legs, startMs, lat, lon, altitudeM)
        val prompt = buildPrompt(timelines)
        val response = provider.generate(prompt, PromptTemplates.BINDING_CONSTRAINT_SYSTEM)
        if (response.error != null) {
            Result.failure(Exception(response.error))
        } else {
            Result.success(parseAnalysis(response.content))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun hasViolations(timelines: List<LegTimeline>): Boolean =
        timelines.any { it.violations.isNotEmpty() }

    private fun parseAnalysis(content: String): BindingAnalysis = try {
        val jsonStr = content.let {
            val start = it.indexOf('{')
            val end = it.lastIndexOf('}')
            if (start >= 0 && end > start) it.substring(start, end + 1) else it
        }
        val obj = JsonParser.parseString(jsonStr).asJsonObject
        BindingAnalysis(
            bindingLeg = obj.get("bindingLeg")?.asString ?: "unknown",
            bindingConstraint = obj.get("bindingConstraint")?.asString ?: "unknown",
            cascadeEffects = obj.getAsJsonArray("cascadeEffects")
                ?.map { it.asString } ?: emptyList(),
            restructuredLegs = obj.getAsJsonArray("restructuredLegs")
                ?.map { it.asString } ?: emptyList(),
            tradeoffSummary = obj.get("tradeoffSummary")?.asString ?: ""
        )
    } catch (_: Exception) {
        BindingAnalysis(
            bindingLeg = "parse_error",
            bindingConstraint = "unknown",
            cascadeEffects = emptyList(),
            restructuredLegs = emptyList(),
            tradeoffSummary = content.take(300)
        )
    }

    private fun angleDelta(a: Double, b: Double): Double {
        val d = kotlin.math.abs(a - b) % 360.0
        return kotlin.math.min(d, 360.0 - d)
    }
}
