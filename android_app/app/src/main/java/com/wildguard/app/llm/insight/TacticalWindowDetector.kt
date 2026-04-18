package com.wildguard.app.llm.insight

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.wildguard.app.core.model.SensorState
import com.wildguard.app.llm.prompt.PromptTemplates
import com.wildguard.app.llm.provider.LlmProvider
import com.wildguard.app.modules.uv.SunPositionCalculator
import com.wildguard.app.modules.uv.UVIndexCalculator
import java.util.Calendar
import java.util.TimeZone

data class TimeSeriesPoint(
    val timeMs: Long,
    val localHour: Double,
    val uv: Double,
    val sunAzDeg: Double,
    val sunElDeg: Double,
    val tideHeight: Double?,
    val tideDirection: String?
)

data class ConstraintProfile(
    val name: String,
    val maxUV: Double? = null,
    val minSunElDeg: Double? = null,
    val maxSunElDeg: Double? = null,
    val preferredBearing: Double? = null,
    val bearingToleranceDeg: Double? = null,
    val requireRisingTide: Boolean? = null,
    val minTideHeight: Double? = null,
    val maxTideHeight: Double? = null,
    val notes: String? = null
)

data class TacticalWindow(
    val startMs: Long,
    val endMs: Long,
    val bindingConstraint: String,
    val qualityScore: Double,
    val tradeoffs: String
)

data class TacticalDetectionResult(
    val windows: List<TacticalWindow>,
    val seriesStartMs: Long
)

class TacticalWindowDetector(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wildguard_constraints", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun generate24hTimeSeries(
        sensor: SensorState,
        startMs: Long = System.currentTimeMillis()
    ): List<TimeSeriesPoint> {
        val loc = sensor.location ?: return emptyList()
        val tzOffsetH = TimeZone.getDefault().getOffset(startMs) / 3600_000.0

        val points = mutableListOf<TimeSeriesPoint>()
        for (i in 0 until 96) {
            val offsetMs = i * 15L * 60_000L
            val pointMs = startMs + offsetMs

            val pointCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = pointMs
            }
            val year = pointCal.get(Calendar.YEAR)
            val month = pointCal.get(Calendar.MONTH) + 1
            val day = pointCal.get(Calendar.DAY_OF_MONTH)
            val utcHour = pointCal.get(Calendar.HOUR_OF_DAY) + pointCal.get(Calendar.MINUTE) / 60.0

            val localHour = utcHour + tzOffsetH
            val localHourNorm = ((localHour % 24.0) + 24.0) % 24.0

            val sun = SunPositionCalculator.compute(
                loc.latitude, loc.longitude, year, month, day, utcHour
            )

            val uv = UVIndexCalculator.compute(
                sunPosition = sun,
                altitudeMeters = loc.altitudeGps ?: 0.0,
                lightLux = null,
                lat = loc.latitude,
                lon = loc.longitude,
                timeMillis = pointMs
            ).uvIndex

            points.add(
                TimeSeriesPoint(
                    timeMs = pointMs,
                    localHour = localHourNorm,
                    uv = uv,
                    sunAzDeg = sun.azimuthDeg,
                    sunElDeg = sun.altitudeDeg,
                    tideHeight = null,
                    tideDirection = null
                )
            )
        }
        return points
    }

    fun saveConstraintProfile(profile: ConstraintProfile) {
        val profiles = loadConstraintProfiles().toMutableList()
        val idx = profiles.indexOfFirst { it.name == profile.name }
        if (idx >= 0) profiles[idx] = profile else profiles.add(profile)
        prefs.edit().putString("constraint_profiles", gson.toJson(profiles)).apply()
    }

    fun loadConstraintProfiles(): List<ConstraintProfile> {
        val json = prefs.getString("constraint_profiles", null) ?: return emptyList()
        val type = object : TypeToken<List<ConstraintProfile>>() {}.type
        return gson.fromJson(json, type)
    }

    fun buildPrompt(
        timeSeries: List<TimeSeriesPoint>,
        profile: ConstraintProfile
    ): String {
        val tsCompact = timeSeries.joinToString("\n") { pt ->
            "%.1fh|UV%.1f|az%.0f|el%.0f%s".format(
                pt.localHour, pt.uv, pt.sunAzDeg, pt.sunElDeg,
                if (pt.tideHeight != null) "|tide%.1fm %s".format(pt.tideHeight, pt.tideDirection ?: "") else ""
            )
        }

        val constraintStr = buildString {
            appendLine("Constraints [${profile.name}]:")
            profile.maxUV?.let { appendLine("  maxUV: $it") }
            profile.minSunElDeg?.let { appendLine("  minSunEl: $it°") }
            profile.maxSunElDeg?.let { appendLine("  maxSunEl: $it°") }
            profile.preferredBearing?.let { appendLine("  preferredBearing: $it° ± ${profile.bearingToleranceDeg ?: 30}°") }
            profile.requireRisingTide?.let { appendLine("  requireRisingTide: $it") }
            profile.minTideHeight?.let { appendLine("  minTideHeight: ${it}m") }
            profile.maxTideHeight?.let { appendLine("  maxTideHeight: ${it}m") }
            profile.notes?.let { appendLine("  notes: $it") }
        }

        return PromptTemplates.TACTICAL_WINDOW_USER_TEMPLATE
            .replace("{TIME_SERIES}", tsCompact)
            .replace("{CONSTRAINTS}", constraintStr)
    }

    suspend fun detect(
        sensor: SensorState,
        profile: ConstraintProfile,
        provider: LlmProvider
    ): Result<TacticalDetectionResult> = try {
        val seriesStartMs = System.currentTimeMillis()
        val ts = generate24hTimeSeries(sensor, seriesStartMs)
        if (ts.isEmpty()) {
            Result.failure(Exception("No location data available"))
        } else {
            val prompt = buildPrompt(ts, profile)
            val response = provider.generate(prompt, PromptTemplates.TACTICAL_WINDOW_SYSTEM)

            if (response.error != null) {
                Result.failure(Exception(response.error))
            } else {
                Result.success(
                    TacticalDetectionResult(
                        windows = parseWindows(response.content, seriesStartMs),
                        seriesStartMs = seriesStartMs
                    )
                )
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Converts LLM-returned local clock hours (0–24) into actual epoch timestamps.
     * The LLM sees localHour values that wrap at midnight, so a window at hour 6
     * means 6 AM on whichever calendar day that falls in the 24h series.
     */
    private fun parseWindows(content: String, seriesStartMs: Long): List<TacticalWindow> = try {
        val cal = Calendar.getInstance()
        cal.timeInMillis = seriesStartMs
        val currentLocalHour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0

        fun localHourToEpoch(h: Double): Long {
            var deltaH = h - currentLocalHour
            if (deltaH < 0) deltaH += 24.0
            return seriesStartMs + (deltaH * 3_600_000).toLong()
        }

        val jsonStr = extractJsonArray(content)
        val arr = JsonParser.parseString(jsonStr).asJsonArray
        arr.map { elem ->
            val obj = elem.asJsonObject
            val startHour = obj.get("startHour")?.asDouble ?: 0.0
            val endHour   = obj.get("endHour")?.asDouble   ?: 0.0
            val startEpoch = localHourToEpoch(startHour)
            var endEpoch   = localHourToEpoch(endHour)
            if (endEpoch <= startEpoch) endEpoch += 24 * 3_600_000L
            TacticalWindow(
                startMs = startEpoch,
                endMs = endEpoch,
                bindingConstraint = obj.get("bindingConstraint")?.asString ?: "unknown",
                qualityScore = obj.get("qualityScore")?.asDouble ?: 0.0,
                tradeoffs = obj.get("tradeoffs")?.asString ?: ""
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun extractJsonArray(text: String): String {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else "[]"
    }
}
