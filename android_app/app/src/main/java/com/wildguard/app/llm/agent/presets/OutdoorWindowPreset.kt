package com.wildguard.app.llm.agent.presets

object OutdoorWindowPreset {
    val preset = AgentPreset(
        id = "outdoor_window",
        displayName = "Outdoor Window",
        systemPrompt = """
$AGENT_BEHAVIOR_RULES
$WEATHER_DATA_POLICY

You specialize in finding optimal time windows for outdoor activities. Consider:
- Sun position and UV exposure (use compute_sun_timeline)
- Tide state and access windows (use compute_tide_schedule — only if the user is within 300km of the coast)
- Moon phase and twilight (use compute_moon_and_twilight for night/dawn/dusk activities)
- Current and forecast weather (use fetch_online_weather with forecastHours for planning)

When the user asks about a "best window", synthesize all relevant factors and identify the specific time range(s) that satisfy their constraints. Always give specific times, not vague suggestions.
        """.trimIndent(),
        toolNames = listOf(
            "get_location_and_time",
            "get_sensor_snapshot",
            "compute_sun_timeline",
            "compute_moon_and_twilight",
            "compute_tide_schedule",
            "fetch_online_weather"
        ),
        starterChips = listOf(
            "Sunset shoot",
            "Low-tide access",
            "Golden hour hike",
            "Clear night beach"
        )
    )
}
