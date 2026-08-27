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

    val vitalsCount: Int get() = listOfNotNull(heartRate, respiratoryRate, spo2, hrv).size
    val stagesCount: Int get() = listOfNotNull(deepMinutes ?: deepPercent, lightMinutes ?: lightPercent, remMinutes ?: remPercent).size
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

    fun summaryString(): String {
        val parts = mutableListOf<String>()
        activity?.steps?.let { parts.add("$it steps") }
        sleep?.totalMinutes?.let { parts.add("${it / 60}h ${it % 60}m sleep") }
        val vitals = sleep?.vitalsCount ?: 0
        if (vitals > 0) parts.add("$vitals vitals")
        val stages = sleep?.stagesCount ?: 0
        if (stages > 0) parts.add("$stages stages")
        return if (parts.isEmpty()) "No metrics captured" else parts.joinToString(" · ")
    }
}

fun DailyActivity.merge(other: DailyActivity): DailyActivity = DailyActivity(
    steps = other.steps ?: this.steps,
    stepsGoal = other.stepsGoal ?: this.stepsGoal,
    exerciseMinutes = other.exerciseMinutes ?: this.exerciseMinutes,
    exerciseGoalMinutes = other.exerciseGoalMinutes ?: this.exerciseGoalMinutes,
    activeCalories = other.activeCalories ?: this.activeCalories,
    activeCaloriesGoal = other.activeCaloriesGoal ?: this.activeCaloriesGoal,
    standHours = other.standHours ?: this.standHours,
    standGoalHours = other.standGoalHours ?: this.standGoalHours,
    distanceKm = other.distanceKm ?: this.distanceKm,
)

fun SleepDetail.merge(other: SleepDetail): SleepDetail = SleepDetail(
    totalMinutes = other.totalMinutes ?: this.totalMinutes,
    bedTime = other.bedTime ?: this.bedTime,
    wakeTime = other.wakeTime ?: this.wakeTime,
    heartRate = other.heartRate ?: this.heartRate,
    respiratoryRate = other.respiratoryRate ?: this.respiratoryRate,
    spo2 = other.spo2 ?: this.spo2,
    hrv = other.hrv ?: this.hrv,
    score = other.score ?: this.score,
    scoreLabel = other.scoreLabel ?: this.scoreLabel,
    deepMinutes = other.deepMinutes ?: this.deepMinutes,
    lightMinutes = other.lightMinutes ?: this.lightMinutes,
    remMinutes = other.remMinutes ?: this.remMinutes,
    deepPercent = other.deepPercent ?: this.deepPercent,
    lightPercent = other.lightPercent ?: this.lightPercent,
    remPercent = other.remPercent ?: this.remPercent,
    deepRating = other.deepRating ?: this.deepRating,
    lightRating = other.lightRating ?: this.lightRating,
    remRating = other.remRating ?: this.remRating,
    awakenings = other.awakenings ?: this.awakenings,
    awakeMinutes = other.awakeMinutes ?: this.awakeMinutes,
    continuityScore = other.continuityScore ?: this.continuityScore,
    continuityRating = other.continuityRating ?: this.continuityRating,
    averageSpo2 = other.averageSpo2 ?: this.averageSpo2,
    stagesDerived = other.stagesDerived || this.stagesDerived,
)

fun ParsedHealthData.merge(other: ParsedHealthData): ParsedHealthData {
    val mergedActivity = when {
        this.activity != null && other.activity != null -> this.activity.merge(other.activity)
        other.activity != null -> other.activity
        else -> this.activity
    }
    val mergedSleep = when {
        this.sleep != null && other.sleep != null -> this.sleep.merge(other.sleep)
        other.sleep != null -> other.sleep
        else -> this.sleep
    }
    return ParsedHealthData(
        activity = mergedActivity,
        sleep = mergedSleep,
        heartRateBpm = other.heartRateBpm ?: this.heartRateBpm,
        restingHeartRateBpm = other.restingHeartRateBpm ?: this.restingHeartRateBpm,
        oxygenSaturation = other.oxygenSaturation ?: this.oxygenSaturation,
        stressLevel = other.stressLevel ?: this.stressLevel,
        stressCategory = other.stressCategory ?: this.stressCategory,
        weightKg = other.weightKg ?: this.weightKg,
        syncTimestamp = System.currentTimeMillis()
    )
}

enum class SyncStatus {
    PENDING, SUCCESS, FAILED, PARTIAL
}

enum class HealthMetricType {
    STEPS, DISTANCE, ACTIVE_CALORIES, EXERCISE, STAND,
    SLEEP, HEART_RATE, HRV, RESPIRATORY_RATE, SPO2, STRESS, WEIGHT
}
