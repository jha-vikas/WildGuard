package com.wildguard.app.llm.insight

import com.google.gson.JsonParser
import com.wildguard.app.llm.prompt.PromptTemplates
import com.wildguard.app.llm.provider.LlmProvider
import com.wildguard.app.modules.celestial.MoonCalculator
import com.wildguard.app.modules.uv.SunPositionCalculator
import java.util.Calendar
import java.util.TimeZone

data class CelestialEvent(
    val name: String,
    val startMs: Long,
    val endMs: Long,
    val azimuthDeg: Double?,
    val description: String
)

sealed class AlignmentTemplate(
    val id: String,
    val label: String,
    val description: String
) {
    data object MilkyWayCoreVisible : AlignmentTemplate(
        "milky_way_core", "Milky Way Core",
        "Galactic center above horizon with minimal moon interference"
    )

    data object AstronomicalDarkness : AlignmentTemplate(
        "astro_darkness", "Astronomical Darkness",
        "Sun >18° below horizon, true dark sky"
    )

    data object GoldenHourBearing : AlignmentTemplate(
        "golden_hour", "Golden Hour",
        "Sun within 6° of horizon, warm directional light"
    )

    data object MoonFreeDarkSky : AlignmentTemplate(
        "moon_free_dark", "Moon-Free Dark Sky",
        "Moon below horizon during astronomical darkness"
    )

    data object PlanetConjunction : AlignmentTemplate(
        "planet_conjunction", "Planet Conjunction",
        "Two or more planets within 5° of each other"
    )

    data object BlueHour : AlignmentTemplate(
        "blue_hour", "Blue Hour",
        "Sun 4-6° below horizon, deep blue ambient light"
    )

    data object CivilTwilight : AlignmentTemplate(
        "civil_twilight", "Civil Twilight",
        "Sun 0-6° below horizon"
    )

    data object NauticalTwilight : AlignmentTemplate(
        "nautical_twilight", "Nautical Twilight",
        "Sun 6-12° below horizon, horizon still visible"
    )

    data object MoonriseAlignment : AlignmentTemplate(
        "moonrise_alignment", "Moonrise",
        "Moon rising, bearing may align with landscape"
    )

    data object FullMoonNight : AlignmentTemplate(
        "full_moon_night", "Full Moon Night",
        "Moon illumination >95%, good for night navigation"
    )

    companion object {
        val ALL = listOf(
            MilkyWayCoreVisible, AstronomicalDarkness, GoldenHourBearing,
            MoonFreeDarkSky, PlanetConjunction, BlueHour,
            CivilTwilight, NauticalTwilight, MoonriseAlignment, FullMoonNight
        )
    }
}

class CelestialAlignmentFinder {

    fun evaluate48h(
        lat: Double,
        lon: Double,
        startMs: Long = System.currentTimeMillis()
    ): List<Pair<AlignmentTemplate, Map<String, Any>>> {
        val fired = mutableListOf<Pair<AlignmentTemplate, Map<String, Any>>>()
        val stepMs = 30L * 60_000L // 30-min steps over 48h
        val steps = 96 // 48h / 30min

        for (template in AlignmentTemplate.ALL) {
            val params = evaluateTemplate(template, lat, lon, startMs, steps, stepMs)
            if (params != null) {
                fired.add(template to params)
            }
        }
        return fired
    }

    fun buildPrompt(
        fired: List<Pair<AlignmentTemplate, Map<String, Any>>>,
        lat: Double,
        lon: Double
    ): String {
        if (fired.isEmpty()) return ""
        val eventsStr = fired.joinToString("\n\n") { (template, params) ->
            val paramsStr = params.entries.joinToString(", ") { "${it.key}=${it.value}" }
            "[${template.label}] ${template.description}\n  params: $paramsStr"
        }

        return PromptTemplates.CELESTIAL_ALIGNMENT_USER_TEMPLATE
            .replace("{EVENTS}", eventsStr)
            .replace("{LAT}", "%.4f".format(lat))
            .replace("{LON}", "%.4f".format(lon))
    }

    suspend fun find(
        lat: Double,
        lon: Double,
        provider: LlmProvider
    ): Result<List<CelestialEvent>> = try {
        val fired = evaluate48h(lat, lon)
        if (fired.isEmpty()) {
            Result.success(emptyList())
        } else {
            val prompt = buildPrompt(fired, lat, lon)
            val response = provider.generate(prompt, PromptTemplates.CELESTIAL_ALIGNMENT_SYSTEM)
            if (response.error != null) {
                Result.failure(Exception(response.error))
            } else {
                Result.success(parseEvents(response.content))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun evaluateTemplate(
        template: AlignmentTemplate,
        lat: Double,
        lon: Double,
        startMs: Long,
        steps: Int,
        stepMs: Long
    ): Map<String, Any>? {
        for (i in 0 until steps) {
            val ms = startMs + i * stepMs
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) + 1
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0

            val sun = SunPositionCalculator.compute(lat, lon, year, month, day, hour)
            val moon = MoonCalculator.compute(lat, lon, ms)

            when (template) {
                is AlignmentTemplate.AstronomicalDarkness -> {
                    if (sun.altitudeDeg < -18.0) {
                        return mapOf("sunEl" to sun.altitudeDeg, "timeMs" to ms, "step" to i)
                    }
                }
                is AlignmentTemplate.GoldenHourBearing -> {
                    if (sun.altitudeDeg in -1.0..6.0) {
                        return mapOf("sunEl" to sun.altitudeDeg, "sunAz" to sun.azimuthDeg, "timeMs" to ms)
                    }
                }
                is AlignmentTemplate.BlueHour -> {
                    if (sun.altitudeDeg in -6.0..-4.0) {
                        return mapOf("sunEl" to sun.altitudeDeg, "sunAz" to sun.azimuthDeg, "timeMs" to ms)
                    }
                }
                is AlignmentTemplate.CivilTwilight -> {
                    if (sun.altitudeDeg in -6.0..0.0) {
                        return mapOf("sunEl" to sun.altitudeDeg, "timeMs" to ms)
                    }
                }
                is AlignmentTemplate.NauticalTwilight -> {
                    if (sun.altitudeDeg in -12.0..-6.0) {
                        return mapOf("sunEl" to sun.altitudeDeg, "timeMs" to ms)
                    }
                }
                is AlignmentTemplate.MilkyWayCoreVisible -> {
                    if (sun.altitudeDeg < -18.0 && moon.illuminationPercent < 25.0) {
                        return mapOf(
                            "moonIllum" to moon.illuminationPercent,
                            "sunEl" to sun.altitudeDeg,
                            "timeMs" to ms
                        )
                    }
                }
                is AlignmentTemplate.MoonFreeDarkSky -> {
                    if (sun.altitudeDeg < -18.0 && moon.altitudeDeg < 0.0) {
                        return mapOf(
                            "moonAlt" to moon.altitudeDeg,
                            "sunEl" to sun.altitudeDeg,
                            "timeMs" to ms
                        )
                    }
                }
                is AlignmentTemplate.MoonriseAlignment -> {
                    if (moon.moonriseUtcHours != null && moon.altitudeDeg in -2.0..5.0 && moon.altitudeDeg > 0) {
                        return mapOf(
                            "moonAz" to moon.azimuthDeg,
                            "moonAlt" to moon.altitudeDeg,
                            "illumination" to moon.illuminationPercent,
                            "timeMs" to ms
                        )
                    }
                }
                is AlignmentTemplate.FullMoonNight -> {
                    if (moon.illuminationPercent > 95.0 && moon.altitudeDeg > 10.0) {
                        return mapOf(
                            "illumination" to moon.illuminationPercent,
                            "moonAlt" to moon.altitudeDeg,
                            "timeMs" to ms
                        )
                    }
                }
                is AlignmentTemplate.PlanetConjunction -> {
                    // Requires ephemeris data beyond sun/moon — skip unless planet data available
                    return null
                }
            }
        }
        return null
    }

    private fun parseEvents(content: String): List<CelestialEvent> = try {
        val jsonStr = content.let {
            val start = it.indexOf('[')
            val end = it.lastIndexOf(']')
            if (start >= 0 && end > start) it.substring(start, end + 1) else "[]"
        }
        val arr = JsonParser.parseString(jsonStr).asJsonArray
        arr.map { elem ->
            val obj = elem.asJsonObject
            CelestialEvent(
                name = obj.get("name")?.asString ?: "unknown",
                startMs = obj.get("startMs")?.asLong ?: 0L,
                endMs = obj.get("endMs")?.asLong ?: 0L,
                azimuthDeg = obj.get("azimuthDeg")?.asDouble,
                description = obj.get("description")?.asString ?: ""
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
