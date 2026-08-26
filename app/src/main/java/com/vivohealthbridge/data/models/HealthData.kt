package com.vivohealthbridge.data.models

/**
 * All data parsed from Vivo Health screens.
 *
 * Sleep fields map to the Vivo Health sleep detail screen:
 *   - Total sleep duration shown at top + below chart  → sleepTotalMinutes
 *   - Sliding stat cards (HR / Resp / SpO2 / HRV)     → sleep*Range fields
 *   - Sleep score badge                                 → sleepScore
 *   - "More analysis" section (Deep/Light/REM/awake)    → stage minutes + counts
 */
data class ParsedHealthData(
    // ── Sleep core ──────────────────────────────────────
    val sleepTotalMinutes: Int? = null,
    val sleepStartTime: String? = null,    // "22:04"
    val sleepEndTime: String? = null,      // "08:41"
    val sleepScore: Int? = null,

    // ── Sleep sliding stat cards (each has a min–max range) ──
    val sleepHeartRateMin: Int? = null,        // bpm
    val sleepHeartRateMax: Int? = null,
    val sleepRespiratoryRateMin: Float? = null, // breaths/min
    val sleepRespiratoryRateMax: Float? = null,
    val sleepSpo2Min: Int? = null,              // %
    val sleepSpo2Max: Int? = null,
    val sleepHrvMin: Int? = null,               // ms
    val sleepHrvMax: Int? = null,

    // ── Sleep "More analysis" breakdown ─────────────────
    val deepSleepMinutes: Int? = null,
    val lightSleepMinutes: Int? = null,
    val remSleepMinutes: Int? = null,
    val deepSleepPct: Int? = null,              // proportion %
    val lightSleepPct: Int? = null,
    val remSleepPct: Int? = null,
    val numberOfAwakenings: Int? = null,
    val awakeMinutes: Int? = null,
    val deepSleepContinuity: String? = null,    // e.g. "Good" / "Poor" / score
    val averageSleepSpo2: Int? = null,          // % (avg blood oxygen during sleep)

    // ── Other metrics (kept for later phases) ───────────
    val steps: Long? = null,
    val heartRateBpm: Int? = null,
    val restingHeartRateBpm: Int? = null,
    val heartRateRangeMin: Int? = null,
    val heartRateRangeMax: Int? = null,
    val stressLevel: Int? = null,
    val stressCategory: String? = null,
    val averageStress: Int? = null,
    val oxygenSaturation: Int? = null,
    val averageOxygenSaturation: Int? = null,
    val weightKg: Float? = null,
    val exerciseDistanceKm: Float? = null,

    val syncTimestamp: Long = System.currentTimeMillis()
) {
    /** True when at least one sleep field was parsed. */
    fun hasSleepData(): Boolean =
        sleepTotalMinutes != null || deepSleepMinutes != null || sleepScore != null
}

enum class SyncStatus {
    PENDING, SUCCESS, FAILED, PARTIAL
}

enum class HealthMetricType {
    STEPS, HEART_RATE, SLEEP, STRESS, SPO2, WEIGHT, EXERCISE
}
