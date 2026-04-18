package com.wildguard.app.llm.plan

data class TripPlan(
    val id: String,
    val generatedAt: Long,
    val validUntil: Long,
    val summary: String,
    val sections: List<PlanSection>,
    val contingencies: List<Contingency>,
    val alerts: List<TriggeredAlert>,
    val decisionPoints: List<DecisionPoint>,
    val rawResponse: String
)

data class PlanSection(
    val timeOrDistance: String,
    val description: String,
    val warnings: List<String>
)

data class Contingency(
    val condition: String,
    val sensorType: String,
    val threshold: Double,
    val action: String
)

data class TriggeredAlert(
    val condition: String,
    val message: String,
    val checkIntervalMs: Long,
    val threshold: Double,
    val sensorType: String
)

data class DecisionPoint(
    val triggerLat: Double?,
    val triggerLon: Double?,
    val triggerTimeMs: Long?,
    val prompt: String,
    val optionA: String,
    val optionB: String
)
