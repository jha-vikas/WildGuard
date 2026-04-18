package com.wildguard.app.llm.insight

import com.wildguard.app.core.model.SensorState
import com.wildguard.app.llm.prompt.PromptTemplates
import com.wildguard.app.llm.provider.LlmProvider
import com.wildguard.app.modules.uv.SunPositionCalculator
import com.google.gson.JsonParser
import kotlin.math.abs

data class SensorSnapshot(
    val timestampMs: Long,
    val uvIndex: Double?,
    val sunElDeg: Double?,
    val sunAzDeg: Double?,
    val pressureHpa: Double?,
    val compassHeadingDeg: Double?,
    val lightLux: Double?
)

data class DriftDeltas(
    val dUVPerHr: Double?,
    val dSunElPerHr: Double?,
    val dPressurePerHr: Double?,
    val headingVsSunTrend: Double?,
    val dLuxPerHr: Double?
)

data class DriftWarning(
    val riskLabel: String,
    val leadTimeMin: Int,
    val primaryDriver: String,
    val secondaryDriver: String,
    val suggestedAction: String
)

class DriftAnalyzer {

    private val history = ArrayDeque<SensorSnapshot>(10)
    private val maxHistoryAge = 90L * 60_000L // 90 minutes
    private val sampleInterval = 10L * 60_000L // 10 minutes

    private var lastSampleMs = 0L

    // Thresholds for triggering an LLM call
    private val uvDeltaThreshold = 1.5    // UV index change per hour
    private val sunElDeltaThreshold = 8.0 // degrees per hour
    private val pressureDeltaThreshold = 1.0 // hPa per hour
    private val headingSunDivergenceThreshold = 20.0 // degrees

    fun recordSample(sensor: SensorState, uvIndex: Double?, timestampMs: Long = System.currentTimeMillis()) {
        if (timestampMs - lastSampleMs < sampleInterval && history.isNotEmpty()) return

        val loc = sensor.location
        val sunPos = if (loc != null) {
            SunPositionCalculator.computeForLocalTime(loc.latitude, loc.longitude, timestampMs)
        } else null

        history.addLast(
            SensorSnapshot(
                timestampMs = timestampMs,
                uvIndex = uvIndex,
                sunElDeg = sunPos?.altitudeDeg,
                sunAzDeg = sunPos?.azimuthDeg,
                pressureHpa = sensor.pressureHpa?.toDouble(),
                compassHeadingDeg = sensor.compassHeadingDeg?.toDouble(),
                lightLux = sensor.lightLux?.toDouble()
            )
        )
        lastSampleMs = timestampMs

        // Trim old entries
        val cutoff = timestampMs - maxHistoryAge
        while (history.isNotEmpty() && history.first().timestampMs < cutoff) {
            history.removeFirst()
        }
    }

    fun computeDeltas(): DriftDeltas? {
        if (history.size < 3) return null
        val oldest = history.first()
        val newest = history.last()
        val hourSpan = (newest.timestampMs - oldest.timestampMs) / 3_600_000.0
        if (hourSpan < 0.15) return null

        return DriftDeltas(
            dUVPerHr = deltaPer(oldest.uvIndex, newest.uvIndex, hourSpan),
            dSunElPerHr = deltaPer(oldest.sunElDeg, newest.sunElDeg, hourSpan),
            dPressurePerHr = deltaPer(oldest.pressureHpa, newest.pressureHpa, hourSpan),
            headingVsSunTrend = computeHeadingVsSunTrend(),
            dLuxPerHr = deltaPer(oldest.lightLux, newest.lightLux, hourSpan)
        )
    }

    fun shouldTriggerLlm(): Boolean {
        val deltas = computeDeltas() ?: return false
        var exceededCount = 0
        if (deltas.dUVPerHr != null && abs(deltas.dUVPerHr) > uvDeltaThreshold) exceededCount++
        if (deltas.dSunElPerHr != null && abs(deltas.dSunElPerHr) > sunElDeltaThreshold) exceededCount++
        if (deltas.dPressurePerHr != null && abs(deltas.dPressurePerHr) > pressureDeltaThreshold) exceededCount++
        if (deltas.headingVsSunTrend != null && abs(deltas.headingVsSunTrend) > headingSunDivergenceThreshold) exceededCount++
        return exceededCount >= 2
    }

    fun buildPrompt(): String {
        val deltas = computeDeltas() ?: return ""
        val historyStr = history.joinToString("\n") { s ->
            val mins = (s.timestampMs - history.first().timestampMs) / 60_000
            "T+${mins}m: uv=${s.uvIndex?.let { "%.1f".format(it) } ?: "-"} " +
                "sunEl=${s.sunElDeg?.let { "%.1f".format(it) } ?: "-"} " +
                "hPa=${s.pressureHpa?.let { "%.1f".format(it) } ?: "-"} " +
                "heading=${s.compassHeadingDeg?.let { "%.0f".format(it) } ?: "-"} " +
                "lux=${s.lightLux?.let { "%.0f".format(it) } ?: "-"}"
        }

        val deltasStr = buildString {
            appendLine("Computed deltas (per hour):")
            deltas.dUVPerHr?.let { appendLine("  dUV/hr: ${"%.2f".format(it)}") }
            deltas.dSunElPerHr?.let { appendLine("  dSunEl/hr: ${"%.2f".format(it)}°") }
            deltas.dPressurePerHr?.let { appendLine("  dPressure/hr: ${"%.2f".format(it)} hPa") }
            deltas.headingVsSunTrend?.let { appendLine("  heading-vs-sun divergence: ${"%.1f".format(it)}°") }
        }

        return PromptTemplates.DRIFT_ANALYSIS_USER_TEMPLATE
            .replace("{HISTORY}", historyStr)
            .replace("{DELTAS}", deltasStr)
    }

    /**
     * Returns:
     *  - success(DriftWarning) — drift exceeds threshold and LLM produced a warning
     *  - success(null)         — no analysis needed (sub-threshold). NOT a failure.
     *  - failure(Exception)    — real error (LLM provider error, network, etc.)
     */
    suspend fun analyze(provider: LlmProvider): Result<DriftWarning?> = try {
        if (!shouldTriggerLlm()) {
            Result.success(null)
        } else {
            val prompt = buildPrompt()
            val response = provider.generate(prompt, PromptTemplates.DRIFT_ANALYSIS_SYSTEM)
            if (response.error != null) {
                Result.failure(Exception(response.error))
            } else {
                Result.success(parseWarning(response.content))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun getRiskLevel(): String {
        val deltas = computeDeltas() ?: return "none"
        var score = 0
        if (deltas.dUVPerHr != null && abs(deltas.dUVPerHr) > uvDeltaThreshold) score++
        if (deltas.dSunElPerHr != null && abs(deltas.dSunElPerHr) > sunElDeltaThreshold) score++
        if (deltas.dPressurePerHr != null && abs(deltas.dPressurePerHr) > pressureDeltaThreshold) score++
        if (deltas.headingVsSunTrend != null && abs(deltas.headingVsSunTrend) > headingSunDivergenceThreshold) score++
        return when {
            score >= 3 -> "red"
            score >= 2 -> "amber"
            score >= 1 -> "low"
            else -> "none"
        }
    }

    fun clearHistory() {
        history.clear()
        lastSampleMs = 0
    }

    private fun deltaPer(old: Double?, new: Double?, hours: Double): Double? {
        if (old == null || new == null || hours <= 0) return null
        return (new - old) / hours
    }

    private fun computeHeadingVsSunTrend(): Double? {
        if (history.size < 2) return null
        val recent = history.takeLast(3)
        val headings = recent.mapNotNull { it.compassHeadingDeg }
        val sunAzs = recent.mapNotNull { it.sunAzDeg }
        if (headings.size < 2 || sunAzs.size < 2) return null

        val headingDelta = angleDiff(headings.first(), headings.last())
        val sunDelta = angleDiff(sunAzs.first(), sunAzs.last())
        return abs(headingDelta - sunDelta)
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val d = (b - a + 540) % 360 - 180
        return d
    }

    private fun parseWarning(content: String): DriftWarning = try {
        val jsonStr = content.let {
            val start = it.indexOf('{')
            val end = it.lastIndexOf('}')
            if (start >= 0 && end > start) it.substring(start, end + 1) else it
        }
        val obj = JsonParser.parseString(jsonStr).asJsonObject
        DriftWarning(
            riskLabel = obj.get("riskLabel")?.asString ?: "unknown",
            leadTimeMin = obj.get("leadTimeMin")?.asInt ?: 0,
            primaryDriver = obj.get("primaryDriver")?.asString ?: "unknown",
            secondaryDriver = obj.get("secondaryDriver")?.asString ?: "",
            suggestedAction = obj.get("suggestedAction")?.asString ?: ""
        )
    } catch (_: Exception) {
        DriftWarning(
            riskLabel = "parse_error",
            leadTimeMin = 0,
            primaryDriver = "unknown",
            secondaryDriver = "",
            suggestedAction = content.take(200)
        )
    }
}
