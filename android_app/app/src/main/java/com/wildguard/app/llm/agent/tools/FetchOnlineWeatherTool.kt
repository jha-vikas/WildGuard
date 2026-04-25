package com.wildguard.app.llm.agent.tools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wildguard.app.llm.agent.Tool
import com.wildguard.app.llm.agent.ToolContext
import com.wildguard.app.modules.weather.WeatherApiClient

class FetchOnlineWeatherTool : Tool {
    override val name = "fetch_online_weather"
    override val description = "Primary weather data source. Fetches current conditions and optional hourly forecast (0-72h) from Open-Meteo (no API key needed). Use forecastHours >= 24 when planning future windows. Falls back gracefully with an error field if offline."
    override val paramsSchema: JsonObject
        get() {
            val props = JsonObject()
            props.add("lat", JsonObject().apply { addProperty("type", "number") })
            props.add("lon", JsonObject().apply { addProperty("type", "number") })
            props.add("forecastHours", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "Hours of hourly forecast to include (0-72, default 0 = current only)")
                addProperty("minimum", 0)
                addProperty("maximum", 72)
            })
            val required = com.google.gson.JsonArray(); required.add("lat"); required.add("lon")
            return JsonObject().apply { addProperty("type", "object"); add("properties", props); add("required", required) }
        }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonElement {
        val lat = args.get("lat")?.asDouble ?: return errorJson("missing 'lat'")
        val lon = args.get("lon")?.asDouble ?: return errorJson("missing 'lon'")
        val forecastHours = args.get("forecastHours")?.asInt ?: 0

        if (forecastHours > 0) {
            return when (val resp = WeatherApiClient.fetchWithForecast(lat, lon, forecastHours)) {
                is WeatherApiClient.ForecastResponse.Success -> {
                    val data = resp.data
                    val result = JsonObject()
                    result.add("current", currentToJson(data.current))
                    val hourlyArr = JsonArray()
                    data.hourly.forEach { pt ->
                        val h = JsonObject()
                        h.addProperty("hour", pt.hour)
                        h.addProperty("tempC", pt.temperatureC)
                        h.addProperty("precipProb", pt.precipitationProbability)
                        h.addProperty("windKmh", pt.windSpeedKmh)
                        h.addProperty("cloudPct", pt.cloudCoverPercent)
                        h.addProperty("pressureHpa", pt.pressureHpa)
                        hourlyArr.add(h)
                    }
                    result.add("hourly", hourlyArr)
                    result
                }
                is WeatherApiClient.ForecastResponse.Error -> errorJson(resp.reason)
            }
        }

        return when (val resp = WeatherApiClient.fetchCurrent(lat, lon)) {
            is WeatherApiClient.Result.Success -> {
                val result = JsonObject()
                result.add("current", currentToJson(resp.data))
                result
            }
            is WeatherApiClient.Result.Error -> errorJson(resp.reason)
        }
    }

    private fun currentToJson(w: com.wildguard.app.modules.weather.OnlineWeather): JsonObject {
        val obj = JsonObject()
        obj.addProperty("tempC", w.temperatureC)
        obj.addProperty("apparentC", w.apparentTempC)
        obj.addProperty("humidity", w.humidityPercent)
        obj.addProperty("windKmh", w.windSpeedKmh)
        obj.addProperty("windDir", w.windDirectionDeg)
        obj.addProperty("pressureHpa", w.pressureHpa)
        obj.addProperty("description", w.description)
        return obj
    }

    private fun errorJson(msg: String) = JsonObject().apply { addProperty("error", msg) }
}
