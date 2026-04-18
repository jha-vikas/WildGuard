package com.wildguard.app.modules.weather

enum class StormAlertLevel { WATCH, WARNING, SEVERE }

data class StormAlert(
    val level: StormAlertLevel,
    val pressureDropRate: Float,
    val message: String,
    val detectedAt: Long
)

class StormAlertDetector(private val pressureLogger: PressureLogger) {

    fun evaluate(): StormAlert? {
        val change3h = pressureLogger.trend3h ?: return null
        val drop = -change3h
        if (drop < 2f) return null

        val now = System.currentTimeMillis()
        val formatted = "%.1f".format(drop)

        return when {
            drop >= 5f -> StormAlert(
                level = StormAlertLevel.SEVERE,
                pressureDropRate = drop,
                message = "SEVERE: Pressure dropped $formatted hPa in 3h. Seek shelter immediately.",
                detectedAt = now
            )
            drop >= 3f -> StormAlert(
                level = StormAlertLevel.WARNING,
                pressureDropRate = drop,
                message = "WARNING: Pressure dropped $formatted hPa in 3h. Storm approaching.",
                detectedAt = now
            )
            else -> StormAlert(
                level = StormAlertLevel.WATCH,
                pressureDropRate = drop,
                message = "WATCH: Pressure dropped $formatted hPa in 3h. Monitor conditions.",
                detectedAt = now
            )
        }
    }
}
