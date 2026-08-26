package com.vivohealthbridge.data.models

data class ParsedHealthData(
    val steps: Long? = null,
    val heartRateBpm: Int? = null,
    val restingHeartRateBpm: Int? = null,
    val heartRateRangeMin: Int? = null,
    val heartRateRangeMax: Int? = null,
    val sleepTotalMinutes: Int? = null,
    val sleepStartTime: String? = null,    // "22:04"
    val sleepEndTime: String? = null,       // "08:41"
    val deepSleepMinutes: Int? = null,
    val lightSleepMinutes: Int? = null,
    val remSleepMinutes: Int? = null,
    val awakeMinutes: Int? = null,
    val numberOfAwakenings: Int? = null,
    val stressLevel: Int? = null,           // 0-100
    val stressCategory: String? = null,     // "Relaxed", "Moderate", "High"
    val averageStress: Int? = null,
    val oxygenSaturation: Int? = null,      // percentage
    val averageOxygenSaturation: Int? = null,
    val averageSleepSpO2: Int? = null,
    val weightKg: Float? = null,
    val exerciseDistanceKm: Float? = null,
    val syncTimestamp: Long = System.currentTimeMillis()
)

enum class SyncStatus {
    PENDING, SUCCESS, FAILED, PARTIAL
}

enum class HealthMetricType {
    STEPS, HEART_RATE, SLEEP, STRESS, SPO2, WEIGHT, EXERCISE
}
