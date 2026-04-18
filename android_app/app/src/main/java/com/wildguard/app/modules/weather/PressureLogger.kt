package com.wildguard.app.modules.weather

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

data class PressureReading(
    val timestampMillis: Long,
    val pressureHpa: Float
)

enum class PressureTrend {
    RAPID_DROP, SLOW_DROP, STEADY, SLOW_RISE, RAPID_RISE
}

class PressureLogger(private val context: Context) {

    private val gson = Gson()
    private val readings = CopyOnWriteArrayList<PressureReading>()
    private val file: File get() = File(context.filesDir, "pressure_history.json")

    init {
        loadFromDisk()
    }

    val currentPressure: Float?
        get() = readings.lastOrNull()?.pressureHpa

    val trend3h: Float?
        get() = computeTrend(THREE_HOURS_MS)

    val trend6h: Float?
        get() = computeTrend(SIX_HOURS_MS)

    val trendClassification: PressureTrend
        get() {
            val change = trend3h ?: return PressureTrend.STEADY
            return when {
                change <= -2f -> PressureTrend.RAPID_DROP
                change < -0.5f -> PressureTrend.SLOW_DROP
                change <= 0.5f -> PressureTrend.STEADY
                change < 2f -> PressureTrend.SLOW_RISE
                else -> PressureTrend.RAPID_RISE
            }
        }

    val history: List<PressureReading>
        get() = readings.toList()

    fun recordReading(pressureHpa: Float) {
        val now = System.currentTimeMillis()
        val last = readings.lastOrNull()
        if (last != null && (now - last.timestampMillis) < SAMPLE_INTERVAL_MS) {
            return
        }
        readings.add(PressureReading(now, pressureHpa))
        trimToMax()
        saveToDisk()
    }

    fun forceRecord(pressureHpa: Float, timestampMillis: Long = System.currentTimeMillis()) {
        readings.add(PressureReading(timestampMillis, pressureHpa))
        trimToMax()
        saveToDisk()
    }

    private fun computeTrend(windowMs: Long): Float? {
        if (readings.size < 2) return null
        val latest = readings.last()
        val cutoff = latest.timestampMillis - windowMs
        val older = readings
            .filter { it.timestampMillis <= cutoff + SAMPLE_INTERVAL_MS * 2 && it.timestampMillis >= cutoff - SAMPLE_INTERVAL_MS * 2 }
            .minByOrNull { kotlin.math.abs(it.timestampMillis - cutoff) }
            ?: return null
        return latest.pressureHpa - older.pressureHpa
    }

    private fun trimToMax() {
        while (readings.size > MAX_READINGS) {
            readings.removeAt(0)
        }
    }

    private fun loadFromDisk() {
        try {
            val f = file
            if (f.exists()) {
                val json = f.readText()
                val type = object : TypeToken<List<PressureReading>>() {}.type
                val loaded: List<PressureReading> = gson.fromJson(json, type) ?: emptyList()
                readings.clear()
                readings.addAll(loaded)
                trimToMax()
            }
        } catch (_: Exception) {
            // Corrupted file — start fresh
        }
    }

    private fun saveToDisk() {
        try {
            file.writeText(gson.toJson(readings.toList()))
        } catch (_: Exception) {
            // Best-effort persistence
        }
    }

    companion object {
        const val MAX_READINGS = 288
        const val SAMPLE_INTERVAL_MS = 10 * 60 * 1000L
        private const val THREE_HOURS_MS = 3 * 3_600_000L
        private const val SIX_HOURS_MS = 6 * 3_600_000L
    }
}
