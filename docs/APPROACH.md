# WildGuard: Technical Approach Document

## 1. Overview

**WildGuard** is a comprehensive offline Android app that turns a smartphone into a complete outdoor companion — UV safety, weather forecasting, altimetry, celestial computation, tide prediction, compass navigation, thermal risk assessment, and AI-powered planning — all without internet connectivity.

**Key design constraints:**
- Zero internet dependency for core features — works in airplane mode
- No ML models — pure algorithmic computation (NOAA, Meeus, Zambretti, harmonic analysis, Pandolf)
- Small footprint — target < 10 MB base APK
- All knowledge embedded as JSON assets (~75 KB total reference data)
- LLM integration is optional, manual, and user-key-based (no WildGuard backend)

```
┌──────────────────────────────────────────────────────────────────────┐
│                        WILDGUARD ANDROID APP                         │
│                                                                      │
│  Sensors              Algorithm Modules              UI              │
│  ┌──────────┐   ┌────────────────────────┐   ┌───────────────────┐  │
│  │ GPS      │──►│ UV Index (NOAA+Beer-L) │──►│ Dashboard         │  │
│  │ Barometer│──►│ Weather (Zambretti)     │   │ UV Screen         │  │
│  │ Light    │──►│ Altitude (Barometric)   │   │ Weather Screen    │  │
│  │ Compass  │──►│ Celestial (Meeus+VSOP) │   │ Altitude Screen   │  │
│  │ Steps    │   │ Tide (Harmonics)        │──►│ Celestial Screen  │  │
│  └──────────┘   │ Compass (WMM)          │   │ Tide Screen       │  │
│                  │ Thermal (NWS/Pandolf)  │   │ Compass Screen    │  │
│  User Input      └────────────────────────┘   │ Thermal Screen    │  │
│  ┌──────────┐                                 │ Conditions Screen │  │
│  │ Temp     │   ┌────────────────────────┐   │ Insight Screen    │  │
│  │ Humidity │──►│ LLM Insight Layer      │──►│ Settings Screen   │  │
│  │ Wind     │   │ (Gemini/OpenAI/Claude) │   └───────────────────┘  │
│  │ Waypoints│   └────────────────────────┘                          │
│  └──────────┘                                 6 Visual Modes        │
│                                               ┌───────────────────┐  │
│  Embedded Data                                │ Standard          │  │
│  ┌──────────┐                                 │ Daylight Contrast │  │
│  │ 9 JSON   │                                 │ Glanceable        │  │
│  │ assets   │                                 │ Night Red         │  │
│  │ ~75 KB   │                                 │ Snow Glare        │  │
│  └──────────┘                                 │ Marine Wet        │  │
│                                               └───────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 2. Architecture

### 2.1 Core Pattern

```
Phone Sensors + Embedded Algorithms + Embedded Reference Data = Real Utility at Zero Network Cost
```

Single-Activity Jetpack Compose architecture with:
- **SensorHub** — central sensor manager exposing `StateFlow<SensorState>`
- **Algorithm Modules** — pure computation, each independent (UV, Weather, Altitude, Celestial, Tide, Compass, Thermal)
- **LLM Insight Layer** — optional AI-powered planning with multi-provider support
- **Compose UI** — 11 screens with 6 adaptive visual modes

### 2.2 Module Architecture

Each module follows the same pattern:
1. **Calculator** — Pure algorithm (no Android dependency), unit-testable
2. **ViewModel** — Connects sensor data to calculator, exposes UI state
3. **Screen** — Compose UI consuming ViewModel state

### 2.3 Sensor Tier Classification

| Tier | Description | Examples |
|------|-------------|---------|
| **Tier 1** | Directly measured by phone sensors | GPS position, pressure, light, heading, steps |
| **Tier 2** | Computed from Tier 1 + algorithms | Sun/moon position, tide height, UV index, declination |
| **Tier 3** | Requires user observation input | Temperature, humidity, wind speed/direction |

Critical: Temperature, humidity, and wind speed/direction cannot be measured by phone sensors. The Conditions Check-In flow collects these via guided user observation.

---

## 3. Modules

### 3.1 UV Index Estimator
- **Algorithm:** NOAA solar position → Beer-Lambert UV estimation with altitude, cloud, ozone, surface corrections
- **Files:** `SunPositionCalculator.kt`, `UVIndexCalculator.kt`
- **Accuracy:** ±1-2 UV index points

### 3.2 Barometric Weather Forecaster
- **Algorithm:** 48h rolling pressure history → Zambretti algorithm (pressure trend + wind direction + season)
- **Files:** `PressureLogger.kt`, `ZambrettiForecaster.kt`, `StormAlertDetector.kt`
- **Accuracy:** ~70-80% for 6-12 hour forecasts

### 3.3 Altimeter & Elevation Tracker
- **Algorithm:** Barometric formula with GPS cross-calibration, cumulative gain/loss tracking, altitude sickness monitoring
- **Files:** `BarometricAltimeter.kt`, `ElevationTracker.kt`, `AltitudeSicknessCalculator.kt`, `BoilingPointCalculator.kt`

### 3.4 Celestial Computer
- **Algorithm:** Jean Meeus (moon), simplified VSOP87 (planets), sidereal time transformations (stars)
- **Files:** `MoonCalculator.kt`, `PlanetCalculator.kt`, `StarCatalog.kt`, `NightSkyQuality.kt`
- **Data:** 50 brightest stars hardcoded, 5 planet orbital elements

### 3.5 Tide Calculator
- **Algorithm:** Fourier series harmonic prediction with 8 constituents (M2, S2, N2, K1, O1, K2, P1, M4)
- **Files:** `TideCalculator.kt`, `TidalStationRepository.kt`
- **Data:** 52 tidal stations worldwide in `tidal_stations.json`
- **Accuracy:** ±5-15 cm at covered stations

### 3.6 Compass & Navigation
- **Algorithm:** WMM magnetic declination, haversine distance, spherical bearing, sun compass verification
- **Files:** `CompassCalculator.kt`, `MagneticDeclinationModel.kt`, `WaypointManager.kt`, `BreadcrumbTracker.kt`, `SunCompassVerifier.kt`

### 3.7 Thermal Risk & Hydration
- **Algorithm:** NWS wind chill, Rothfusz heat index, WBGT estimation, Pandolf metabolic equation
- **Files:** `WindChillCalculator.kt`, `HeatIndexCalculator.kt`, `WBGTCalculator.kt`, `HydrationCalculator.kt`
- **Input:** All primary inputs (temperature, humidity, wind) are user-observed via Conditions Check-In

### 3.8 LLM Insight Layer
- **Multi-provider:** OpenAI-compatible (Gemini, GPT, Groq, Mistral, Ollama), Anthropic, Gemini native
- **5 AI use cases:** Tactical Window Detection, Situational Drift Warning, Celestial Alignment, Sensor Consistency, Binding Constraint Planning
- **Files:** Provider abstraction (`llm/provider/`), Context collector (`llm/context/`), Plan cache (`llm/plan/`), 5 insight analyzers (`llm/insight/`), Prompt templates (`llm/prompt/`)
- **Key storage:** EncryptedSharedPreferences (AES-256, hardware-backed keystore)

---

## 4. Embedded Data Assets

| File | Size | Content |
|------|------|---------|
| `skin_types.json` | ~1 KB | Fitzpatrick I-VI skin types with MED values |
| `uv_safety.json` | ~1 KB | WHO UV categories with recommended actions |
| `ozone_factors.json` | ~1 KB | Seasonal ozone correction by latitude band |
| `tidal_stations.json` | ~39 KB | 52 tidal stations with harmonic constituents |
| `altitude_sickness.json` | ~1 KB | Lake Louise scoring, thresholds, recommendations |
| `beaufort_scale.json` | ~2 KB | Beaufort 0-12 with land and sea descriptions |
| `thermal_tables.json` | ~2 KB | Wind chill, heat index, WBGT thresholds |
| `sea_temperature.json` | ~7 KB | Monthly SST by 10° grid (60 ocean cells) |
| `first_aid_basic.json` | ~20 KB | 20 wilderness first aid scenarios |
| **Total** | **~75 KB** | |

---

## 5. Project Structure

```
WildGuard/
├── docs/
│   └── APPROACH.md                              ← this file
├── android_app/
│   ├── build.gradle.kts                         # AGP 8.7.3 + Kotlin 2.1.0
│   ├── settings.gradle.kts                      # Project name: WildGuard
│   ├── gradle.properties
│   ├── gradlew
│   ├── gradle/wrapper/gradle-wrapper.properties
│   └── app/
│       ├── build.gradle.kts                     # Dependencies
│       ├── proguard-rules.pro
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── res/values/
│           │   ├── themes.xml
│           │   ├── colors.xml
│           │   └── strings.xml
│           ├── assets/                          # 9 JSON data files (~75 KB)
│           └── java/com/wildguard/app/
│               ├── MainActivity.kt
│               ├── WildGuardApp.kt
│               ├── core/
│               │   ├── sensor/
│               │   │   ├── SensorHub.kt         # Central sensor manager
│               │   │   ├── GpsProvider.kt
│               │   │   ├── BarometerProvider.kt
│               │   │   ├── LightSensorProvider.kt
│               │   │   ├── CompassProvider.kt
│               │   │   └── StepCounterProvider.kt
│               │   ├── data/
│               │   │   └── AssetRepository.kt   # JSON asset reader
│               │   └── model/
│               │       └── SensorState.kt       # Shared data models
│               ├── modules/
│               │   ├── uv/
│               │   │   ├── SunPositionCalculator.kt
│               │   │   └── UVIndexCalculator.kt
│               │   ├── weather/
│               │   │   ├── PressureLogger.kt
│               │   │   ├── ZambrettiForecaster.kt
│               │   │   └── StormAlertDetector.kt
│               │   ├── altitude/
│               │   │   ├── BarometricAltimeter.kt
│               │   │   ├── ElevationTracker.kt
│               │   │   ├── AltitudeSicknessCalculator.kt
│               │   │   └── BoilingPointCalculator.kt
│               │   ├── celestial/
│               │   │   ├── MoonCalculator.kt
│               │   │   ├── PlanetCalculator.kt
│               │   │   ├── StarCatalog.kt
│               │   │   └── NightSkyQuality.kt
│               │   ├── tide/
│               │   │   ├── TideCalculator.kt
│               │   │   └── TidalStationRepository.kt
│               │   ├── compass/
│               │   │   ├── CompassCalculator.kt
│               │   │   ├── MagneticDeclinationModel.kt
│               │   │   ├── WaypointManager.kt
│               │   │   ├── BreadcrumbTracker.kt
│               │   │   └── SunCompassVerifier.kt
│               │   ├── thermal/
│               │   │   ├── WindChillCalculator.kt
│               │   │   ├── HeatIndexCalculator.kt
│               │   │   ├── WBGTCalculator.kt
│               │   │   └── HydrationCalculator.kt
│               │   └── conditions/
│               │       └── ConditionsCheckIn.kt
│               ├── llm/
│               │   ├── provider/
│               │   │   ├── LlmProvider.kt
│               │   │   ├── OpenAICompatibleProvider.kt
│               │   │   ├── AnthropicProvider.kt
│               │   │   ├── GeminiNativeProvider.kt
│               │   │   └── ProviderRegistry.kt
│               │   ├── context/
│               │   │   └── ContextCollector.kt
│               │   ├── plan/
│               │   │   ├── TripPlan.kt
│               │   │   ├── PlanCache.kt
│               │   │   └── LocalAlertEngine.kt
│               │   ├── insight/
│               │   │   ├── TacticalWindowDetector.kt
│               │   │   ├── DriftAnalyzer.kt
│               │   │   ├── CelestialAlignmentFinder.kt
│               │   │   ├── SensorConsistencyChecker.kt
│               │   │   └── BindingConstraintPlanner.kt
│               │   └── prompt/
│               │       └── PromptTemplates.kt
│               └── ui/
│                   ├── Navigation.kt
│                   ├── theme/
│                   │   ├── Theme.kt
│                   │   ├── VisualMode.kt
│                   │   └── ModeController.kt
│                   └── screens/
│                       ├── DashboardScreen.kt
│                       ├── UVScreen.kt
│                       ├── WeatherScreen.kt
│                       ├── AltitudeScreen.kt
│                       ├── CelestialScreen.kt
│                       ├── TideScreen.kt
│                       ├── CompassScreen.kt
│                       ├── ThermalScreen.kt
│                       ├── ConditionsCheckInScreen.kt
│                       ├── InsightScreen.kt
│                       ├── SettingsScreen.kt
│                       └── StubScreens.kt
```

---

## 6. How to Build the APK

### 6.1 Prerequisites

- **Android Studio** Ladybug (2024.2) or newer
- **JDK 17** (bundled with Android Studio)
- **Android SDK 35** (install via Android Studio → SDK Manager)
- No physical device required for building (but needed for testing sensors)

### 6.2 Build via Android Studio (Recommended)

1. Open Android Studio
2. **File → Open** → select the `WildGuard/android_app/` folder
3. Wait for Gradle sync to complete (first time downloads ~500 MB of dependencies)
4. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
5. APK will be at `android_app/app/build/outputs/apk/debug/app-debug.apk`

### 6.3 Build via Command Line

```bash
cd android_app

# Debug APK
./gradlew assembleDebug

# Release APK (requires signing config)
./gradlew assembleRelease
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 7. How to Install on Android Phone

### 7.1 Via ADB (Developer Mode)

1. Enable **Developer Options** on your phone:
   - Go to **Settings → About Phone** → tap **Build Number** 7 times
2. Enable **USB Debugging** in Developer Options
3. Connect phone via USB cable
4. Run:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 7.2 Via File Transfer

1. Copy `app-debug.apk` to your phone (USB, email, Google Drive, etc.)
2. On the phone, open the APK file
3. If prompted, allow "Install from unknown sources" for your file manager
4. Tap **Install**

---

## 8. How to Use

### 8.1 First Launch

1. Open **WildGuard** from your app drawer
2. Grant **Location permission** when prompted
3. You'll see the Dashboard with 10 module tiles

### 8.2 Dashboard

Grid of module tiles: UV Index, Weather, Altitude, Sky, Tides, Compass, Thermal, Conditions, AI Insights, Settings. Tap any tile to enter that module.

Visual mode selector in top-right corner — adapts display for different outdoor conditions.

### 8.3 Core Modules

- **UV Index** — real-time UV estimate, safe exposure time, vitamin D timer, daily UV curve
- **Weather** — barometric pressure trend, Zambretti 6-12hr forecast, storm alerts
- **Altitude** — barometric altimeter, elevation gain/loss, altitude sickness risk, boiling point
- **Sky** — moon phase/position, planet visibility, star positions, night sky quality
- **Tides** — harmonic tide prediction for nearest station, 48hr high/low times, tide curve
- **Compass** — true north with declination, waypoints, breadcrumb trail, sun compass verification
- **Thermal** — wind chill, heat index, WBGT, hydration needs (requires Conditions Check-In)

### 8.4 Conditions Check-In

5-step wizard for recording weather observations the phone cannot sense:
1. Temperature (guided estimation with physical cues)
2. Humidity (5-level selector)
3. Wind speed (Beaufort scale with visual descriptions)
4. Wind direction (compass-assisted)
5. Cloud type

Observations are cached and shared with all modules.

### 8.5 AI Insights (Optional)

Requires an LLM API key configured in Settings. Supports Gemini, OpenAI, Anthropic, Groq, Ollama.

Five AI use cases:
1. **Tactical Windows** — find optimal time windows where multiple constraints converge
2. **Drift Warning** — detect converging adverse trends before thresholds breach
3. **Celestial Events** — identify alignment opportunities for photography/navigation
4. **Sensor Check** — cross-validate compass vs sun position vs GPS heading
5. **Binding Constraints** — identify the critical-path constraint in multi-leg plans

### 8.6 Visual Modes

| Mode | When | Display |
|------|------|---------|
| Standard | Default | Dark theme, white text, green accents |
| Daylight | Bright sun (>30k lux) | Pure black + white, max contrast |
| Glanceable | Active hiking | Single giant metric, minimal UI |
| Night Red | After astronomical twilight | All red on black, preserves night vision |
| Snow Glare | Snow/water + bright light | Amber/yellow on black |
| Marine | Kayaking/sailing | Giant metrics, physical button navigation |

---

## 9. APK Size Budget

| Component | Estimated Size |
|-----------|---------------|
| Compose runtime + Kotlin stdlib | ~4.5 MB |
| App code (all modules + LLM + UI) | ~500 KB |
| OkHttp (HTTP client) | ~150 KB |
| EncryptedSharedPreferences | ~50 KB |
| Google Play Services Location | ~1.5 MB |
| Embedded JSON data assets | ~75 KB |
| Resources (themes, strings, icons) | ~100 KB |
| **Total estimated APK** | **~7 MB** |

Well under the 25 MB target. Adding Mapsforge later (~4-5 MB) would reach ~12 MB.

---

## 10. Dependencies

| Dependency | Purpose | Size Impact |
|-----------|---------|-------------|
| Jetpack Compose BOM 2024.12.01 | UI framework | ~4 MB (runtime) |
| Material 3 | Design system | Included in Compose |
| Navigation Compose | Screen navigation | ~100 KB |
| Play Services Location | GPS provider | ~1.5 MB |
| OkHttp 4.12.0 | HTTP for LLM API calls | ~150 KB |
| AndroidX Security Crypto | Encrypted API key storage | ~50 KB |
| Gson 2.11.0 | JSON parsing | ~250 KB |

---

## 11. Sensor Availability & Graceful Degradation

WildGuard detects available sensors at startup and adapts:

| Sensor Missing | Impact | Fallback |
|---------------|--------|----------|
| Barometer | No weather forecast, GPS altimeter only | Cloud guide promoted, GPS altitude with "estimated" badge |
| Light sensor | No UV cloud correction | Assumes clear sky (overestimates UV — safer direction) |
| Compass | No magnetic heading | GPS heading when moving, sun position for direction |
| Step counter | No step-based activity detection | GPS-based distance only |
| GPS | Severely limited | Manual coordinate entry |

---

## 12. Future Enhancements (Not Implemented Yet)

1. **GPX Track Renderer** — render GPS tracks on a coordinate grid (Phase 5)
2. **Mapsforge Integration** — offline vector maps (Phase 8)
3. **Marine Charts** — OpenSeaMap/NOAA ENC charts (Phase 9)
4. **Emergency Toolkit** — SOS flashlight, Morse code, Mayday template (Phase 10)
5. **P2P Data Sharing** — Bluetooth LE weather sharing between nearby users
6. **Home Screen Widgets** — UV, weather, tide at a glance
7. **Wear OS Companion** — key metrics on wrist
