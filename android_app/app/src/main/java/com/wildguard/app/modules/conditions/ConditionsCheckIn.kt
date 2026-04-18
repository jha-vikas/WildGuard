package com.wildguard.app.modules.conditions

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.wildguard.app.core.model.UserObservations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BeaufortLevel(
    val scale: Int,
    val label: String,
    val description: String,
    val speedRangeKmh: IntRange
)

data class TemperatureCue(
    val rangeC: IntRange,
    val label: String,
    val cue: String
)

data class HumidityCue(
    val label: String,
    val description: String,
    val estimatedPercent: Double
)

/**
 * Manages the user observation check-in flow and distributes observations
 * to all modules via StateFlow.
 */
class ConditionsCheckIn(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _observations = MutableStateFlow(loadCached())
    val observations: StateFlow<UserObservations> = _observations.asStateFlow()

    fun update(obs: UserObservations) {
        _observations.value = obs
        cache(obs)
    }

    fun updateTemperature(tempC: Double) {
        update(_observations.value.copy(temperatureC = tempC, observedAt = System.currentTimeMillis()))
    }

    fun updateHumidity(humidityPercent: Double) {
        update(_observations.value.copy(humidityPercent = humidityPercent, observedAt = System.currentTimeMillis()))
    }

    fun updateWind(beaufortScale: Int, speedKmh: Double, directionDeg: Double?) {
        update(_observations.value.copy(
            beaufortScale = beaufortScale,
            windSpeedKmh = speedKmh,
            windDirectionDeg = directionDeg,
            observedAt = System.currentTimeMillis()
        ))
    }

    fun updateCloudType(cloudType: String) {
        update(_observations.value.copy(cloudType = cloudType, observedAt = System.currentTimeMillis()))
    }

    fun clear() {
        update(UserObservations())
        prefs.edit().remove(KEY_OBSERVATIONS).apply()
    }

    private fun cache(obs: UserObservations) {
        prefs.edit().putString(KEY_OBSERVATIONS, gson.toJson(obs)).apply()
    }

    private fun loadCached(): UserObservations {
        val json = prefs.getString(KEY_OBSERVATIONS, null) ?: return UserObservations()
        return try {
            gson.fromJson(json, UserObservations::class.java) ?: UserObservations()
        } catch (_: Exception) {
            UserObservations()
        }
    }

    companion object {
        private const val PREFS_NAME = "wildguard_conditions"
        private const val KEY_OBSERVATIONS = "last_observations"

        val BEAUFORT_SCALE = listOf(
            BeaufortLevel(0, "Calm", "Smoke rises vertically, leaves still", 0..1),
            BeaufortLevel(1, "Light air", "Smoke drifts, weather vanes inactive", 1..5),
            BeaufortLevel(2, "Light breeze", "Feel wind on face, leaves rustle", 6..11),
            BeaufortLevel(3, "Gentle breeze", "Leaves and small twigs move, flags extend", 12..19),
            BeaufortLevel(4, "Moderate breeze", "Small branches move, dust and paper blow", 20..28),
            BeaufortLevel(5, "Fresh breeze", "Small trees sway, wavelets on lakes", 29..38),
            BeaufortLevel(6, "Strong breeze", "Large branches move, hard to use umbrella", 39..49),
            BeaufortLevel(7, "Near gale", "Whole trees move, hard to walk against", 50..61),
            BeaufortLevel(8, "Gale", "Twigs break, difficult to walk", 62..74),
            BeaufortLevel(9, "Strong gale", "Branches break, shingles blown off", 75..88),
            BeaufortLevel(10, "Storm", "Trees uprooted, significant damage", 89..102),
            BeaufortLevel(11, "Violent storm", "Widespread damage", 103..117),
            BeaufortLevel(12, "Hurricane", "Devastating damage", 118..200)
        )

        val TEMPERATURE_CUES = listOf(
            TemperatureCue(-30..-21, "Extreme cold", "Exposed skin freezes rapidly. Breath forms ice crystals."),
            TemperatureCue(-20..-11, "Very cold", "Breath is very visible. Snow squeaks underfoot."),
            TemperatureCue(-10..-1, "Cold", "Breath clearly visible. Puddles frozen solid."),
            TemperatureCue(0..4, "Near freezing", "Water begins to freeze. Light frost on surfaces."),
            TemperatureCue(5..9, "Cool", "No frost but chilly. Jacket weather."),
            TemperatureCue(10..14, "Mild", "Comfortable with light layers. Morning dew."),
            TemperatureCue(15..19, "Pleasant", "T-shirt weather in sun. Cool in shade."),
            TemperatureCue(20..24, "Warm", "Comfortable in light clothing. Light perspiration with activity."),
            TemperatureCue(25..29, "Hot", "Perspiring at rest in sun. Seeking shade."),
            TemperatureCue(30..34, "Very hot", "Uncomfortable. Heavy perspiration. Heat shimmer visible."),
            TemperatureCue(35..45, "Extreme heat", "Dangerous conditions. Ground burns bare feet.")
        )

        val HUMIDITY_LEVELS = listOf(
            HumidityCue("Very dry", "Lips chapped, skin tight, static sparks", 15.0),
            HumidityCue("Dry", "Comfortable, slight dryness", 30.0),
            HumidityCue("Moderate", "Comfortable, neither dry nor damp", 50.0),
            HumidityCue("Humid", "Slight stickiness, sweat slow to dry", 70.0),
            HumidityCue("Very humid", "Air feels heavy, sweat doesn't evaporate, foggy", 90.0)
        )

        val CLOUD_TYPES = listOf(
            "Clear" to "No clouds visible",
            "Cirrus" to "Thin, wispy, high-altitude streaks",
            "Cirrostratus" to "Thin veil, sun/moon halo",
            "Cirrocumulus" to "Small high ripples (mackerel sky)",
            "Altocumulus" to "Mid-level puffy patches",
            "Altostratus" to "Gray mid-level sheet, dim sun",
            "Stratocumulus" to "Low lumpy gray layer",
            "Stratus" to "Uniform gray low blanket, drizzle",
            "Nimbostratus" to "Dark gray, steady rain/snow",
            "Cumulus" to "Puffy white, flat base (fair weather)",
            "Towering cumulus" to "Tall cumulus, growing upward",
            "Cumulonimbus" to "Thunderstorm cloud, anvil top"
        )
    }
}
