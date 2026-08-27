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
    fun hasData(): Boolean = totalMinutes != null || deepMinutes != null || score != null ||
            vitalsCount > 0 || stagesCount > 0 || awakenings != null || awakeMinutes != null ||
            continuityScore != null || averageSpo2 != null || bedTime != null || wakeTime != null

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

data class HeartRateDetail(
    val range: MetricRange<Int>? = null,
    val restingBpm: Int? = null,
    val currentBpm: Int? = null,
    val walkingBpm: Int? = null,
    val sleepingBpm: Int? = null,
) {
    fun hasData(): Boolean = range != null || restingBpm != null || currentBpm != null ||
            walkingBpm != null || sleepingBpm != null
}

data class StressDetail(
    val range: MetricRange<Int>? = null,
    val average: Int? = null,
    val category: String? = null,
    val current: Int? = null,
) {
    fun hasData(): Boolean = range != null || average != null || category != null || current != null
}

data class OxygenSaturationDetail(
    val range: MetricRange<Int>? = null,
    val average: Int? = null,
    val averageSleep: Int? = null,
    val current: Int? = null,
) {
    fun hasData(): Boolean = range != null || average != null || averageSleep != null || current != null
}

/**
 * Everything read out of Vivo Health in one sync pass, in collection order:
 * the home-screen activity rings first, then the sleep detail screen,
 * heart rate, stress, and oxygen saturation detail screens.
 */
data class ParsedHealthData(
    val activity: DailyActivity? = null,
    val sleep: SleepDetail? = null,
    val heartRate: HeartRateDetail? = null,
    val stress: StressDetail? = null,
    val oxygenSaturation: OxygenSaturationDetail? = null,

    // ── Single-shot / legacy metrics ───────────────────────────
    val heartRateBpm: Int? = null,
    val restingHeartRateBpm: Int? = null,
    val stressLevel: Int? = null,
    val stressCategory: String? = null,
    val weightKg: Float? = null,

    val syncTimestamp: Long = System.currentTimeMillis()
) {
    fun hasSleepData(): Boolean = sleep?.hasData() == true

    fun hasAnyData(): Boolean = hasSleepData() || activity?.hasData() == true ||
            heartRate?.hasData() == true || stress?.hasData() == true ||
            oxygenSaturation?.hasData() == true ||
            heartRateBpm != null || restingHeartRateBpm != null ||
            stressLevel != null || stressCategory != null || weightKg != null

    fun summaryString(): String {
        val parts = mutableListOf<String>()
        activity?.steps?.let { parts.add("$it steps") }
        sleep?.totalMinutes?.let { parts.add("${it / 60}h ${it % 60}m sleep") }
        val vitals = sleep?.vitalsCount ?: 0
        if (vitals > 0) parts.add("$vitals vitals")
        val stages = sleep?.stagesCount ?: 0
        if (stages > 0) parts.add("$stages stages")

        heartRate?.let { hr ->
            val hrParts = mutableListOf<String>()
            hr.currentBpm?.let { hrParts.add("HR $it bpm") }
            when {
                hr.range != null && hr.restingBpm != null -> {
                    hrParts.add(if (hr.currentBpm != null) "range ${hr.range.format("bpm")} (resting ${hr.restingBpm})" else "HR ${hr.range.format("bpm")} (resting ${hr.restingBpm})")
                }
                hr.range != null -> {
                    hrParts.add(if (hr.currentBpm != null) "range ${hr.range.format("bpm")}" else "HR ${hr.range.format("bpm")}")
                }
                hr.restingBpm != null -> {
                    hrParts.add("Resting HR ${hr.restingBpm} bpm")
                }
            }
            hr.walkingBpm?.let { hrParts.add("walking $it") }
            hr.sleepingBpm?.let { hrParts.add("sleeping $it") }
            if (hrParts.isNotEmpty()) {
                parts.add(hrParts.joinToString(" · "))
            }
            Unit
        } ?: run {
            restingHeartRateBpm?.let { parts.add("Resting HR $it bpm") }
                ?: heartRateBpm?.let { parts.add("HR $it bpm") }
        }

        stress?.let { st ->
            val cat = st.category?.let { " ($it)" } ?: ""
            val stParts = mutableListOf<String>()
            st.current?.let { stParts.add("Stress $it$cat") }
            when {
                st.average != null -> {
                    val label = if (st.current != null) "avg ${st.average}" else "Stress avg ${st.average}$cat"
                    stParts.add(label)
                }
                st.range != null -> {
                    val label = if (st.current != null) "range ${st.range.format()}" else "Stress ${st.range.format()}$cat"
                    stParts.add(label)
                }
                st.category != null && st.current == null -> stParts.add("Stress ${st.category}")
            }
            if (st.range != null && st.average != null && st.current != null) {
                stParts.add("range ${st.range.format()}")
            }
            if (stParts.isNotEmpty()) {
                parts.add(stParts.joinToString(" · "))
            }
            Unit
        } ?: run {
            stressLevel?.let { parts.add("Stress $it${stressCategory?.let { c -> " ($c)" } ?: ""}") }
        }

        oxygenSaturation?.let { spo2 ->
            val oxyParts = mutableListOf<String>()
            spo2.current?.let { oxyParts.add("SpO₂ $it%") }
            when {
                spo2.average != null -> {
                    val label = if (spo2.current != null) "avg ${spo2.average}%" else "SpO₂ avg ${spo2.average}%"
                    oxyParts.add(label)
                }
                spo2.range != null -> {
                    val label = if (spo2.current != null) "range ${spo2.range.format("%")}" else "SpO₂ ${spo2.range.format("%")}"
                    oxyParts.add(label)
                }
            }
            if (spo2.range != null && spo2.average != null && spo2.current != null) {
                oxyParts.add("range ${spo2.range.format("%")}")
            }
            spo2.averageSleep?.let { oxyParts.add("sleep avg $it%") }
            if (oxyParts.isNotEmpty()) {
                parts.add(oxyParts.joinToString(" · "))
            }
            Unit
        }

        weightKg?.let { parts.add("$it kg") }
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

fun SleepDetail.merge(other: SleepDetail): SleepDetail {
    val thisHasStages = this.deepMinutes != null || this.lightMinutes != null || this.remMinutes != null
    val otherHasStages = other.deepMinutes != null || other.lightMinutes != null || other.remMinutes != null
    val useOtherStages = otherHasStages && (!thisHasStages || !other.stagesDerived)

    return SleepDetail(
        totalMinutes = other.totalMinutes ?: this.totalMinutes,
        bedTime = other.bedTime ?: this.bedTime,
        wakeTime = other.wakeTime ?: this.wakeTime,
        heartRate = other.heartRate ?: this.heartRate,
        respiratoryRate = other.respiratoryRate ?: this.respiratoryRate,
        spo2 = other.spo2 ?: this.spo2,
        hrv = other.hrv ?: this.hrv,
        score = other.score ?: this.score,
        scoreLabel = other.scoreLabel ?: this.scoreLabel,
        deepMinutes = if (useOtherStages) other.deepMinutes ?: this.deepMinutes else this.deepMinutes ?: other.deepMinutes,
        lightMinutes = if (useOtherStages) other.lightMinutes ?: this.lightMinutes else this.lightMinutes ?: other.lightMinutes,
        remMinutes = if (useOtherStages) other.remMinutes ?: this.remMinutes else this.remMinutes ?: other.remMinutes,
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
        stagesDerived = when {
            useOtherStages && (other.deepMinutes != null || other.lightMinutes != null || other.remMinutes != null) -> other.stagesDerived
            this.deepMinutes != null || this.lightMinutes != null || this.remMinutes != null -> this.stagesDerived
            else -> other.stagesDerived || this.stagesDerived
        },
    )
}

fun HeartRateDetail.merge(other: HeartRateDetail): HeartRateDetail = HeartRateDetail(
    range = other.range ?: this.range,
    restingBpm = other.restingBpm ?: this.restingBpm,
    currentBpm = other.currentBpm ?: this.currentBpm,
    walkingBpm = other.walkingBpm ?: this.walkingBpm,
    sleepingBpm = other.sleepingBpm ?: this.sleepingBpm,
)

fun StressDetail.merge(other: StressDetail): StressDetail = StressDetail(
    range = other.range ?: this.range,
    average = other.average ?: this.average,
    category = other.category ?: this.category,
    current = other.current ?: this.current,
)

fun OxygenSaturationDetail.merge(other: OxygenSaturationDetail): OxygenSaturationDetail = OxygenSaturationDetail(
    range = other.range ?: this.range,
    average = other.average ?: this.average,
    averageSleep = other.averageSleep ?: this.averageSleep,
    current = other.current ?: this.current,
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
    val mergedHeartRate = when {
        this.heartRate != null && other.heartRate != null -> this.heartRate.merge(other.heartRate)
        other.heartRate != null -> other.heartRate
        else -> this.heartRate
    }
    val mergedStress = when {
        this.stress != null && other.stress != null -> this.stress.merge(other.stress)
        other.stress != null -> other.stress
        else -> this.stress
    }
    val mergedOxygen = when {
        this.oxygenSaturation != null && other.oxygenSaturation != null -> this.oxygenSaturation.merge(other.oxygenSaturation)
        other.oxygenSaturation != null -> other.oxygenSaturation
        else -> this.oxygenSaturation
    }
    return ParsedHealthData(
        activity = mergedActivity,
        sleep = mergedSleep,
        heartRate = mergedHeartRate,
        stress = mergedStress,
        oxygenSaturation = mergedOxygen,
        heartRateBpm = other.heartRateBpm ?: this.heartRateBpm,
        restingHeartRateBpm = other.restingHeartRateBpm ?: this.restingHeartRateBpm,
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
