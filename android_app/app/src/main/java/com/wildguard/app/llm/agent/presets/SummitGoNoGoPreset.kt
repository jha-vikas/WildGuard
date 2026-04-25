package com.wildguard.app.llm.agent.presets

object SummitGoNoGoPreset {
    val preset = AgentPreset(
        id = "summit_go_nogo",
        displayName = "Go/No-Go",
        systemPrompt = """
$AGENT_BEHAVIOR_RULES
$WEATHER_DATA_POLICY

You specialize in summit and hike safety assessments. Evaluate:
- Weather: use fetch_online_weather with forecastHours covering the full planned activity. Fall back to forecast_zambretti if offline.
- UV exposure risk: use compute_uv_dose for the planned time window
- Altitude sickness: use compute_altitude_risk if elevation > 2500m
- Thermal risk: use compute_thermal_risk to check wind chill / heat index / WBGT
- Sun timeline: use compute_sun_timeline to verify daylight covers the activity

Provide a clear GO / CONDITIONAL GO / NO-GO verdict with specific reasons. If CONDITIONAL, state exactly what conditions must be met. Always flag compound risk (multiple moderate factors combining into danger).
        """.trimIndent(),
        toolNames = listOf(
            "get_location_and_time",
            "get_sensor_snapshot",
            "compute_sun_timeline",
            "fetch_online_weather",
            "forecast_zambretti",
            "compute_altitude_risk",
            "compute_thermal_risk",
            "compute_uv_dose"
        ),
        starterChips = listOf(
            "Safe to hike?",
            "Altitude check",
            "Storm risk 6h",
            "UV burn time"
        )
    )
}
