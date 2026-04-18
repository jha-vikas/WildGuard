package com.wildguard.app.modules.altitude

enum class AcclimatizationStatus { SAFE, CAUTION, DANGER }

data class LakeLouiseSymptoms(
    val headache: Int = 0,
    val giNausea: Int = 0,
    val fatigue: Int = 0,
    val dizziness: Int = 0,
    val sleepDifficulty: Int = 0
) {
    val totalScore: Int
        get() = headache + giNausea + fatigue + dizziness + sleepDifficulty

    val hasAMS: Boolean
        get() = headache >= 1 && totalScore >= 3
}

data class SleepAltitudeRecord(
    val altitudeM: Double,
    val dateMillis: Long
)

data class AltitudeSicknessRisk(
    val ascentRate: Double,
    val riskLevel: AcclimatizationStatus,
    val recommendation: String,
    val lakeLouiseScore: Int
)

class AltitudeSicknessCalculator {

    private val sleepAltitudes = mutableListOf<SleepAltitudeRecord>()

    fun recordSleepAltitude(altitudeM: Double, dateMillis: Long = System.currentTimeMillis()) {
        sleepAltitudes.add(SleepAltitudeRecord(altitudeM, dateMillis))
        sleepAltitudes.sortBy { it.dateMillis }
        while (sleepAltitudes.size > MAX_RECORDS) sleepAltitudes.removeFirst()
    }

    fun computeAscentRateMPerDay(): Double {
        if (sleepAltitudes.size < 2) return 0.0
        val latest = sleepAltitudes.last()
        val previous = sleepAltitudes[sleepAltitudes.size - 2]
        val daysDelta = (latest.dateMillis - previous.dateMillis).toDouble() / MS_PER_DAY
        if (daysDelta < 0.1) return 0.0
        return (latest.altitudeM - previous.altitudeM) / daysDelta
    }

    fun evaluate(
        currentAltitudeM: Double,
        symptoms: LakeLouiseSymptoms = LakeLouiseSymptoms()
    ): AltitudeSicknessRisk {
        val ascentRate = computeAscentRateMPerDay()
        val llScore = symptoms.totalScore

        val riskLevel = when {
            symptoms.hasAMS -> AcclimatizationStatus.DANGER
            currentAltitudeM > 2500 && ascentRate > 500 -> AcclimatizationStatus.DANGER
            currentAltitudeM > 2500 && ascentRate > 300 -> AcclimatizationStatus.CAUTION
            llScore in 1..2 -> AcclimatizationStatus.CAUTION
            currentAltitudeM > 3500 -> AcclimatizationStatus.CAUTION
            else -> AcclimatizationStatus.SAFE
        }

        val recommendation = when (riskLevel) {
            AcclimatizationStatus.DANGER -> buildString {
                if (symptoms.hasAMS) append("AMS symptoms detected (Lake Louise score $llScore). ")
                if (currentAltitudeM > 2500 && ascentRate > 500) {
                    append("Ascending too fast (${ascentRate.toInt()}m/day above 2500m). ")
                }
                append("Do NOT ascend further. Descend if symptoms worsen.")
            }
            AcclimatizationStatus.CAUTION -> buildString {
                if (currentAltitudeM > 2500 && ascentRate > 300) {
                    append("Ascent rate ${ascentRate.toInt()}m/day is elevated above 2500m. ")
                }
                append("Rest day recommended before ascending further. Monitor symptoms.")
            }
            AcclimatizationStatus.SAFE ->
                "Acclimatization on track. Stay hydrated and ascend gradually."
        }

        return AltitudeSicknessRisk(
            ascentRate = ascentRate,
            riskLevel = riskLevel,
            recommendation = recommendation,
            lakeLouiseScore = llScore
        )
    }

    companion object {
        private const val MS_PER_DAY = 86_400_000.0
        private const val MAX_RECORDS = 30
    }
}
