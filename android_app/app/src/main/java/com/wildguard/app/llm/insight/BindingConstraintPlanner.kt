package com.wildguard.app.llm.insight

import android.content.Context
import com.google.gson.JsonParser
import com.wildguard.app.core.data.AssetRepository
import com.wildguard.app.core.model.SensorState
import com.wildguard.app.llm.prompt.PromptTemplates
import com.wildguard.app.llm.provider.LlmProvider
import com.wildguard.app.modules.tide.TidalStation
import com.wildguard.app.modules.tide.TidalStationRepository
import com.wildguard.app.modules.tide.TideCalculator
import com.wildguard.app.modules.uv.SunPositionCalculator
import com.wildguard.app.modules.uv.UVIndexCalculator
import com.wildguard.app.modules.weather.OnlineWeather
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
    val minTideM: Double?,
    val maxTideM: Double?,
    val tideDirectionAtStart: String?,
    val tideDirectionAtEnd: String?,
    val violations: List<String>
)

data class BindingAnalysis(
    val bindingLeg: String,
    val bindingConstraint: String,
    val cascadeEffects: List<String>,
    val restructuredLegs: List<String>,
    val tradeoffSummary: String
)

class BindingConstraintPlanner(context: Context) {

    private val stationRepo = TidalStationRepository(AssetRepository(context))

    // Maximum distance to consider a tidal station relevant for this trip.
    private val TIDE_RADIUS_KM = 300.0

    fun overlayTimeline(
        legs: List<TripLeg>,
        startMs: Long,
        lat: Double,
        lon: Double,
        altitudeM: Double = 0.0,
        maxUV: Double = 8.0,
        maxSunEl: Double = 75.0,
        profile: ConstraintProfile? = null
    ): List<LegTimeline> {
        // Resolve nearest tidal station once — null if user is inland.
        val nearestStation: TidalStation? = stationRepo
            .findNearby(lat, lon, TIDE_RADIUS_KM)
            .minByOrNull { TidalStationRepository.haversineKm(lat, lon, it.lat, it.lon) }

        // Compute the tide range at this station across the next 24h so we can
        // assess "high" vs "low" relative to local tidal behavior, not absolute height.
        val tideRangeMinMax: Pair<Double, Double>? = nearestStation?.let { station ->
            var lo = Double.POSITIVE_INFINITY
            var hi = Double.NEGATIVE_INFINITY
            for (i in 0 until 96) {
                val h = TideCalculator.computeTideHeight(station, startMs + i * 15L * 60_000L)
                if (h < lo) lo = h
                if (h > hi) hi = h
            }
            lo to hi
        }

        var currentMs = startMs
        return legs.map { leg ->
            val legStart = currentMs
            val legEnd = currentMs + leg.durationMin * 60_000L

            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            val stepMin = if (leg.durationMin <= 15) 1 else 5
            val points = (0..leg.durationMin step stepMin).map { min ->
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

            // Tide computations over the leg
            var minTide: Double? = null
            var maxTide: Double? = null
            var tideDirStart: String? = null
            var tideDirEnd: String? = null
            nearestStation?.let { station ->
                val heights = points.map { TideCalculator.computeTideHeight(station, it.third) }
                minTide = heights.min()
                maxTide = heights.max()
                tideDirStart = if (TideCalculator.isRising(station, legStart)) "rising" else "falling"
                tideDirEnd   = if (TideCalculator.isRising(station, legEnd))   "rising" else "falling"
            }

            val violations = mutableListOf<String>()
            if (peakUV > maxUV) violations.add("UV exceeds $maxUV (peak: ${"%.1f".format(peakUV)})")
            if (avgSunEl > maxSunEl) violations.add("Sun elevation >$maxSunEl° (avg: ${"%.1f".format(avgSunEl)})")

            // Walking-into-sun detection.
            val midSun = points.getOrNull(points.size / 2)?.first
            if (midSun != null && midSun.altitudeDeg > 5.0) {
                val bearingDelta = angleDelta(leg.bearingDeg, midSun.azimuthDeg)
                if (bearingDelta < 20.0) {
                    violations.add("Walking into sun (bearing delta: ${"%.0f".format(bearingDelta)}°)")
                }
            }

            // Tide-based heuristic violations. Triggered when leg label suggests a
            // coastal context (beach, shore, coastal, tide, sea) OR the user has
            // explicit tide constraints in a profile.
            if (nearestStation != null && maxTide != null && minTide != null && tideRangeMinMax != null) {
                val (tideLo, tideHi) = tideRangeMinMax
                val span = (tideHi - tideLo).coerceAtLeast(0.1)
                val relMax = (maxTide!! - tideLo) / span      // 0 = lowest, 1 = highest
                val relMin = (minTide!! - tideLo) / span
                val coastalHint = COASTAL_KEYWORDS.any { leg.label.contains(it, ignoreCase = true) }

                // Explicit profile overrides — always applied when present.
                profile?.minTideHeight?.let { floor ->
                    if (minTide!! < floor) violations.add(
                        "Tide drops to ${"%.1f".format(minTide)}m (below required ${"%.1f".format(floor)}m)"
                    )
                }
                profile?.maxTideHeight?.let { ceil ->
                    if (maxTide!! > ceil) violations.add(
                        "Tide reaches ${"%.1f".format(maxTide)}m (exceeds allowed ${"%.1f".format(ceil)}m)"
                    )
                }
                profile?.requireRisingTide?.let { req ->
                    if (req && (tideDirStart == "falling" || tideDirEnd == "falling")) {
                        violations.add("Rising tide required but tide is falling during leg")
                    }
                }

                // Heuristic — only applied when no explicit profile constraint hit.
                if (profile?.minTideHeight == null && profile?.maxTideHeight == null) {
                    if (coastalHint && relMax > 0.80) {
                        violations.add(
                            "Near-high tide on coastal leg (peak ${"%.1f".format(maxTide)}m, " +
                            "${(relMax * 100).toInt()}% of today's range) — beach/shore route may be submerged"
                        )
                    }
                    if (coastalHint && relMin < 0.20) {
                        violations.add(
                            "Near-low tide on coastal leg (trough ${"%.1f".format(minTide)}m, " +
                            "${(relMin * 100).toInt()}% of today's range) — deep water access may be limited"
                        )
                    }
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
                minTideM = minTide,
                maxTideM = maxTide,
                tideDirectionAtStart = tideDirStart,
                tideDirectionAtEnd = tideDirEnd,
                violations = violations
            )
        }
    }

    fun buildPrompt(
        timelines: List<LegTimeline>,
        lat: Double,
        lon: Double,
        startMs: Long,
        sensor: SensorState? = null,
        onlineWeather: OnlineWeather? = null
    ): String {
        val timelineStr = timelines.joinToString("\n\n") { tl ->
            val v = if (tl.violations.isEmpty()) "none" else tl.violations.joinToString("; ")
            val tideLine = if (tl.minTideM != null && tl.maxTideM != null)
                "\n  tide: ${"%.1f".format(tl.minTideM)}m→${"%.1f".format(tl.maxTideM)}m " +
                "(${tl.tideDirectionAtStart}→${tl.tideDirectionAtEnd})"
            else ""
            "LEG [${tl.leg.label}] bearing=${tl.leg.bearingDeg}° duration=${tl.leg.durationMin}min\n" +
                "  peakUV=${"%.1f".format(tl.peakUV)} avgSunEl=${"%.1f".format(tl.avgSunEl)}°\n" +
                "  sunAz: ${"%.0f".format(tl.sunBearingAtStart)}°→${"%.0f".format(tl.sunBearingAtEnd)}°" +
                tideLine + "\n" +
                "  violations: $v"
        }

        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(startMs))
        val pressureCtx = sensor?.pressureHpa?.let { "Barometric pressure: ${"%.1f".format(it)} hPa." } ?: ""
        val weatherCtx = onlineWeather?.let {
            "Current conditions: ${"%.0f".format(it.temperatureC)}°C (feels ${"%.0f".format(it.apparentTempC)}°C), " +
            "${it.humidityPercent}% RH, wind ${"%.0f".format(it.windSpeedKmh)} km/h, ${it.description}."
        } ?: ""

        return PromptTemplates.BINDING_CONSTRAINT_USER_TEMPLATE
            .replace("{LEG_COUNT}", timelines.size.toString())
            .replace("{LAT}", "%.4f".format(lat))
            .replace("{LON}", "%.4f".format(lon))
            .replace("{DATE}", dateStr)
            .replace("{PRESSURE_CONTEXT}", pressureCtx)
            .replace("{WEATHER_CONTEXT}", weatherCtx)
            .replace("{TIMELINE}", timelineStr)
    }

    suspend fun analyze(
        legs: List<TripLeg>,
        startMs: Long,
        lat: Double,
        lon: Double,
        provider: LlmProvider,
        altitudeM: Double = 0.0,
        sensor: SensorState? = null,
        onlineWeather: OnlineWeather? = null,
        profile: ConstraintProfile? = null
    ): Result<BindingAnalysis> = try {
        val timelines = overlayTimeline(legs, startMs, lat, lon, altitudeM, profile = profile)
        val prompt = buildPrompt(timelines, lat, lon, startMs, sensor, onlineWeather)
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

    companion object {
        private val COASTAL_KEYWORDS = listOf(
            "beach", "shore", "coast", "coastal", "sea", "ocean", "bay", "tide", "tidal",
            "estuary", "lagoon", "cove", "reef", "mangrove", "wade", "crossing"
        )
    }
}
