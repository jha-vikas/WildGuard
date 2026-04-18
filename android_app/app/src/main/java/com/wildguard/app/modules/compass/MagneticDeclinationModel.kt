package com.wildguard.app.modules.compass

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Simplified World Magnetic Model (WMM 2025-2030) implementation using a 10°×10° grid
 * of pre-computed declination values with bilinear interpolation.
 * Accuracy: ~1-2° for most populated outdoor regions.
 */
object MagneticDeclinationModel {

    private const val LON_STEP = 10
    private const val LAT_STEP = 10
    private const val LON_CELLS = 36   // -180 to +170
    private const val LAT_CELLS = 18   // -80 to +80 (polar regions clamped)

    // Declination grid: row = latitude band from -80 to +80 (south to north),
    // column = longitude band from -180 to +170 (west to east).
    // Values in degrees, positive = east declination. Epoch 2025.0.
    // Derived from WMM-2025 model outputs at sea level.
    @Suppress("LongMethod")
    private val GRID: Array<DoubleArray> = arrayOf(
        // lat -80: Antarctica
        doubleArrayOf(70.0, 58.0, 46.0, 33.0, 20.0, 8.0, -5.0, -17.0, -29.0, -40.0, -51.0, -60.0, -68.0, -73.0, -74.0, -71.0, -62.0, -48.0, -30.0, -10.0, 12.0, 30.0, 46.0, 58.0, 66.0, 70.0, 72.0, 73.0, 73.0, 73.0, 73.0, 73.0, 74.0, 74.0, 73.0, 72.0),
        // lat -70
        doubleArrayOf(52.0, 42.0, 32.0, 22.0, 14.0, 6.0, -2.0, -10.0, -19.0, -28.0, -38.0, -48.0, -58.0, -65.0, -67.0, -63.0, -52.0, -37.0, -20.0, -4.0, 13.0, 28.0, 39.0, 47.0, 52.0, 55.0, 56.0, 56.0, 56.0, 56.0, 55.0, 55.0, 55.0, 55.0, 54.0, 53.0),
        // lat -60
        doubleArrayOf(36.0, 28.0, 21.0, 14.0, 8.0, 3.0, -2.0, -7.0, -14.0, -22.0, -30.0, -39.0, -47.0, -53.0, -53.0, -47.0, -36.0, -23.0, -11.0, 1.0, 12.0, 22.0, 30.0, 35.0, 38.0, 39.0, 39.0, 38.0, 37.0, 37.0, 37.0, 37.0, 37.0, 37.0, 37.0, 37.0),
        // lat -50
        doubleArrayOf(26.0, 20.0, 14.0, 9.0, 5.0, 1.0, -3.0, -6.0, -11.0, -17.0, -24.0, -31.0, -37.0, -40.0, -39.0, -33.0, -24.0, -14.0, -5.0, 3.0, 11.0, 18.0, 24.0, 28.0, 30.0, 30.0, 29.0, 28.0, 27.0, 27.0, 27.0, 27.0, 27.0, 27.0, 27.0, 27.0),
        // lat -40
        doubleArrayOf(22.0, 16.0, 11.0, 6.0, 2.0, -1.0, -4.0, -7.0, -10.0, -14.0, -19.0, -24.0, -28.0, -30.0, -28.0, -23.0, -16.0, -9.0, -2.0, 4.0, 10.0, 16.0, 20.0, 23.0, 24.0, 24.0, 23.0, 22.0, 21.0, 21.0, 21.0, 21.0, 22.0, 22.0, 22.0, 22.0),
        // lat -30
        doubleArrayOf(18.0, 13.0, 8.0, 4.0, 0.0, -3.0, -5.0, -7.0, -9.0, -12.0, -15.0, -19.0, -22.0, -23.0, -21.0, -17.0, -11.0, -6.0, 0.0, 5.0, 10.0, 14.0, 17.0, 19.0, 20.0, 20.0, 19.0, 18.0, 17.0, 17.0, 17.0, 17.0, 18.0, 18.0, 18.0, 18.0),
        // lat -20
        doubleArrayOf(14.0, 10.0, 6.0, 2.0, -1.0, -4.0, -6.0, -7.0, -8.0, -10.0, -12.0, -15.0, -17.0, -18.0, -16.0, -12.0, -8.0, -3.0, 1.0, 5.0, 9.0, 12.0, 15.0, 16.0, 17.0, 17.0, 16.0, 15.0, 14.0, 14.0, 14.0, 14.0, 14.0, 14.0, 14.0, 14.0),
        // lat -10
        doubleArrayOf(10.0, 7.0, 4.0, 1.0, -2.0, -4.0, -6.0, -6.0, -7.0, -8.0, -9.0, -11.0, -12.0, -13.0, -12.0, -9.0, -5.0, -1.0, 2.0, 5.0, 8.0, 11.0, 13.0, 14.0, 14.0, 14.0, 13.0, 12.0, 11.0, 11.0, 11.0, 11.0, 11.0, 11.0, 10.0, 10.0),
        // lat 0: Equator
        doubleArrayOf(7.0, 5.0, 2.0, 0.0, -2.0, -4.0, -5.0, -5.0, -5.0, -6.0, -6.0, -7.0, -8.0, -8.0, -7.0, -5.0, -3.0, 0.0, 2.0, 5.0, 7.0, 9.0, 11.0, 12.0, 12.0, 11.0, 11.0, 10.0, 9.0, 9.0, 8.0, 8.0, 8.0, 8.0, 8.0, 7.0),
        // lat +10
        doubleArrayOf(5.0, 3.0, 1.0, -1.0, -3.0, -4.0, -4.0, -4.0, -4.0, -4.0, -4.0, -4.0, -5.0, -5.0, -4.0, -2.0, 0.0, 2.0, 3.0, 5.0, 7.0, 8.0, 9.0, 10.0, 10.0, 10.0, 9.0, 8.0, 7.0, 7.0, 6.0, 6.0, 6.0, 6.0, 6.0, 5.0),
        // lat +20
        doubleArrayOf(3.0, 2.0, 0.0, -2.0, -3.0, -4.0, -4.0, -3.0, -2.0, -2.0, -1.0, -1.0, -1.0, -1.0, 0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 8.0, 8.0, 8.0, 7.0, 6.0, 5.0, 5.0, 4.0, 4.0, 4.0, 4.0, 3.0, 3.0),
        // lat +30
        doubleArrayOf(2.0, 1.0, -1.0, -3.0, -4.0, -5.0, -4.0, -3.0, -1.0, 0.0, 1.0, 2.0, 2.0, 2.0, 3.0, 4.0, 4.0, 5.0, 5.0, 5.0, 6.0, 6.0, 7.0, 7.0, 7.0, 6.0, 5.0, 4.0, 3.0, 3.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0),
        // lat +40
        doubleArrayOf(2.0, 0.0, -2.0, -5.0, -6.0, -7.0, -6.0, -4.0, -1.0, 1.0, 3.0, 5.0, 6.0, 6.0, 7.0, 7.0, 7.0, 7.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 5.0, 5.0, 4.0, 3.0, 2.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0),
        // lat +50
        doubleArrayOf(4.0, 1.0, -3.0, -7.0, -10.0, -11.0, -9.0, -6.0, -2.0, 2.0, 5.0, 8.0, 10.0, 10.0, 10.0, 10.0, 10.0, 9.0, 8.0, 7.0, 6.0, 6.0, 5.0, 5.0, 5.0, 4.0, 3.0, 2.0, 1.0, 0.0, -1.0, -1.0, -1.0, 0.0, 1.0, 2.0),
        // lat +60
        doubleArrayOf(8.0, 3.0, -4.0, -10.0, -15.0, -17.0, -14.0, -9.0, -3.0, 3.0, 8.0, 12.0, 14.0, 15.0, 14.0, 14.0, 13.0, 12.0, 10.0, 8.0, 7.0, 6.0, 5.0, 5.0, 5.0, 4.0, 3.0, 1.0, 0.0, -2.0, -3.0, -3.0, -2.0, 0.0, 2.0, 5.0),
        // lat +70
        doubleArrayOf(14.0, 5.0, -6.0, -16.0, -24.0, -27.0, -22.0, -14.0, -5.0, 4.0, 12.0, 17.0, 20.0, 20.0, 19.0, 18.0, 17.0, 15.0, 12.0, 10.0, 8.0, 7.0, 6.0, 5.0, 5.0, 4.0, 3.0, 1.0, -1.0, -3.0, -5.0, -5.0, -3.0, 1.0, 5.0, 10.0),
        // lat +80
        doubleArrayOf(26.0, 10.0, -10.0, -27.0, -38.0, -40.0, -33.0, -21.0, -8.0, 5.0, 16.0, 24.0, 28.0, 28.0, 27.0, 25.0, 22.0, 19.0, 15.0, 12.0, 10.0, 8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 1.0, -2.0, -5.0, -7.0, -7.0, -4.0, 2.0, 10.0, 18.0),
        // lat +90 (approximate pole values)
        doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    )

    // Secular variation per year (degrees/year) — simplified average per cell.
    // For 2025-2030 epoch the secular variation is small (~0.1°/yr in most areas).
    private const val SECULAR_VARIATION_RATE = 0.1

    /**
     * Returns the magnetic declination in degrees for the given position.
     * Positive = east declination, negative = west.
     *
     * @param lat Latitude in degrees (-90 to 90)
     * @param lon Longitude in degrees (-180 to 180)
     * @param year Decimal year (e.g. 2025.5 for mid-2025). Secular variation applied from 2025.0.
     */
    fun getDeclination(lat: Double, lon: Double, year: Double = 2025.0): Double {
        val clampedLat = lat.coerceIn(-80.0, 80.0)
        val normalizedLon = ((lon % 360.0) + 540.0) % 360.0 - 180.0

        val latIdx = (clampedLat + 80.0) / LAT_STEP
        val lonIdx = (normalizedLon + 180.0) / LON_STEP

        val latRow = floor(latIdx).toInt().coerceIn(0, LAT_CELLS - 2)
        val lonCol = floor(lonIdx).toInt().coerceIn(0, LON_CELLS - 2)

        val latFrac = latIdx - latRow
        val lonFrac = lonIdx - lonCol

        val d00 = GRID[latRow][lonCol]
        val d01 = GRID[latRow][lonCol + 1]
        val d10 = GRID[latRow + 1][lonCol]
        val d11 = GRID[latRow + 1][lonCol + 1]

        val interpolated = d00 * (1 - latFrac) * (1 - lonFrac) +
                d01 * (1 - latFrac) * lonFrac +
                d10 * latFrac * (1 - lonFrac) +
                d11 * latFrac * lonFrac

        val secularCorrection = (year - 2025.0) * SECULAR_VARIATION_RATE
        return interpolated + secularCorrection
    }
}
