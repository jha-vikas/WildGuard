package com.wildguard.app.llm.plan

import com.wildguard.app.core.model.SensorState
import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs

data class FiredAlert(
    val alert: TriggeredAlert,
    val currentValue: Double,
    val firedAt: Long
)

data class FiredDecisionPoint(
    val decisionPoint: DecisionPoint,
    val firedAt: Long
)

class LocalAlertEngine(
    private val checkIntervalMs: Long = 5 * 60_000L
) {
    private var timer: Timer? = null
    private var onAlertFired: ((FiredAlert) -> Unit)? = null
    private var onDecisionFired: ((FiredDecisionPoint) -> Unit)? = null

    fun start(
        planProvider: () -> TripPlan?,
        sensorProvider: () -> SensorState,
        onAlert: (FiredAlert) -> Unit,
        onDecision: (FiredDecisionPoint) -> Unit
    ) {
        onAlertFired = onAlert
        onDecisionFired = onDecision
        stop()
        timer = Timer("LocalAlertEngine", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val plan = planProvider() ?: return
                    val sensor = sensorProvider()
                    val now = System.currentTimeMillis()
                    checkAlerts(plan, sensor, now)
                    checkDecisionPoints(plan, sensor, now)
                }
            }, 0L, checkIntervalMs)
        }
    }

    fun stop() {
        timer?.cancel()
        timer = null
    }

    fun evaluate(plan: TripPlan, sensor: SensorState): List<FiredAlert> {
        val now = System.currentTimeMillis()
        return plan.alerts.mapNotNull { alert ->
            val current = readSensorValue(alert.sensorType, sensor) ?: return@mapNotNull null
            if (evaluateCondition(alert.condition, current, alert.threshold)) {
                FiredAlert(alert = alert, currentValue = current, firedAt = now)
            } else null
        }
    }

    fun evaluateDecisionPoints(
        plan: TripPlan,
        sensor: SensorState
    ): List<FiredDecisionPoint> {
        val now = System.currentTimeMillis()
        val loc = sensor.location
        return plan.decisionPoints.mapNotNull { dp ->
            val timeTrigger = dp.triggerTimeMs?.let { now >= it } ?: false
            val locTrigger = if (dp.triggerLat != null && dp.triggerLon != null && loc != null) {
                val dLat = loc.latitude - dp.triggerLat
                val dLon = loc.longitude - dp.triggerLon
                val distApprox = Math.sqrt(dLat * dLat + dLon * dLon) * 111_000
                distApprox < 200 // within 200m
            } else false

            if (timeTrigger || locTrigger) {
                FiredDecisionPoint(decisionPoint = dp, firedAt = now)
            } else null
        }
    }

    private fun checkAlerts(plan: TripPlan, sensor: SensorState, now: Long) {
        for (alert in plan.alerts) {
            val current = readSensorValue(alert.sensorType, sensor) ?: continue
            if (evaluateCondition(alert.condition, current, alert.threshold)) {
                onAlertFired?.invoke(FiredAlert(alert = alert, currentValue = current, firedAt = now))
            }
        }
    }

    private fun checkDecisionPoints(plan: TripPlan, sensor: SensorState, now: Long) {
        for (dp in evaluateDecisionPoints(plan, sensor)) {
            onDecisionFired?.invoke(dp)
        }
    }

    private fun readSensorValue(sensorType: String, sensor: SensorState): Double? = when (sensorType.lowercase()) {
        "pressure", "barometer" -> sensor.pressureHpa?.toDouble()
        "light", "lux" -> sensor.lightLux?.toDouble()
        "compass", "heading" -> sensor.compassHeadingDeg?.toDouble()
        "altitude", "alt" -> sensor.location?.altitudeGps
        "speed" -> sensor.location?.speedMps?.toDouble()
        else -> null
    }

    private fun evaluateCondition(condition: String, current: Double, threshold: Double): Boolean {
        val op = condition.trim().lowercase()
        return when {
            op.startsWith("gte") || op.startsWith(">=") -> current >= threshold
            op.startsWith("lte") || op.startsWith("<=") -> current <= threshold
            op.startsWith("gt") || op.startsWith(">") -> current > threshold
            op.startsWith("lt") || op.startsWith("<") -> current < threshold
            op.startsWith("eq") || op.startsWith("=") -> abs(current - threshold) < 0.01
            op.startsWith("delta") -> abs(current - threshold) > threshold * 0.1
            else -> current > threshold
        }
    }
}
