package com.vivohealthbridge.data.models

/**
 * A min–max range, exactly as the Vivo Health sliding stat cards report it:
 * "51−119 bpm", "10.5−25.5 bpm", "94−100 %", "33−88 ms".
 */
data class MetricRange<T : Number>(val min: T, val max: T) {
    val average: Double get() = (min.toDouble() + max.toDouble()) / 2.0

    fun format(unit: String = ""): String =
        if (unit.isBlank()) "$min–$max" else "$min–$max $unit"
}

/**
 * The four activity rings + distance shown at the very top of the Vivo Health
 * "Health" tab, above the Sleep card:
 *
 *   Steps     76 / 8,000steps     Exercise  0 / 30min
 *   Calories  4  / 400kcal        Stand     4 / 12Hour
 *   Distance  0.05 km
 */
data class DailyActivity(
    val steps: Long? = null,
    val stepsGoal: Long? = null,
    val exerciseMinutes: Int? = null,
    val exerciseGoalMinutes: Int? = null,
    val activeCalories: Int? = null,
    val activeCaloriesGoal: Int? = null,
    val standHours: Int? = null,
    val standGoalHours: Int? = null,
    val distanceKm: Float? = null,
) {
    fun hasData(): Boolean = steps != null || exerciseMinutes != null ||
            activeCalories != null || standHours != null || distanceKm != null
}

/**
 * The Vivo Health **Sleep** detail screen, in the order it is read on screen.
 *
 * 1. Header          – "Total sleep duration" / "10 hrs, 15 mins" / "Today"
 * 2. Hypnogram       – x-axis gives the sleep window ("22:04" … "08:41")
 * 3. Sliding cards   – swipe right to reveal, each a min–max range:
 *                      Total sleep duration → Sleep heart rate → Sleep respiratory
 *                      rate → Sleep SpO₂ → Sleep HRV
 * 4. Score block     – "72 pts" / "Slept fairly well" / "Compared to last time"
 * 5. "More analysis" – donut (Deep / Light / REM durations) then the proportion
 *                      cards, awakenings, deep-sleep continuity and average
 *                      blood oxygen during sleep
 */
data class SleepDetail(
    // ── 1 / 2 · header + hypnogram window ───────────────────────
    val totalMinutes: Int? = null,
    val bedTime: String? = null,               // "22:04"
    val wakeTime: String? = null,              // "08:41"

    // ── 3 · sliding stat cards ──────────────────────────────────
    val heartRate: MetricRange<Int>? = null,        // bpm
    val respiratoryRate: MetricRange<Float>? = null, // breaths/min (shown as bpm)
    val spo2: MetricRange<Int>? = null,             // %
    val hrv: MetricRange<Int>? = null,              // ms

    // ── 4 · sleep score block ───────────────────────────────────
    val score: Int? = null,                    // "72 pts"
    val scoreLabel: String? = null,            // "Slept fairly well"

    // ── 5 · "More analysis" ─────────────────────────────────────
    val deepMinutes: Int? = null,
    val lightMinutes: Int? = null,
    val remMinutes: Int? = null,
    val deepPercent: Int? = null,
    val lightPercent: Int? = null,
    val remPercent: Int? = null,
    val deepRating: String? = null,            // "Low"
    val lightRating: String? = null,           // "High"
    val remRating: String? = null,             // "Normal"
    val awakenings: Int? = null,               // "1 reps"
    val awakeMinutes: Int? = null,             // "22 mins"
    val continuityScore: Int? = null,          // "100 pts"
    val continuityRating: String? = null,      // "Normal"
    val averageSpo2: Int? = null,              // "96%"

    /** True when the stage minutes were derived from the proportion cards. */
    val stagesDerived: Boolean = false,
) {
    fun hasData(): Boolean = totalMinutes != null || deepMinutes != null || score != null

    /** Length of the bed-time window in minutes, when both ends were read. */
    fun windowMinutes(): Int? {
        val start = parseClock(bedTime) ?: return null
        val end = parseClock(wakeTime) ?: return null
        return if (end >= start) end - start else end + 24 * 60 - start
    }

    private fun parseClock(value: String?): Int? {
        val parts = value?.split(":") ?: return null
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return h * 60 + m
    }
}

/**
 * Everything read out of Vivo Health in one sync pass, in collection order:
 * the home-screen activity rings first, then the sleep detail screen.
 *
 * The loose fields at the bottom are single metrics that only Manual Entry
 * writes today; they will move into their own screens in later phases.
 */
data class ParsedHealthData(
    val activity: DailyActivity? = null,
    val sleep: SleepDetail? = null,

    // ── Single-shot metrics (Manual Entry / later phases) ───────
    val heartRateBpm: Int? = null,
    val restingHeartRateBpm: Int? = null,
    val oxygenSaturation: Int? = null,
    val stressLevel: Int? = null,
    val stressCategory: String? = null,
    val weightKg: Float? = null,

    val syncTimestamp: Long = System.currentTimeMillis()
) {
    fun hasSleepData(): Boolean = sleep?.hasData() == true

    fun hasAnyData(): Boolean = hasSleepData() || activity?.hasData() == true ||
            heartRateBpm != null || restingHeartRateBpm != null || oxygenSaturation != null ||
            stressLevel != null || weightKg != null
}

enum class SyncStatus {
    PENDING, SUCCESS, FAILED, PARTIAL
}

enum class HealthMetricType {
    STEPS, DISTANCE, ACTIVE_CALORIES, EXERCISE, STAND,
    SLEEP, HEART_RATE, HRV, RESPIRATORY_RATE, SPO2, STRESS, WEIGHT
}
