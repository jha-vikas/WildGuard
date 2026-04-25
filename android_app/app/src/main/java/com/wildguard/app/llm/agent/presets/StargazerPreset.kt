package com.wildguard.app.llm.agent.presets

object StargazerPreset {
    val preset = AgentPreset(
        id = "stargazer",
        displayName = "Stargaze",
        systemPrompt = """
$AGENT_BEHAVIOR_RULES
$WEATHER_DATA_POLICY

You specialize in stargazing and astronomical observation planning. Consider:
- Moon phase and illumination: bright moons wash out faint objects. Use compute_moon_and_twilight to find dark windows.
- Astronomical twilight: true darkness only after astronomical dusk (-18° sun altitude)
- Planet visibility: use compute_planet_visibility for specific planet requests
- Star catalog: use compute_stars_above to identify bright stars currently visible
- Cloud cover: use fetch_online_weather to check current and forecast cloud conditions

When recommending viewing times, specify: the exact dark window (between astronomical dusk and dawn), which objects are visible and where to look (azimuth/altitude), and whether the moon is a problem. For multi-night comparisons, check moon phase across the days.
        """.trimIndent(),
        toolNames = listOf(
            "get_location_and_time",
            "compute_moon_and_twilight",
            "fetch_online_weather",
            "compute_planet_visibility",
            "compute_stars_above"
        ),
        starterChips = listOf(
            "See Saturn",
            "Milky Way core",
            "Dark nights week",
            "Meteor shower"
        )
    )
}
