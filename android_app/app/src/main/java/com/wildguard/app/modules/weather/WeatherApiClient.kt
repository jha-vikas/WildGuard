package com.wildguard.app.modules.weather

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class OnlineWeather(
    val temperatureC: Double,
    val apparentTempC: Double,
    val humidityPercent: Int,
    val windSpeedKmh: Double,
    val windDirectionDeg: Double,
    val pressureHpa: Double,
    val description: String,
    val fetchedAt: Long = System.currentTimeMillis()
)

object WeatherApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Fetches current conditions from Open-Meteo (free, no API key required).
     * Returns null on any network or parse error — callers must degrade gracefully.
     */
    suspend fun fetchCurrent(lat: Double, lon: Double): OnlineWeather? =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=${"%.4f".format(lat)}" +
                    "&longitude=${"%.4f".format(lon)}" +
                    "&current=temperature_2m,apparent_temperature," +
                    "relative_humidity_2m,wind_speed_10m,wind_direction_10m," +
                    "surface_pressure,weather_code" +
                    "&timezone=auto&forecast_days=1"

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null

                val body = response.body?.string() ?: return@withContext null
                val cur = JsonParser.parseString(body)
                    .asJsonObject
                    .getAsJsonObject("current") ?: return@withContext null

                val code = cur.get("weather_code")?.asInt ?: 0

                OnlineWeather(
                    temperatureC    = cur.get("temperature_2m")?.asDouble ?: return@withContext null,
                    apparentTempC   = cur.get("apparent_temperature")?.asDouble ?: 0.0,
                    humidityPercent = cur.get("relative_humidity_2m")?.asInt ?: 0,
                    windSpeedKmh    = cur.get("wind_speed_10m")?.asDouble ?: 0.0,
                    windDirectionDeg = cur.get("wind_direction_10m")?.asDouble ?: 0.0,
                    pressureHpa     = cur.get("surface_pressure")?.asDouble ?: 0.0,
                    description     = wmoDescription(code)
                )
            } catch (_: Exception) {
                null
            }
        }

    private fun wmoDescription(code: Int): String = when (code) {
        0            -> "Clear sky"
        1            -> "Mainly clear"
        2            -> "Partly cloudy"
        3            -> "Overcast"
        in 45..48    -> "Fog"
        in 51..55    -> "Drizzle"
        in 56..57    -> "Freezing drizzle"
        in 61..65    -> "Rain"
        in 66..67    -> "Freezing rain"
        in 71..77    -> "Snowfall"
        in 80..82    -> "Rain showers"
        in 85..86    -> "Snow showers"
        in 95..96    -> "Thunderstorm"
        in 99..99    -> "Thunderstorm with hail"
        else         -> "Mixed conditions"
    }
}
