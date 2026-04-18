package com.wildguard.app.llm.insight

import com.google.gson.JsonParser
import com.wildguard.app.core.model.SensorState
import com.wildguard.app.llm.prompt.PromptTemplates
import com.wildguard.app.llm.provider.LlmProvider
import com.wildguard.app.modules.uv.SunPositionCalculator
import kotlin.math.abs
import kotlin.math.min

data class ConsistencyAlert(
    val compassDeg: Double,
    val expectedSunDeg: Double,
    val gpsDeg: Double?,
    val discrepancy: Double,
    val likelyCause: String,
    val recommendation: String
)

class SensorConsistencyChecker {

    private val divergenceThreshold = 15.0

    fun check(sensor: SensorState, timestampMs: Long = System.currentTimeMillis()): ConsistencyCheckResult {
        val compassHeading = sensor.compassHeadingDeg?.toDouble() ?: return ConsistencyCheckResult.NoData
        val loc = sensor.location ?: return ConsistencyCheckResult.NoData

        val sun = SunPositionCalculator.computeForLocalTime(loc.latitude, loc.longitude, timestampMs)

        // Sun must be visible for meaningful comparison
        if (sun.altitudeDeg < 5.0) return ConsistencyCheckResult.Inconclusive("Sun too low for reliable bearing check")

        val gpsHeading = loc.bearingGps?.toDouble()

        val compassVsSun = angleDelta(compassHeading, sun.azimuthDeg)
        val compassVsGps = if (gpsHeading != null) angleDelta(compassHeading, gpsHeading) else null
        val gpsVsSun = if (gpsHeading != null) angleDelta(gpsHeading, sun.azimuthDeg) else null

        val maxDelta = maxOf(
            compassVsSun,
            compassVsGps ?: 0.0,
            gpsVsSun ?: 0.0
        )

        return if (maxDelta > divergenceThreshold) {
            ConsistencyCheckResult.Divergent(
                compassDeg = compassHeading,
                expectedSunAzDeg = sun.azimuthDeg,
                gpsDeg = gpsHeading,
                compassVsSunDelta = compassVsSun,
                compassVsGpsDelta = compassVsGps,
                gpsVsSunDelta = gpsVsSun
            )
        } else {
            ConsistencyCheckResult.Consistent(maxDelta)
        }
    }

    fun buildPrompt(result: ConsistencyCheckResult.Divergent, timestampMs: Long): String {
        return PromptTemplates.SENSOR_CONSISTENCY_USER_TEMPLATE
            .replace("{COMPASS}", "%.1f".format(result.compassDeg))
            .replace("{SUN_AZ}", "%.1f".format(result.expectedSunAzDeg))
            .replace("{GPS}", result.gpsDeg?.let { "%.1f".format(it) } ?: "N/A")
            .replace("{COMPASS_VS_SUN}", "%.1f".format(result.compassVsSunDelta))
            .replace("{COMPASS_VS_GPS}", result.compassVsGpsDelta?.let { "%.1f".format(it) } ?: "N/A")
            .replace("{GPS_VS_SUN}", result.gpsVsSunDelta?.let { "%.1f".format(it) } ?: "N/A")
            .replace("{TIMESTAMP}", timestampMs.toString())
    }

    suspend fun checkAndAnalyze(
        sensor: SensorState,
        provider: LlmProvider,
        timestampMs: Long = System.currentTimeMillis()
    ): Result<ConsistencyAlert> {
        val result = check(sensor, timestampMs)
        if (result !is ConsistencyCheckResult.Divergent) {
            return Result.failure(Exception("Sensors consistent or insufficient data"))
        }

        return try {
            val prompt = buildPrompt(result, timestampMs)
            val response = provider.generate(prompt, PromptTemplates.SENSOR_CONSISTENCY_SYSTEM)
            if (response.error != null) {
                Result.failure(Exception(response.error))
            } else {
                Result.success(parseAlert(response.content, result))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseAlert(content: String, result: ConsistencyCheckResult.Divergent): ConsistencyAlert = try {
        val jsonStr = content.let {
            val start = it.indexOf('{')
            val end = it.lastIndexOf('}')
            if (start >= 0 && end > start) it.substring(start, end + 1) else it
        }
        val obj = JsonParser.parseString(jsonStr).asJsonObject
        ConsistencyAlert(
            compassDeg = result.compassDeg,
            expectedSunDeg = result.expectedSunAzDeg,
            gpsDeg = result.gpsDeg,
            discrepancy = result.compassVsSunDelta,
            likelyCause = obj.get("likelyCause")?.asString ?: "unknown",
            recommendation = obj.get("recommendation")?.asString ?: ""
        )
    } catch (_: Exception) {
        ConsistencyAlert(
            compassDeg = result.compassDeg,
            expectedSunDeg = result.expectedSunAzDeg,
            gpsDeg = result.gpsDeg,
            discrepancy = result.compassVsSunDelta,
            likelyCause = "analysis_unavailable",
            recommendation = content.take(200)
        )
    }

    private fun angleDelta(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return min(d, 360.0 - d)
    }
}

sealed class ConsistencyCheckResult {
    data object NoData : ConsistencyCheckResult()

    data class Inconclusive(val reason: String) : ConsistencyCheckResult()

    data class Consistent(val maxDelta: Double) : ConsistencyCheckResult()

    data class Divergent(
        val compassDeg: Double,
        val expectedSunAzDeg: Double,
        val gpsDeg: Double?,
        val compassVsSunDelta: Double,
        val compassVsGpsDelta: Double?,
        val gpsVsSunDelta: Double?
    ) : ConsistencyCheckResult()
}
