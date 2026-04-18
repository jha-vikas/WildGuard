# WildGuard

**Offline Android outdoor companion — UV safety, weather, altimetry, celestial, tides, compass, and thermal risk. No internet required.**

WildGuard turns your smartphone into a complete backcountry instrument. It uses your phone's built-in sensors (GPS, barometer, light, compass) combined with embedded reference data and pure algorithmic computation to deliver real utility with zero network dependency — fully functional in airplane mode, deep wilderness, or at sea.

No ML models. No cloud. No tracking. Everything runs on-device.

## Features

- **UV Index** — real-time UV estimate from solar position, altitude, cloud cover, and seasonal ozone; safe exposure timer by skin type and SPF; vitamin D timer; hourly UV curve
- **Barometric Weather** — 48-hour rolling pressure history drives a Zambretti 6–12 hour forecast; storm alert detection from rapid pressure drops
- **Altimeter** — barometric altitude with GPS cross-calibration; cumulative elevation gain/loss; altitude sickness risk scoring; water boiling point
- **Celestial Computer** — moon phase and position (Jean Meeus); planet visibility (VSOP87); 50-star catalog with current sky positions; night sky quality index
- **Tide Calculator** — harmonic tide prediction at 52 worldwide stations using 8 Fourier constituents; 48-hour high/low schedule; tide curve
- **Compass and Navigation** — true north with WMM magnetic declination; waypoint manager; breadcrumb trail; sun compass cross-verification
- **Thermal Risk** — NWS wind chill, Rothfusz heat index, WBGT estimation, Pandolf metabolic hydration equation
- **Conditions Check-In** — guided 5-step wizard to record temperature, humidity, wind speed, wind direction, and cloud type (inputs the phone cannot sense)
- **AI Insights** — optional LLM layer for tactical window detection, drift warnings, celestial alignment, sensor consistency checks, and binding constraint planning (requires your own API key: Gemini, OpenAI, Anthropic, Groq, or Ollama)
- **6 Visual Modes** — Standard, Daylight Contrast, Glanceable, Night Red, Snow Glare, Marine Wet

## How It Works

```
GPS + Clock     ──►  NOAA solar position  ──►  UV index, celestial positions
Barometer       ──►  Zambretti algorithm  ──►  Weather forecast, altitude
Light Sensor    ──►  Cloud cover proxy    ──►  UV cloud correction
Compass         ──►  WMM declination      ──►  True north, bearing
User Input      ──►  Conditions Check-In  ──►  Thermal risk, weather context
Embedded Data   ──►  52 tidal stations    ──►  Harmonic tide prediction
                     9 JSON assets ~75 KB
```

All algorithms are pure math — no network calls, no trained models. See [docs/APPROACH.md](docs/APPROACH.md) for full technical details.

## Screenshots

*Coming soon — build the APK and try it on your phone.*

## Quick Start

### Prerequisites

- Android Studio Ladybug (2024.2) or newer
- JDK 17 (bundled with Android Studio)
- Android SDK 35
- An Android phone (API 26+ / Android 8.0+) for sensor testing

### Build

```bash
git clone <repo-url> WildGuard
cd WildGuard/android_app

# Debug APK
./gradlew assembleDebug
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

Or open `WildGuard/android_app/` in Android Studio and use **Build > Build APK(s)**.

### Install

```bash
# Via ADB (fastest)
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to your phone and open it directly (enable "Install from unknown sources" if prompted).

### First Launch

1. Grant **Location** permission when prompted
2. The Dashboard shows all 10 module tiles
3. Tap **Conditions** first to log temperature, humidity, and wind — this feeds Thermal and Weather
4. Open **Settings** to configure skin type, SPF, and optional LLM API key

## Project Structure

```
WildGuard/
├── docs/APPROACH.md              # Full technical approach, algorithms, build guide
├── android_app/
│   ├── build.gradle.kts          # AGP 8.7.3 + Kotlin 2.1.0
│   ├── settings.gradle.kts
│   └── app/
│       ├── build.gradle.kts      # Dependencies
│       └── src/main/
│           ├── assets/           # 9 JSON knowledge bases (~75 KB total)
│           └── java/com/wildguard/app/
│               ├── core/         # SensorHub, AssetRepository, SensorState
│               ├── modules/      # uv, weather, altitude, celestial, tide,
│               │                 # compass, thermal, conditions
│               ├── llm/          # Provider abstraction, insight analyzers,
│               │                 # context collector, plan cache
│               └── ui/           # Navigation, 6 visual modes, 11 screens
```

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Navigation | Compose Navigation |
| Sun/Moon/Planets | NOAA + Jean Meeus + VSOP87 (pure math) |
| Weather | Zambretti algorithm (pressure trend) |
| Tides | Fourier harmonic analysis (8 constituents) |
| Compass | WMM magnetic declination model |
| Thermal | NWS wind chill, Rothfusz, Pandolf |
| LLM | Multi-provider (OpenAI-compat, Anthropic, Gemini) |
| Sensors | Android SensorManager + FusedLocationProvider |
| Key Storage | EncryptedSharedPreferences (AES-256) |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 |
| APK size | ~7 MB |

## Embedded Data

| File | Content |
|------|---------|
| `tidal_stations.json` | 52 stations with harmonic constituents (~39 KB) |
| `first_aid_basic.json` | 20 wilderness first aid scenarios (~20 KB) |
| `sea_temperature.json` | Monthly SST by 10-degree grid (~7 KB) |
| `beaufort_scale.json` | Beaufort 0-12 descriptions (~2 KB) |
| `thermal_tables.json` | Wind chill, heat index, WBGT thresholds (~2 KB) |
| `skin_types.json` | Fitzpatrick I–VI skin types with MED values (~1 KB) |
| `uv_safety.json` | WHO UV categories and actions (~1 KB) |
| `ozone_factors.json` | Seasonal ozone correction by latitude band (~1 KB) |
| `altitude_sickness.json` | Lake Louise scoring and thresholds (~1 KB) |

## Sensor Graceful Degradation

| Missing Sensor | Fallback |
|----------------|---------|
| Barometer | GPS altitude only; weather forecast disabled |
| Light sensor | Assumes clear sky (UV overestimated — safer direction) |
| Compass | GPS heading when moving; sun position for direction |
| Step counter | GPS-based distance only |
| GPS | Manual coordinate entry |

## Accuracy

All estimates are algorithmic approximations:

- UV: within ±1–2 index points
- Weather: ~70–80% for 6–12 hour forecasts
- Tides: ±5–15 cm at covered stations
- Altitude: ±5–10 m with barometric + GPS fusion

For safety-critical decisions, do not rely solely on this app.

## License

Copyright (c) 2026 Vikas Jha. All rights reserved.

Source available under the [Business Source License 1.1](LICENSE).

- **Personal and non-commercial use** — free. Run it, study it, modify it for private use.
- **Commercial use** — requires a separate license from the author.
- **Change Date: April 17, 2029** — on this date the license converts to Apache 2.0 and all restrictions lift.

By submitting a pull request you agree that your contributions may be used and commercialized under these same terms.

## Acknowledgments

- Sun position algorithm based on the [NOAA Solar Calculator](https://gml.noaa.gov/grad/solcalc/)
- Moon and planetary positions follow Jean Meeus, *Astronomical Algorithms* (2nd ed.)
- Tide prediction uses harmonic analysis as described by NOAA CO-OPS
- Weather forecasting uses the [Zambretti algorithm](https://www.weather.gov/media/epz/wxcalc/zambretti.pdf)
- Thermal indices follow [NWS wind chill](https://www.weather.gov/safety/cold-wind-chill-chart) and Rothfusz heat index equations
- Magnetic declination uses the [World Magnetic Model (WMM)](https://www.ngdc.noaa.gov/geomag/WMM/)
- UV index categorization follows [WHO UV Index guidelines](https://www.who.int/news-room/questions-and-answers/item/radiation-the-ultraviolet-(uv)-index)
- Skin type classification uses the [Fitzpatrick scale](https://en.wikipedia.org/wiki/Fitzpatrick_scale)
