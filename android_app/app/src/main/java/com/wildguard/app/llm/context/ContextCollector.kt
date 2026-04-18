package com.wildguard.app.llm.context

import com.wildguard.app.core.model.SensorState
import com.wildguard.app.core.model.UserObservations
import com.wildguard.app.modules.celestial.MoonCalculator
import com.wildguard.app.modules.celestial.MoonData
import com.wildguard.app.modules.compass.MagneticDeclinationModel
import com.wildguard.app.modules.uv.SunPosition
import com.wildguard.app.modules.uv.SunPositionCalculator
import com.wildguard.app.modules.uv.UVResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ModuleOutputs(
    val sunPosition: SunPosition? = null,
    val uvResult: UVResult? = null,
    val moonData: MoonData? = null,
    val pressureTrendHpaPer3Hr: Double? = null,
    val pressureForecast: String? = null,
    val tideState: TideSnapshot? = null,
    val altitudeBaroM: Double? = null
)

data class TideSnapshot(
    val nextHighTimeUtc: String?,
    val nextHighM: Double?,
    val direction: String?,
    val stationName: String? = null
)

object ContextCollector {

    private val utcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val timeOnlyFormat = SimpleDateFormat("HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun collect(
        sensor: SensorState,
        observations: UserObservations?,
        modules: ModuleOutputs,
        timestampMs: Long = System.currentTimeMillis()
    ): String {
        val sb = StringBuilder()
        sb.appendLine("CONTEXT [${utcFormat.format(Date(timestampMs))}]")

        appendLocation(sb, sensor, modules)
        appendWeather(sb, sensor, modules)
        appendSun(sb, modules.sunPosition)
        appendMoon(sb, modules.moonData)
        appendUV(sb, modules.uvResult)
        appendTide(sb, modules.tideState)
        appendCompass(sb, sensor)
        appendConditions(sb, observations)

        return sb.toString().trimEnd()
    }

    fun collectFromSensors(
        sensor: SensorState,
        observations: UserObservations?,
        timestampMs: Long = System.currentTimeMillis()
    ): String {
        val loc = sensor.location
        var sunPos: SunPosition? = null
        var moonData: MoonData? = null

        if (loc != null) {
            sunPos = SunPositionCalculator.computeForLocalTime(loc.latitude, loc.longitude, timestampMs)
            moonData = MoonCalculator.compute(loc.latitude, loc.longitude, timestampMs)
        }

        val modules = ModuleOutputs(
            sunPosition = sunPos,
            moonData = moonData,
            altitudeBaroM = null
        )

        return collect(sensor, observations, modules, timestampMs)
    }

    private fun appendLocation(sb: StringBuilder, sensor: SensorState, modules: ModuleOutputs) {
        val loc = sensor.location ?: return
        val altSource = if (modules.altitudeBaroM != null) "baro" else "gps"
        val alt = modules.altitudeBaroM ?: loc.altitudeGps
        val altStr = if (alt != null) ", alt ${alt.toInt()}m ($altSource)" else ""
        val latHemi = if (loc.latitude >= 0) "N" else "S"
        val lonHemi = if (loc.longitude >= 0) "E" else "W"
        sb.appendLine(
            "LOC: ${"%.4f".format(kotlin.math.abs(loc.latitude))}$latHemi, " +
                "${"%.4f".format(kotlin.math.abs(loc.longitude))}$lonHemi$altStr"
        )
    }

    private fun appendWeather(sb: StringBuilder, sensor: SensorState, modules: ModuleOutputs) {
        val pressure = sensor.pressureHpa ?: return
        val trendStr = if (modules.pressureTrendHpaPer3Hr != null) {
            val direction = when {
                modules.pressureTrendHpaPer3Hr < -0.5 -> "falling"
                modules.pressureTrendHpaPer3Hr > 0.5 -> "rising"
                else -> "steady"
            }
            ", trend ${"%.1f".format(modules.pressureTrendHpaPer3Hr)}hPa/3hr ($direction)"
        } else ""
        val forecastStr = if (modules.pressureForecast != null) ", forecast: ${modules.pressureForecast}" else ""
        sb.appendLine("WEATHER: ${"%.1f".format(pressure)}hPa$trendStr$forecastStr")
    }

    private fun appendSun(sb: StringBuilder, sun: SunPosition?) {
        if (sun == null) return
        val riseH = sun.sunriseUtc.toInt()
        val riseM = ((sun.sunriseUtc - riseH) * 60).toInt()
        val setH = sun.sunsetUtc.toInt()
        val setM = ((sun.sunsetUtc - setH) * 60).toInt()
        sb.appendLine(
            "SUN: rise %02d:%02d, set %02d:%02d, alt %.0f°, az %.0f°".format(
                riseH, riseM, setH, setM, sun.altitudeDeg, sun.azimuthDeg
            )
        )
    }

    private fun appendMoon(sb: StringBuilder, moon: MoonData?) {
        if (moon == null) return
        val riseStr = if (moon.moonriseUtcHours != null) {
            val h = moon.moonriseUtcHours.toInt()
            val m = ((moon.moonriseUtcHours - h) * 60).toInt()
            ", rise %02d:%02d".format(h, m)
        } else ""
        val wax = if (moon.isWaxing) "waxing" else "waning"
        sb.appendLine(
            "MOON: %.0f%% %s %s%s".format(
                moon.illuminationPercent, wax, moon.phaseName.lowercase(), riseStr
            )
        )
    }

    private fun appendUV(sb: StringBuilder, uv: UVResult?) {
        if (uv == null) return
        val peakEntry = uv.dailyCurve.maxByOrNull { it.second }
        val peakStr = if (peakEntry != null && peakEntry.second > 0) {
            ", peak forecast ${"%.1f".format(peakEntry.second)}@${peakEntry.first}:00"
        } else ""
        sb.appendLine("UV: current ${"%.1f".format(uv.uvIndex)}$peakStr")
    }

    private fun appendTide(sb: StringBuilder, tide: TideSnapshot?) {
        if (tide == null) return
        val station = if (tide.stationName != null) "[${tide.stationName}]" else "[nearest]"
        val high = if (tide.nextHighTimeUtc != null && tide.nextHighM != null) {
            "high ${tide.nextHighTimeUtc}(${tide.nextHighM}m)"
        } else "no data"
        val dir = if (tide.direction != null) ", ${tide.direction}" else ""
        sb.appendLine("TIDE$station: $high$dir")
    }

    private fun appendCompass(sb: StringBuilder, sensor: SensorState) {
        val heading = sensor.compassHeadingDeg ?: return
        val loc = sensor.location
        val declStr = if (loc != null) {
            val decl = MagneticDeclinationModel.getDeclination(loc.latitude, loc.longitude)
            " (declination ${"%.1f".format(decl)}°)"
        } else ""
        sb.appendLine("COMPASS: heading ${"%.0f".format(heading)}° true$declStr")
    }

    private fun appendConditions(sb: StringBuilder, obs: UserObservations?) {
        if (obs == null) return
        val parts = mutableListOf<String>()
        if (obs.temperatureC != null) parts.add("temp ~${obs.temperatureC.toInt()}°C")
        if (obs.humidityPercent != null) {
            val label = when {
                obs.humidityPercent < 30 -> "dry"
                obs.humidityPercent < 60 -> "moderate"
                else -> "humid"
            }
            parts.add("humidity $label")
        }
        if (obs.windDirectionDeg != null && obs.beaufortScale != null) {
            val compass = degToCompass(obs.windDirectionDeg)
            parts.add("wind $compass Beaufort ${obs.beaufortScale}")
        }
        if (obs.isStale) parts.add("(stale)")
        if (parts.isNotEmpty()) {
            sb.appendLine("CONDITIONS: ${parts.joinToString(", ")}")
        }
    }

    private fun degToCompass(deg: Double): String {
        val dirs = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val idx = ((deg + 11.25) / 22.5).toInt() % 16
        return dirs[idx]
    }
}
