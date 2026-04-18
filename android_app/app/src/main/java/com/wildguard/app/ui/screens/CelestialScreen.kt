package com.wildguard.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildguard.app.WildGuardApp
import com.wildguard.app.modules.celestial.*
import kotlinx.coroutines.launch
import kotlin.math.*

data class CelestialUiState(
    val moon: MoonData? = null,
    val planets: List<PlanetData> = emptyList(),
    val visibleStars: List<StarData> = emptyList(),
    val navigationStars: List<StarData> = emptyList(),
    val polaris: StarData? = null,
    val skyAssessment: NightSkyAssessment? = null,
    val goldenHourStart: String? = null,
    val goldenHourEnd: String? = null,
    val blueHourStart: String? = null,
    val blueHourEnd: String? = null,
    val isLoaded: Boolean = false
)

class CelestialViewModel : ViewModel() {

    var uiState by mutableStateOf(CelestialUiState())
        private set

    private val sensorHub = WildGuardApp.instance.sensorHub
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastUpdateMs = 0L

    init {
        viewModelScope.launch {
            sensorHub.state.collect { sensor ->
                val loc = sensor.location ?: return@collect
                val now = System.currentTimeMillis()
                val moved = lastLat.isNaN() ||
                    abs(loc.latitude - lastLat) > 0.005 ||
                    abs(loc.longitude - lastLon) > 0.005
                val elapsed = now - lastUpdateMs > 60_000L
                if (!moved && !elapsed) return@collect

                lastLat = loc.latitude
                lastLon = loc.longitude
                lastUpdateMs = now
                update(loc.latitude, loc.longitude, now, sensor.lightLux)
            }
        }
    }

    fun update(lat: Double, lon: Double, utcMillis: Long, luxReading: Float?) {
        val moon = MoonCalculator.compute(lat, lon, utcMillis)
        val planets = PlanetCalculator.computeAll(lat, lon, utcMillis)
        val visibleStars = StarCatalog.getVisibleAbove(lat, lon, utcMillis)
        val navStars = StarCatalog.getNavigationStars(lat, lon, utcMillis)
        val polaris = StarCatalog.findPolaris(lat, lon, utcMillis)
        val skyAssessment = luxReading?.let { NightSkyQuality.assess(it) }

        val sunTimes = computeTwilightTimes(lat, lon, utcMillis)

        uiState = CelestialUiState(
            moon = moon,
            planets = planets,
            visibleStars = visibleStars,
            navigationStars = navStars,
            polaris = polaris,
            skyAssessment = skyAssessment,
            goldenHourStart = sunTimes.goldenHourStart,
            goldenHourEnd = sunTimes.goldenHourEnd,
            blueHourStart = sunTimes.blueHourStart,
            blueHourEnd = sunTimes.blueHourEnd,
            isLoaded = true
        )
    }

    private data class TwilightTimes(
        val goldenHourStart: String?,
        val goldenHourEnd: String?,
        val blueHourStart: String?,
        val blueHourEnd: String?
    )

    private fun computeTwilightTimes(lat: Double, lon: Double, utcMillis: Long): TwilightTimes {
        val jd = MoonCalculator.toJulianDay(utcMillis)
        val jdMidnight = floor(jd - 0.5) + 0.5

        var sunsetHour: Double? = null
        var goldenStart: Double? = null
        var blueStart: Double? = null
        var blueEnd: Double? = null

        fun sunAltAt(h: Double): Double {
            val jdH = jdMidnight + h / 24.0
            val T = (jdH - 2451545.0) / 36525.0
            val M = ((357.5291 + 35999.0503 * T) % 360.0 + 360.0) % 360.0
            val C = 1.9146 * sin(Math.toRadians(M)) + 0.02 * sin(Math.toRadians(2 * M))
            val sunLon = ((M + C + 180.0 + 102.9372) % 360.0 + 360.0) % 360.0
            val obliquity = 23.4393 - 0.0130 * T
            val ra = Math.toDegrees(
                atan2(
                    sin(Math.toRadians(sunLon)) * cos(Math.toRadians(obliquity)),
                    cos(Math.toRadians(sunLon))
                )
            )
            val dec = Math.toDegrees(
                asin(sin(Math.toRadians(sunLon)) * sin(Math.toRadians(obliquity)))
            )
            val gmst = MoonCalculator.greenwichMeanSiderealTime(jdH)
            val lst = gmst + lon
            val (alt, _) = MoonCalculator.equatorialToHorizontal(
                ((ra % 360.0) + 360.0) % 360.0, dec, lat, lst
            )
            return alt
        }

        // Scan afternoon/evening (hours 12-24) for golden/blue hour
        var prevAlt = sunAltAt(12.0)
        for (m in (12 * 60 + 1)..(24 * 60)) {
            val h = m / 60.0
            val alt = sunAltAt(h)

            if (prevAlt > 0 && alt <= 0 && sunsetHour == null) {
                sunsetHour = h
            }
            // Golden hour: sun between 6° and 0° (evening)
            if (prevAlt > 6 && alt <= 6 && goldenStart == null) {
                goldenStart = h
            }
            // Blue hour: sun between -4° and -6° (evening)
            if (prevAlt > -4 && alt <= -4 && blueStart == null) {
                blueStart = h
            }
            if (prevAlt > -6 && alt <= -6 && blueEnd == null) {
                blueEnd = h
            }
            prevAlt = alt
        }

        return TwilightTimes(
            goldenHourStart = goldenStart?.let { formatUtcHour(it) },
            goldenHourEnd = sunsetHour?.let { formatUtcHour(it) },
            blueHourStart = blueStart?.let { formatUtcHour(it) },
            blueHourEnd = blueEnd?.let { formatUtcHour(it) }
        )
    }
}

private fun formatUtcHour(h: Double): String {
    val hh = h.toInt() % 24
    val mm = ((h - h.toInt()) * 60).toInt()
    return "%02d:%02d UTC".format(hh, mm)
}

@Composable
fun CelestialScreen(navController: NavController, vm: CelestialViewModel = viewModel()) {
    val state = vm.uiState

    // GPS-based updates are handled by the ViewModel's sensor collection

    ModuleScaffold(title = "Night Sky", navController = navController) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!state.isLoaded) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                return@ModuleScaffold
            }

            state.moon?.let { MoonSection(it) }
            PlanetSection(state.planets)
            StarSection(state.visibleStars, state.navigationStars, state.polaris)
            state.skyAssessment?.let { SkyQualitySection(it) }
            TwilightSection(state)
            SkyMapSection(state)
        }
    }
}

@Composable
private fun MoonSection(moon: MoonData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = moonPhaseEmoji(moon.phaseName),
                    style = MaterialTheme.typography.displayMedium
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(moon.phaseName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "%.1f%% illuminated".format(moon.illuminationPercent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            HorizontalDivider()

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoColumn("Altitude", "%.1f\u00B0".format(moon.altitudeDeg))
                InfoColumn("Azimuth", "%.1f\u00B0".format(moon.azimuthDeg))
                InfoColumn("Age", "%.1f days".format(moon.ageInDays))
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoColumn("Moonrise", moon.moonriseUtcHours?.let { formatUtcHour(it) } ?: "—")
                InfoColumn("Moonset", moon.moonsetUtcHours?.let { formatUtcHour(it) } ?: "—")
                InfoColumn("Phase", if (moon.isWaxing) "Waxing" else "Waning")
            }
        }
    }
}

@Composable
private fun PlanetSection(planets: List<PlanetData>) {
    val visible = planets.filter { it.isVisible }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Planets", style = MaterialTheme.typography.titleMedium)

            if (visible.isEmpty()) {
                Text(
                    "No planets currently visible",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            } else {
                visible.forEach { planet ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(planet.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Alt %.1f\u00B0  Az %.1f\u00B0  Mag %.1f".format(
                                planet.altitudeDeg, planet.azimuthDeg, planet.magnitude
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            val belowHorizon = planets.filter { !it.isVisible && it.riseUtcHours != null }
            if (belowHorizon.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    "Rising later",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                belowHorizon.forEach { planet ->
                    Text(
                        "${planet.name} rises ${planet.riseUtcHours?.let { formatUtcHour(it) } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StarSection(
    visibleStars: List<StarData>,
    navStars: List<StarData>,
    polaris: StarData?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stars", style = MaterialTheme.typography.titleMedium)
            Text(
                "${visibleStars.size} bright stars above horizon",
                style = MaterialTheme.typography.bodyMedium
            )

            if (polaris != null) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("\u2B50 ", style = MaterialTheme.typography.bodyLarge)
                    Column {
                        Text("Polaris (North Star)", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Alt %.1f\u00B0  Az %.1f\u00B0 — True North reference".format(
                                polaris.altitudeDeg, polaris.azimuthDeg
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            if (navStars.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    "Navigation Stars",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                navStars.take(6).forEach { star ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(star.name, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${star.constellation}  Alt %.1f\u00B0".format(star.altitudeDeg),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkyQualitySection(assessment: NightSkyAssessment) {
    val qualityColor = when (assessment.quality) {
        SkyQuality.EXCELLENT -> Color(0xFF66BB6A)
        SkyQuality.GOOD -> Color(0xFF81C784)
        SkyQuality.FAIR -> Color(0xFFFFA726)
        SkyQuality.POOR -> Color(0xFFEF5350)
        SkyQuality.URBAN -> Color(0xFFB71C1C)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Night Sky Quality", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = qualityColor,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        assessment.quality.label,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
            Text(
                assessment.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            if (assessment.milkyWayVisible) {
                Text(
                    "\uD83C\uDF0C Milky Way visible!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF90CAF9)
                )
            }
        }
    }
}

@Composable
private fun TwilightSection(state: CelestialUiState) {
    val hasData = state.goldenHourStart != null || state.blueHourStart != null
    if (!hasData) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Twilight & Golden Hour", style = MaterialTheme.typography.titleMedium)

            if (state.goldenHourStart != null && state.goldenHourEnd != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("\uD83C\uDF05 Golden Hour", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${state.goldenHourStart} – ${state.goldenHourEnd}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFFA726)
                    )
                }
            }

            if (state.blueHourStart != null && state.blueHourEnd != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("\uD83D\uDD35 Blue Hour", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${state.blueHourStart} – ${state.blueHourEnd}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF42A5F5)
                    )
                }
            }
        }
    }
}

@Composable
private fun SkyMapSection(state: CelestialUiState) {
    val visiblePlanets = state.planets.filter { it.isVisible }
    val brightStars = state.visibleStars.take(15)
    val textMeasurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val planetColor = Color(0xFFFFA726)
    val starColor = Color(0xFFE0E0E0)
    val moonColor = Color(0xFFFFF9C4)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Sky Map", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val radius = minOf(cx, cy) * 0.9f

                drawCircle(color = gridColor, radius = radius, center = Offset(cx, cy), style = Stroke(1f))
                drawCircle(color = gridColor, radius = radius * 0.66f, center = Offset(cx, cy), style = Stroke(0.5f))
                drawCircle(color = gridColor, radius = radius * 0.33f, center = Offset(cx, cy), style = Stroke(0.5f))

                drawLine(gridColor, Offset(cx, cy - radius), Offset(cx, cy + radius), 0.5f)
                drawLine(gridColor, Offset(cx - radius, cy), Offset(cx + radius, cy), 0.5f)

                val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
                drawText(textMeasurer, "N", Offset(cx - 5, cy - radius - 14), style = labelStyle)
                drawText(textMeasurer, "S", Offset(cx - 4, cy + radius + 2), style = labelStyle)
                drawText(textMeasurer, "E", Offset(cx + radius + 4, cy - 6), style = labelStyle)
                drawText(textMeasurer, "W", Offset(cx - radius - 16, cy - 6), style = labelStyle)

                fun altAzToXY(altDeg: Double, azDeg: Double): Offset {
                    val r = radius * (1.0 - altDeg / 90.0).toFloat()
                    val azRad = Math.toRadians(azDeg)
                    val x = cx + r * sin(azRad).toFloat()
                    val y = cy - r * cos(azRad).toFloat()
                    return Offset(x, y)
                }

                state.moon?.let { moon ->
                    if (moon.altitudeDeg > 0) {
                        val pos = altAzToXY(moon.altitudeDeg, moon.azimuthDeg)
                        drawCircle(color = moonColor, radius = 8f, center = pos)
                    }
                }

                visiblePlanets.forEach { planet ->
                    val pos = altAzToXY(planet.altitudeDeg, planet.azimuthDeg)
                    val dotSize = (6f - planet.magnitude.toFloat()).coerceIn(3f, 7f)
                    drawCircle(color = planetColor, radius = dotSize, center = pos)
                }

                brightStars.forEach { star ->
                    if (star.altitudeDeg > 0) {
                        val pos = altAzToXY(star.altitudeDeg, star.azimuthDeg)
                        val dotSize = (4f - star.magnitude.toFloat()).coerceIn(1.5f, 4f)
                        drawCircle(color = starColor, radius = dotSize, center = pos)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                LegendDot(color = moonColor, label = "Moon")
                LegendDot(color = planetColor, label = "Planets")
                LegendDot(color = starColor, label = "Stars")
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = color, radius = size.minDimension / 2)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun InfoColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun moonPhaseEmoji(phaseName: String): String = when (phaseName) {
    "New Moon" -> "\uD83C\uDF11"
    "Waxing Crescent" -> "\uD83C\uDF12"
    "First Quarter" -> "\uD83C\uDF13"
    "Waxing Gibbous" -> "\uD83C\uDF14"
    "Full Moon" -> "\uD83C\uDF15"
    "Waning Gibbous" -> "\uD83C\uDF16"
    "Last Quarter" -> "\uD83C\uDF17"
    "Waning Crescent" -> "\uD83C\uDF18"
    else -> "\uD83C\uDF11"
}
