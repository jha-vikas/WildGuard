package com.wildguard.app.modules.celestial

enum class SkyQuality(val label: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Poor"),
    URBAN("Urban")
}

data class NightSkyAssessment(
    val quality: SkyQuality,
    val milkyWayVisible: Boolean,
    val description: String
)

object NightSkyQuality {

    fun assess(luxReading: Float): NightSkyAssessment {
        return when {
            luxReading < 0.1f -> NightSkyAssessment(
                quality = SkyQuality.EXCELLENT,
                milkyWayVisible = true,
                description = "Pristine dark sky. The Milky Way casts shadows. " +
                    "Zodiacal light and gegenschein visible. Ideal for deep-sky observing."
            )
            luxReading < 1.0f -> NightSkyAssessment(
                quality = SkyQuality.GOOD,
                milkyWayVisible = true,
                description = "Dark sky with minor light pollution on the horizon. " +
                    "Milky Way clearly visible with detailed structure. Great for stargazing."
            )
            luxReading < 10.0f -> NightSkyAssessment(
                quality = SkyQuality.FAIR,
                milkyWayVisible = false,
                description = "Moderate light pollution. Milky Way washed out. " +
                    "Bright stars and planets visible. Constellations discernible."
            )
            luxReading < 100.0f -> NightSkyAssessment(
                quality = SkyQuality.POOR,
                milkyWayVisible = false,
                description = "Significant light pollution. Only bright stars and planets visible. " +
                    "Sky has a noticeable glow."
            )
            else -> NightSkyAssessment(
                quality = SkyQuality.URBAN,
                milkyWayVisible = false,
                description = "Heavy light pollution. Only the Moon, planets, and " +
                    "a handful of the brightest stars are visible."
            )
        }
    }
}
