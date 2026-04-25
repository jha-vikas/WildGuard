package com.wildguard.app.modules.weather

import android.util.Log
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

    sealed class Result {
        data class Success(val data: OnlineWeather) : Result()
        data class Error(val reason: String) : Result()
    }

    /**
     * Fetches current conditions from Open-Meteo (free, no API key required).
     * Returns a [Result] so callers can surface failures (HTTP errors, offline, parse issues).
     */
    suspend fun fetchCurrent(lat: Double, lon: Double): Result =
        withContext(Dispatchers.IO) {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${"%.4f".format(lat)}" +
                "&longitude=${"%.4f".format(lon)}" +
                "&current=temperature_2m,apparent_temperature," +
                "relative_humidity_2m,wind_speed_10m,wind_direction_10m," +
                "surface_pressure,weather_code" +
                "&timezone=auto&forecast_days=1"

            try {
                Log.d(TAG, "Fetching: $url")
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val httpCode = response.code
                val successful = response.isSuccessful
                val body: String = try {
                    if (successful) response.body?.string().orEmpty() else ""
                } finally {
                    response.close()
                }
                if (!successful) {
                    val msg = "HTTP $httpCode"
                    Log.w(TAG, "Fetch failed: $msg")
                    return@withContext Result.Error(msg)
                }
                if (body.isEmpty()) return@withContext Result.Error("Empty response body")

                val cur = JsonParser.parseString(body)
                    .asJsonObject
                    .getAsJsonObject("current")
                    ?: return@withContext Result.Error("Malformed JSON (no 'current')")

                val temp = cur.get("temperature_2m")?.asDouble
                    ?: return@withContext Result.Error("Missing temperature_2m")

                val code = cur.get("weather_code")?.asInt ?: 0
                Log.d(TAG, "Fetch OK: ${temp}°C, code=$code")

                Result.Success(
                    OnlineWeather(
                        temperatureC     = temp,
                        apparentTempC    = cur.get("apparent_temperature")?.asDouble ?: 0.0,
                        humidityPercent  = cur.get("relative_humidity_2m")?.asInt ?: 0,
                        windSpeedKmh     = cur.get("wind_speed_10m")?.asDouble ?: 0.0,
                        windDirectionDeg = cur.get("wind_direction_10m")?.asDouble ?: 0.0,
                        pressureHpa      = cur.get("surface_pressure")?.asDouble ?: 0.0,
                        description      = wmoDescription(code)
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Fetch exception", e)
                Result.Error(e.javaClass.simpleName + ": " + (e.message ?: "network error"))
            }
        }

    data class HourlyForecastPoint(
        val hour: String,
        val temperatureC: Double,
        val precipitationProbability: Int,
        val windSpeedKmh: Double,
        val cloudCoverPercent: Int,
        val pressureHpa: Double,
        val weatherCode: Int
    )

    data class ForecastResult(
        val current: OnlineWeather,
        val hourly: List<HourlyForecastPoint>
    )

    sealed class ForecastResponse {
        data class Success(val data: ForecastResult) : ForecastResponse()
        data class Error(val reason: String) : ForecastResponse()
    }

    suspend fun fetchWithForecast(lat: Double, lon: Double, forecastHours: Int): ForecastResponse =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append("https://api.open-meteo.com/v1/forecast")
                append("?latitude=${"%.4f".format(lat)}")
                append("&longitude=${"%.4f".format(lon)}")
                append("&current=temperature_2m,apparent_temperature,")
                append("relative_humidity_2m,wind_speed_10m,wind_direction_10m,")
                append("surface_pressure,weather_code")
                if (forecastHours > 0) {
                    append("&hourly=temperature_2m,precipitation_probability,")
                    append("wind_speed_10m,cloud_cover,pressure_msl,weather_code")
                    append("&forecast_hours=$forecastHours")
                }
                append("&timezone=auto&forecast_days=1")
            }

            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val body: String = try {
                    if (response.isSuccessful) response.body?.string().orEmpty() else ""
                } finally {
                    response.close()
                }
                if (!response.isSuccessful) {
                    return@withContext ForecastResponse.Error("HTTP ${response.code}")
                }
                if (body.isEmpty()) return@withContext ForecastResponse.Error("Empty response body")

                val json = JsonParser.parseString(body).asJsonObject
                val cur = json.getAsJsonObject("current")
                    ?: return@withContext ForecastResponse.Error("Malformed JSON (no 'current')")
                val temp = cur.get("temperature_2m")?.asDouble
                    ?: return@withContext ForecastResponse.Error("Missing temperature_2m")
                val code = cur.get("weather_code")?.asInt ?: 0

                val currentWeather = OnlineWeather(
                    temperatureC     = temp,
                    apparentTempC    = cur.get("apparent_temperature")?.asDouble ?: 0.0,
                    humidityPercent  = cur.get("relative_humidity_2m")?.asInt ?: 0,
                    windSpeedKmh     = cur.get("wind_speed_10m")?.asDouble ?: 0.0,
                    windDirectionDeg = cur.get("wind_direction_10m")?.asDouble ?: 0.0,
                    pressureHpa      = cur.get("surface_pressure")?.asDouble ?: 0.0,
                    description      = wmoDescription(code)
                )

                val hourlyPoints = mutableListOf<HourlyForecastPoint>()
                val hourlyObj = json.getAsJsonObject("hourly")
                if (hourlyObj != null) {
                    val times = hourlyObj.getAsJsonArray("time")
                    val temps = hourlyObj.getAsJsonArray("temperature_2m")
                    val precip = hourlyObj.getAsJsonArray("precipitation_probability")
                    val winds = hourlyObj.getAsJsonArray("wind_speed_10m")
                    val clouds = hourlyObj.getAsJsonArray("cloud_cover")
                    val pressures = hourlyObj.getAsJsonArray("pressure_msl")
                    val codes = hourlyObj.getAsJsonArray("weather_code")

                    if (times != null) {
                        for (i in 0 until times.size()) {
                            hourlyPoints.add(HourlyForecastPoint(
                                hour = times[i]?.asString ?: "",
                                temperatureC = temps?.get(i)?.asDouble ?: 0.0,
                                precipitationProbability = precip?.get(i)?.asInt ?: 0,
                                windSpeedKmh = winds?.get(i)?.asDouble ?: 0.0,
                                cloudCoverPercent = clouds?.get(i)?.asInt ?: 0,
                                pressureHpa = pressures?.get(i)?.asDouble ?: 0.0,
                                weatherCode = codes?.get(i)?.asInt ?: 0
                            ))
                        }
                    }
                }

                ForecastResponse.Success(ForecastResult(currentWeather, hourlyPoints))
            } catch (e: Exception) {
                ForecastResponse.Error(e.javaClass.simpleName + ": " + (e.message ?: "network error"))
            }
        }

    private const val TAG = "WeatherApiClient"

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
