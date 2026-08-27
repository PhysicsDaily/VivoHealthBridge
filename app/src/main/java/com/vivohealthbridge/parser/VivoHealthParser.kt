package com.vivohealthbridge.parser

import com.vivohealthbridge.data.models.DailyActivity
import com.vivohealthbridge.data.models.HeartRateDetail
import com.vivohealthbridge.data.models.MetricRange
import com.vivohealthbridge.data.models.OxygenSaturationDetail
import com.vivohealthbridge.data.models.ParsedHealthData
import com.vivohealthbridge.data.models.SleepDetail
import com.vivohealthbridge.data.models.StressDetail

/**
 * Turns the raw accessibility text nodes captured from Vivo Health into typed data.
 *
 * Deliberately free of any `android.*` dependency so it can be unit tested on the
 * JVM against the exact strings the app renders.
 *
 * ## Why the text needs normalising first
 * Vivo Health does not use plain ASCII for its numbers:
 *  - ranges are joined with U+2212 MINUS SIGN — `51−119 bpm`, not `51-119 bpm`
 *  - "SpO₂" uses U+2082 SUBSCRIPT TWO, so a search for "spo2" never matches
 *  - goals carry thousands separators — `8,000steps`
 * [normalize] flattens all of that before any pattern is applied.
 *
 * ## Captures
 * A single screen never holds every value: the stat cards must be swiped
 * horizontally and the analysis section scrolled into view. Each read is passed in
 * as its own *capture*, and lookups never cross a capture boundary — otherwise the
 * last node of one read would look like the label for the first node of the next.
 */
class VivoHealthParser {

    companion object {
        /** Separator inserted between captures; label→value lookups stop here. */
        private const val BREAK = " "

        private val RATINGS = setOf(
            "low", "high", "normal", "good", "excellent", "poor", "fair", "average"
        )

        private val STRESS_CATEGORIES = setOf(
            "relaxed", "moderate", "medium", "normal", "low", "high", "extremely high", "very high"
        )

        private val DURATION_HOURS = Regex("""(\d+)\s*(?:hrs?|hours?|h)\b""", RegexOption.IGNORE_CASE)
        private val DURATION_MINUTES = Regex("""(\d+)\s*(?:mins?|minutes?|m)\b""", RegexOption.IGNORE_CASE)
        private val TIME_WINDOW = Regex("""\(?(\d{1,2}:\d{2})\s*-\s*(\d{1,2}:\d{2})\)?""")
        private val CLOCK = Regex("""^(\d{1,2}:\d{2})$""")
        private val INT_RANGE = Regex("""(?<![\d.])(\d+)\s*-\s*(\d+)(?![\d.])""")
        private val FLOAT_RANGE = Regex("""(?<![\d.])(\d+(?:\.\d+)?)\s*-\s*(\d+(?:\.\d+)?)(?![\d.])""")
        private val PERCENT = Regex("""(\d{1,3})\s*%""")
        private val SCORE = Regex("""^(\d{1,3})\s*(?:pts?|points?)?$""", RegexOption.IGNORE_CASE)
        private val FIRST_INT = Regex("""(\d+)""")
        private val CURRENT_GOAL = Regex("""(\d+)\s*/\s*(\d+)""")
        private val KILOMETRES = Regex("""([\d.]+)\s*km""", RegexOption.IGNORE_CASE)
        private val BPM_VALUE = Regex("""(?<![\d.])(\d{2,3})\s*bpm\b""", RegexOption.IGNORE_CASE)
        private val KG_VALUE = Regex("""(?<![\d.])(\d{2,3}(?:\.\d+)?)\s*kg\b""", RegexOption.IGNORE_CASE)
        private val TIME_AGO = Regex("""\b\d+\s*(?:m|mins?|minutes?|h|hrs?|hours?|s|secs?|seconds?)\s*ago\b""", RegexOption.IGNORE_CASE)
        private val JUST_NOW = Regex("""\bjust\s*now\b""", RegexOption.IGNORE_CASE)
    }

    // ══════════════════════════════════════════════════════════════
    //  Step 1 · Home screen activity rings (read before Sleep)
    // ══════════════════════════════════════════════════════════════

    fun parseHomeActivity(captures: List<List<String>>): DailyActivity {
        val screen = Screen(captures)

        val steps = screen.currentAndGoal("Steps", "Step count", "Step")
        val exercise = screen.currentAndGoal("Exercise", "Exercise time", "Workout")
        val calories = screen.currentAndGoal("Calories", "Active calories", "Calorie")
        val stand = screen.currentAndGoal("Stand", "Stand hours", "Standing")

        val singleSteps = if (steps == null) {
            screen.valueNear({ it.equals("steps", true) || it.equals("step count", true) }) { parseSingleInt(it)?.toLong() }
        } else null

        val singleExercise = if (exercise == null) {
            screen.valueNear({ it.equals("exercise", true) || it.equals("exercise time", true) }) { parseDuration(it) ?: parseSingleInt(it) }
        } else null

        val singleCalories = if (calories == null) {
            screen.valueNear({ it.equals("calories", true) || it.equals("active calories", true) }) { parseSingleInt(it) }
        } else null

        val singleStand = if (stand == null) {
            screen.valueNear({ it.equals("stand", true) || it.equals("stand hours", true) }) { parseSingleInt(it) }
        } else null

        return DailyActivity(
            steps = steps?.first ?: singleSteps,
            stepsGoal = steps?.second,
            exerciseMinutes = exercise?.first?.toInt() ?: singleExercise,
            exerciseGoalMinutes = exercise?.second?.toInt(),
            activeCalories = calories?.first?.toInt() ?: singleCalories,
            activeCaloriesGoal = calories?.second?.toInt(),
            standHours = stand?.first?.toInt() ?: singleStand,
            standGoalHours = stand?.second?.toInt(),
            distanceKm = screen.valueNear({ it.contains("distance", true) }) { parseKilometres(it) },
        )
    }

    /**
     * Single-capture convenience. Needs [JvmName] because `List<List<String>>`
     * and `List<String>` erase to the same JVM signature.
     */
    @JvmName("parseHomeActivityFromTexts")
    fun parseHomeActivity(texts: List<String>): DailyActivity = parseHomeActivity(listOf(texts))

    /**
     * Reads recent metrics displayed directly on the Health home tab cards:
     * - Heart rate card: "Heart rate", "2m ago" / "Just now", "82bpm" / "88bpm"
     * - Stress card: "Stress", "4m ago", "47", "Moderate"
     * - Oxygen saturation card: "Oxygen saturation", "44m ago", "98%", "Normal"
     * - Weight card: "Weight", "56.4 kg"
     */
    fun parseHomeCards(captures: List<List<String>>): ParsedHealthData {
        val screen = Screen(captures)

        val hrCurrent = screen.valueNear(
            labelMatch = { it.equals("heart rate", true) },
            forward = 6
        ) { parseBpm(it) }

        val stressCurrent = screen.valueNear(
            labelMatch = { it.equals("stress", true) },
            forward = 6
        ) { text ->
            if (TIME_AGO.containsMatchIn(text) || JUST_NOW.containsMatchIn(text)) null
            else parseSingleInt(text)?.takeIf { it in 1..100 }
        }
        val stressCat = screen.valueNear(
            labelMatch = { it.equals("stress", true) },
            forward = 6
        ) { parseStressCategory(it) }

        val spo2Current = screen.valueNear(
            labelMatch = { it.equals("oxygen saturation", true) || it.equals("blood oxygen", true) || it.equals("spo2", true) },
            forward = 6
        ) { text ->
            if (TIME_AGO.containsMatchIn(text) || JUST_NOW.containsMatchIn(text)) null
            else parseOxygenPercent(text)
        }

        val weight = screen.valueNear(
            labelMatch = { it.equals("weight", true) },
            forward = 6
        ) { parseWeightKg(it) }

        val hrDetail = hrCurrent?.let { HeartRateDetail(currentBpm = it) }
        val stressDetail = if (stressCurrent != null || stressCat != null) {
            StressDetail(current = stressCurrent, average = stressCurrent, category = stressCat)
        } else null
        val spo2Detail = spo2Current?.let { OxygenSaturationDetail(current = it) }

        return ParsedHealthData(
            heartRate = hrDetail,
            heartRateBpm = hrCurrent,
            stress = stressDetail,
            stressLevel = stressCurrent,
            stressCategory = stressCat,
            oxygenSaturation = spo2Detail,
            weightKg = weight
        )
    }

    @JvmName("parseHomeCardsFromTexts")
    fun parseHomeCards(texts: List<String>): ParsedHealthData = parseHomeCards(listOf(texts))

    // ══════════════════════════════════════════════════════════════
    //  Step 2 · Sleep detail screen
    // ══════════════════════════════════════════════════════════════

    fun parseSleepDetail(captures: List<List<String>>): SleepDetail {
        val screen = Screen(captures)

        // ── Header: "Total sleep duration" → "10 hrs, 15 mins" ──
        // The narrative paragraph ("You slept 10 hrs, 15 mins last night…")
        // is a fallback for when the header itself is still rendering.
        val totalMinutes =
            screen.valueNear({ it.contains("total sleep duration", true) }) { parseDuration(it) }
                ?: screen.valueNear({ it.contains("you slept", true) }) { parseDuration(it) }

        // ── Hypnogram window: "(22:04-08:41)", else the two axis labels ──
        val window = screen.firstNotNull { parseTimeWindow(it) } ?: screen.clockPair()

        // ── Sliding stat cards ──────────────────────────────────
        val heartRate = screen.valueNear(
            { it.contains("sleep heart rate", true) || it.contains("sleeping heart rate", true) }
        ) { parseIntRange(it) }

        val respiratoryRate = screen.valueNear(
            { it.contains("respiratory", true) || it.contains("breathing", true) }
        ) { parseFloatRange(it) }

        val spo2 = screen.valueNear({ it.contains("spo2", true) }) { parseIntRange(it) }

        val hrv = screen.valueNear(
            { it.contains("hrv", true) || it.contains("heart rate variability", true) }
        ) { parseIntRange(it) }

        // ── Score block ─────────────────────────────────────────
        val (score, scoreLabel) = screen.sleepScore()

        // ── "More analysis" ─────────────────────────────────────
        val legend = screen.legendDurations()

        val deepPercent = screen.valueNear({ it.contains("deep sleep proportion", true) }) { parsePercent(it) }
        val lightPercent = screen.valueNear({ it.contains("light sleep proportion", true) }) { parsePercent(it) }
        val remPercent = screen.valueNear(
            { it.contains("rem proportion", true) || it.contains("rem sleep proportion", true) }
        ) { parsePercent(it) }

        // Stage minutes fall back to percent × total when the donut legend was
        // not on screen long enough to be captured.
        val stagesDerived = legend.deep == null && legend.light == null && legend.rem == null &&
                totalMinutes != null && (deepPercent != null || lightPercent != null || remPercent != null)

        return SleepDetail(
            totalMinutes = totalMinutes,
            bedTime = window?.first,
            wakeTime = window?.second,
            heartRate = heartRate,
            respiratoryRate = respiratoryRate,
            spo2 = spo2,
            hrv = hrv,
            score = score,
            scoreLabel = scoreLabel,
            deepMinutes = legend.deep ?: derive(deepPercent, totalMinutes),
            lightMinutes = legend.light ?: derive(lightPercent, totalMinutes),
            remMinutes = legend.rem ?: derive(remPercent, totalMinutes),
            deepPercent = deepPercent,
            lightPercent = lightPercent,
            remPercent = remPercent,
            deepRating = screen.ratingNear { it.contains("deep sleep proportion", true) },
            lightRating = screen.ratingNear { it.contains("light sleep proportion", true) },
            remRating = screen.ratingNear {
                it.contains("rem proportion", true) || it.contains("rem sleep proportion", true)
            },
            awakenings = screen.valueNear({ it.contains("number of awakenings", true) || (it.contains("awakening", true) && !it.contains("duration", true) && !it.contains("time", true)) || it.contains("woke up", true) }) { parseFirstInt(it) },
            awakeMinutes = screen.valueNear({ it.contains("duration of awakenings", true) || (it.contains("awakening", true) && (it.contains("duration", true) || it.contains("time", true))) || it.contains("awake duration", true) || it.contains("awake time", true) || it.contains("time awake", true) }) { parseDuration(it) },
            continuityScore = screen.valueNear({ it.contains("continuity", true) }) { parseScore(it) },
            continuityRating = screen.ratingNear { it.contains("continuity", true) },
            averageSpo2 = screen.valueNear(
                { it.contains("average blood oxygen", true) || it.contains("average spo2", true) || (it.contains("blood oxygen", true) && it.contains("sleep", true) && (it.contains("avg", true) || it.contains("average", true))) }
            ) { parseOxygenPercent(it) ?: parsePercent(it) },
            stagesDerived = stagesDerived,
        )
    }

    /** Single-capture convenience — see [parseHomeActivity] for why [JvmName]. */
    @JvmName("parseSleepDetailFromTexts")
    fun parseSleepDetail(texts: List<String>): SleepDetail = parseSleepDetail(listOf(texts))

    // ══════════════════════════════════════════════════════════════
    //  Step 3 · Standalone Heart Rate detail screen
    // ══════════════════════════════════════════════════════════════

    fun parseHeartRateDetail(captures: List<List<String>>): HeartRateDetail {
        val screen = Screen(captures)

        val range = screen.valueNear(
            { (it.contains("heart rate range", true) || it.contains("daily range", true) || it.contains("range", true) || it.equals("heart rate", true)) &&
                    !it.contains("resting", true) && !it.contains("walking", true) && !it.contains("sleeping", true) && !it.contains("current", true) }
        ) { parseIntRange(it) } ?: screen.firstNotNull { parseIntRange(it) }

        val restingBpm = screen.valueNear(
            { (it.contains("resting heart rate", true) || it.contains("resting hr", true) || it.contains("resting", true)) && !it.contains("range", true) }
        ) { parseBpm(it) ?: parseSingleInt(it) }

        val walkingBpm = screen.valueNear(
            { (it.contains("average walking heart rate", true) || it.contains("walking heart rate", true) || it.contains("walking hr", true) || it.contains("walking", true)) && !it.contains("range", true) }
        ) { parseBpm(it) ?: parseSingleInt(it) }

        val sleepingBpm = screen.valueNear(
            { (it.contains("normal sleeping heart rate", true) || it.contains("sleeping heart rate", true) || it.contains("sleeping hr", true) || it.contains("sleeping", true) || (it.contains("sleep", true) && it.contains("heart rate", true))) &&
                    !it.contains("range", true) && !it.contains("hypnogram", true) }
        ) { parseBpm(it) ?: parseSingleInt(it) }

        val currentBpm = screen.valueNear(
            { (it.contains("current", true) || it.contains("latest", true) || it.contains("real-time", true)) && !it.contains("range", true) }
        ) { parseBpm(it) ?: parseSingleInt(it) }

        return HeartRateDetail(
            range = range,
            restingBpm = restingBpm,
            currentBpm = currentBpm,
            walkingBpm = walkingBpm,
            sleepingBpm = sleepingBpm,
        )
    }

    @JvmName("parseHeartRateDetailFromTexts")
    fun parseHeartRateDetail(texts: List<String>): HeartRateDetail = parseHeartRateDetail(listOf(texts))

    // ══════════════════════════════════════════════════════════════
    //  Step 4 · Standalone Stress detail screen
    // ══════════════════════════════════════════════════════════════

    fun parseStressDetail(captures: List<List<String>>): StressDetail {
        val screen = Screen(captures)

        val range = screen.valueNear(
            { (it.contains("stress range", true) || it.contains("daily range", true) || (it.contains("range", true) && !it.contains("average", true))) &&
                    !it.contains("relaxed", true) && !it.contains("moderate", true) && !it.contains("high", true) && !it.contains("distribution", true) }
        ) { parseIntRange(it) } ?: screen.firstNotNull { parseIntRange(it) }

        val average = screen.valueNear(
            { (it.contains("average stress", true) || it.contains("average", true) || it.contains("avg", true) || it.contains("score", true)) &&
                    !it.contains("range", true) && !it.contains("distribution", true) }
        ) { parseScore(it) ?: parseSingleInt(it) }

        val category = screen.valueNear(
            { (it.contains("category", true) || it.contains("status", true) || it.contains("level", true) || it.contains("average stress", true) || it.contains("stress", true)) &&
                    !it.contains("range", true) && !it.contains("distribution", true) }
        ) { text -> parseStressCategory(text) } ?: screen.firstNotNull { text ->
            if (text.contains("distribution", true)) null else parseStressCategory(text)
        } ?: average?.let { avg ->
            when (avg) {
                in 1..33 -> "Relaxed"
                in 34..66 -> "Moderate"
                in 67..100 -> "High"
                else -> null
            }
        }

        val current = screen.valueNear(
            { (it.contains("current", true) || it.contains("latest", true) || it.contains("real-time", true)) &&
                    !it.contains("range", true) && !it.contains("average", true) && !it.contains("distribution", true) }
        ) { parseScore(it) ?: parseSingleInt(it) }

        return StressDetail(
            range = range,
            average = average,
            category = category,
            current = current,
        )
    }

    fun parseStressCategory(text: String): String? {
        val t = normalize(text).lowercase().trim().trimEnd('.', '!')
        if (t in STRESS_CATEGORIES) return t.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        for (cat in STRESS_CATEGORIES) {
            if (Regex("""\b${Regex.escape(cat)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
                return cat.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }
        return null
    }

    @JvmName("parseStressDetailFromTexts")
    fun parseStressDetail(texts: List<String>): StressDetail = parseStressDetail(listOf(texts))

    // ══════════════════════════════════════════════════════════════
    //  Step 5 · Standalone Oxygen Saturation (SpO₂) screen
    // ══════════════════════════════════════════════════════════════

    fun parseOxygenDetail(captures: List<List<String>>): OxygenSaturationDetail {
        val screen = Screen(captures)

        val range = screen.valueNear(
            { (it.contains("spo2 range", true) || it.contains("blood oxygen range", true) || it.contains("range", true) || it.contains("spo2", true) || it.contains("blood oxygen", true) || it.contains("oxygen saturation", true)) &&
                    !it.contains("average", true) && !it.contains("avg", true) && !it.contains("altitude", true) }
        ) { parseIntRange(it) } ?: screen.firstNotNull { parseIntRange(it) }

        val averageSleep = screen.valueNear(
            { it.contains("sleep", true) && (it.contains("average", true) || it.contains("avg", true) || it.contains("blood oxygen", true) || it.contains("spo2", true) || it.contains("oxygen", true)) &&
                    !it.contains("range", true) && !it.contains("altitude", true) }
        ) { parseOxygenPercent(it) }

        val average = screen.valueNear(
            { (it.contains("average oxygen saturation", true) || it.contains("average blood oxygen", true) || it.contains("average spo2", true) || it.contains("average", true) || it.contains("avg", true) || it.contains("daily", true)) &&
                    !it.contains("sleep", true) && !it.contains("range", true) && !it.contains("altitude", true) }
        ) { parseOxygenPercent(it) }

        val current = screen.valueNear(
            { (it.contains("current", true) || it.contains("latest", true) || it.contains("real-time", true)) &&
                    !it.contains("range", true) && !it.contains("average", true) && !it.contains("sleep", true) && !it.contains("altitude", true) }
        ) { parseOxygenPercent(it) }

        return OxygenSaturationDetail(
            range = range,
            average = average,
            averageSleep = averageSleep,
            current = current,
        )
    }

    @JvmName("parseOxygenDetailFromTexts")
    fun parseOxygenDetail(texts: List<String>): OxygenSaturationDetail = parseOxygenDetail(listOf(texts))

    @JvmName("parseOxygenSaturationDetailFromCaptures")
    fun parseOxygenSaturationDetail(captures: List<List<String>>): OxygenSaturationDetail = parseOxygenDetail(captures)

    @JvmName("parseOxygenSaturationDetailFromTexts")
    fun parseOxygenSaturationDetail(texts: List<String>): OxygenSaturationDetail = parseOxygenDetail(texts)

    private fun derive(percent: Int?, totalMinutes: Int?): Int? {
        if (percent == null || totalMinutes == null) return null
        return (totalMinutes * percent / 100.0).toInt().takeIf { it > 0 }
    }

    // ══════════════════════════════════════════════════════════════
    //  Text normalisation
    // ══════════════════════════════════════════════════════════════

    /**
     * Flattens Vivo Health's typography into something patterns can match:
     * minus-sign/en-dash/tilde → `-`, subscript digits → ASCII digits,
     * non-breaking spaces → space, and `8,000` → `8000`.
     */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(
                when (ch) {
                    '\u2212', '\u2013', '\u2014', '\u2015', '\uFF0D', '~', '\uFF5E' -> '-'
                    '\u00A0', '\u202F', '\u2009', '\u200A', '\u2007' -> ' '
                    in '\u2080'..'\u2089' -> '0' + (ch - '\u2080')
                    else -> ch
                }
            )
        }
        return sb.toString()
            .replace(Regex("""(?<=\d),(?=\d)"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    // ══════════════════════════════════════════════════════════════
    //  Field-level parsers
    // ══════════════════════════════════════════════════════════════

    /** `"10 hrs, 15 mins"` → 615 · `"1 hr, 50 mins"` → 110 · `"22 mins"` → 22 */
    fun parseDuration(text: String): Int? {
        val t = normalize(text)
        val hours = DURATION_HOURS.find(t)?.groupValues?.get(1)?.toIntOrNull()
        val minutes = DURATION_MINUTES.find(t)?.groupValues?.get(1)?.toIntOrNull()
        if (hours == null && minutes == null) return null
        val total = (hours ?: 0) * 60 + (minutes ?: 0)
        return total.takeIf { it > 0 }
    }

    /** `"10 hrs, 15 mins (22:04-08:41)"` → `"22:04"` to `"08:41"` */
    fun parseTimeWindow(text: String): Pair<String, String>? {
        val m = TIME_WINDOW.find(normalize(text)) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    /** `"51−119 bpm"` → 51..119 · `"94−100 %"` → 94..100 */
    fun parseIntRange(text: String): MetricRange<Int>? {
        val m = INT_RANGE.find(normalize(text)) ?: return null
        val min = m.groupValues[1].toIntOrNull() ?: return null
        val max = m.groupValues[2].toIntOrNull() ?: return null
        return MetricRange(min, max)
    }

    /** `"10.5−25.5 bpm"` → 10.5..25.5 */
    fun parseFloatRange(text: String): MetricRange<Float>? {
        val m = FLOAT_RANGE.find(normalize(text)) ?: return null
        val min = m.groupValues[1].toFloatOrNull() ?: return null
        val max = m.groupValues[2].toFloatOrNull() ?: return null
        return MetricRange(min, max)
    }

    /** `"17%"` → 17. Rejects ranges like `"94-100%"` */
    fun parsePercent(text: String): Int? {
        val t = normalize(text)
        if (INT_RANGE.containsMatchIn(t) || FLOAT_RANGE.containsMatchIn(t)) return null
        return PERCENT.find(t)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Parses an oxygen saturation percentage (50..100%), properly ignoring any
     * '2' in "SpO2" / "SpO₂" and rejecting ranges like "94-100%".
     */
    fun parseOxygenPercent(text: String): Int? {
        val t = normalize(text)
        if (INT_RANGE.containsMatchIn(t) || FLOAT_RANGE.containsMatchIn(t)) return null
        parsePercent(t)?.let { return it }
        val clean = t.replace(Regex("""\bspo2?\b""", RegexOption.IGNORE_CASE), "").trim()
        val value = FIRST_INT.find(clean)?.groupValues?.get(1)?.toIntOrNull()
        return value?.takeIf { it in 50..100 }
    }

    /** `"72 pts"` → 72 · `"100"` → 100. Whole-node match only, so labels are ignored. */
    fun parseScore(text: String): Int? =
        SCORE.find(normalize(text))?.groupValues?.get(1)?.toIntOrNull()

    /** `"1 reps"` → 1 */
    fun parseFirstInt(text: String): Int? =
        FIRST_INT.find(normalize(text))?.groupValues?.get(1)?.toIntOrNull()

    /** Parses a single integer, returning null if the text contains a range like `"51-119"`. */
    fun parseSingleInt(text: String): Int? {
        val t = normalize(text)
        if (INT_RANGE.containsMatchIn(t) || FLOAT_RANGE.containsMatchIn(t)) return null
        return FIRST_INT.find(t)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** `"0.05 km"` → 0.05f */
    fun parseKilometres(text: String): Float? =
        KILOMETRES.find(normalize(text))?.groupValues?.get(1)?.toFloatOrNull()

    /** `"82 bpm"`, `"88bpm"`, or bare integer bpm in 30..250 */
    fun parseBpm(text: String): Int? {
        val t = normalize(text)
        if (INT_RANGE.containsMatchIn(t) || FLOAT_RANGE.containsMatchIn(t)) return null
        BPM_VALUE.find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        if (!TIME_AGO.containsMatchIn(t) && !JUST_NOW.containsMatchIn(t)) {
            val v = FIRST_INT.find(t)?.groupValues?.get(1)?.toIntOrNull()
            if (v != null && v in 30..250) return v
        }
        return null
    }

    /** `"56.4 kg"` → 56.4f */
    fun parseWeightKg(text: String): Float? =
        KG_VALUE.find(normalize(text))?.groupValues?.get(1)?.toFloatOrNull()

    // ══════════════════════════════════════════════════════════════
    //  Screen: normalised nodes + label→value lookups
    // ══════════════════════════════════════════════════════════════

    private data class Legend(val deep: Int?, val light: Int?, val rem: Int?)

    private inner class Screen(captures: List<List<String>>) {

        /** All captures flattened, with [BREAK] marking each seam. */
        val nodes: List<String> = buildList {
            captures.forEachIndexed { index, capture ->
                if (index > 0) add(BREAK)
                capture.forEach { raw ->
                    val text = normalize(raw)
                    if (text.isNotEmpty()) add(text)
                }
            }
        }

        /** Nodes after [index] (nearest first), then before it, never crossing a [BREAK]. */
        fun window(index: Int, forward: Int, back: Int = 0): List<String> = buildList {
            var i = index + 1
            while (size < forward && i < nodes.size && nodes[i] != BREAK) add(nodes[i++])
            var taken = 0
            var j = index - 1
            while (taken < back && j >= 0 && nodes[j] != BREAK) {
                add(nodes[j--]); taken++
            }
        }

        /**
         * Finds a label with [labelMatch], then the first value [extract] accepts —
         * trying the label node itself (label and value share a node on some cards),
         * then each nearby node, then the whole window joined (values that Vivo
         * splits across nodes, e.g. `"10"` `"hrs,"` `"15"` `"mins"`).
         */
        fun <T : Any> valueNear(
            labelMatch: (String) -> Boolean,
            forward: Int = 4,
            back: Int = 0,
            extract: (String) -> T?,
        ): T? {
            for (i in nodes.indices) {
                val node = nodes[i]
                if (node == BREAK || !labelMatch(node)) continue
                extract(node)?.let { return it }
                val nearby = window(i, forward, back)
                for (text in nearby) extract(text)?.let { return it }
                extract(nearby.joinToString(" "))?.let { return it }
            }
            return null
        }

        /** First non-null [extract] over every node, ignoring labels entirely. */
        fun <T : Any> firstNotNull(extract: (String) -> T?): T? {
            for (node in nodes) {
                if (node == BREAK) continue
                extract(node)?.let { return it }
            }
            return null
        }

        /** A rating word ("Low" / "High" / "Normal") sitting next to a label. */
        fun ratingNear(labelMatch: (String) -> Boolean): String? =
            valueNear(labelMatch, forward = 3) { text ->
                text.takeIf { it.lowercase() in RATINGS }
            }

        /**
         * Fallback for when no `(22:04-08:41)` card header was captured: the
         * hypnogram x-axis ticks. The axis runs bed-time → wake-time, so the
         * **first and last** ticks are the ends of the window — everything in
         * between is a midnight / 03:00 / 06:00 gridline, which is why taking
         * the first *two* clocks would report a two-hour night.
         */
        fun clockPair(): Pair<String, String>? {
            val clocks = nodes.filter { it != BREAK }
                .mapNotNull { CLOCK.find(it)?.groupValues?.get(1) }
            if (clocks.size < 2) return null
            val first = clocks.first()
            val last = clocks.last()
            // Reject an implausible span, so an unrelated clock elsewhere on the
            // screen (a "last synced 09:15" stamp, say) cannot invent a session.
            val span = minutesBetween(first, last) ?: return null
            return if (span >= 60) first to last else null
        }

        private fun minutesBetween(from: String, to: String): Int? {
            val start = minutesOfDay(from) ?: return null
            val end = minutesOfDay(to) ?: return null
            return if (end >= start) end - start else end + 24 * 60 - start
        }

        private fun minutesOfDay(clock: String): Int? {
            val parts = clock.split(":")
            if (parts.size != 2) return null
            val hour = parts[0].toIntOrNull() ?: return null
            val minute = parts[1].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return hour * 60 + minute
        }

        /**
         * `"76"` `"/ 8,000steps"` → 76 to 8000. Matched on an exact label so the
         * "Exercise record" card and the "Exercise" tab in the nav bar are skipped:
         * neither is followed by a `current / goal` pair.
         */
        fun currentAndGoal(vararg labels: String, forward: Int = 3): Pair<Long, Long>? {
            for (i in nodes.indices) {
                if (labels.none { nodes[i].equals(it, ignoreCase = true) }) continue
                val joined = (listOf(nodes[i]) + window(i, forward)).joinToString(" ")
                val m = CURRENT_GOAL.find(joined) ?: continue
                val current = m.groupValues[1].toLongOrNull() ?: continue
                val goal = m.groupValues[2].toLongOrNull() ?: continue
                return current to goal
            }
            return null
        }

        /**
         * Score block: `"72"` `"pts"` `"Slept fairly well"` `"Compared to last time"`.
         * Anchored on "Compared to last time" and read backwards, which keeps it
         * clear of the other `N pts` value on the screen — deep sleep continuity.
         */
        fun sleepScore(): Pair<Int?, String?> {
            val anchor = nodes.indexOfFirst { it.contains("compared to last", true) }
            if (anchor >= 0) {
                var score: Int? = null
                var label: String? = null
                for (text in window(anchor, forward = 0, back = 5)) {
                    if (score == null) score = parseScore(text)
                    if (label == null && text.startsWith("Slept", true) && text.length < 40) label = text
                }
                if (score != null || label != null) return score to label
            }

            // No anchor: take a bare "N pts" node that is not the continuity score.
            for (i in nodes.indices) {
                val score = parseScore(nodes[i]) ?: continue
                if (!nodes[i].contains("pts", true)) continue
                val precedes = window(i, forward = 0, back = 3)
                if (precedes.any { it.contains("continuity", true) }) continue
                return score to nodes.firstOrNull { it.startsWith("Slept", true) }
            }
            return null to null
        }

        /**
         * Donut legend: Deep / Light / REM with a duration each. Handles both the
         * row-major order ("Deep", "1 hr, 50 mins", "Light", …) and the column-major
         * one ("Deep", "Light", "REM", "1 hr, 50 mins", …).
         *
         * Searched from the `(22:04-08:41)` card header onwards, because the
         * hypnogram above it carries its own "Awake / REM / Light / Deep" y-axis
         * labels that would otherwise be matched first.
         */
        fun legendDurations(): Legend {
            val anchor = nodes.indexOfFirst { TIME_WINDOW.containsMatchIn(it) }
            val from = if (anchor >= 0) anchor else 0

            fun locate(key: String): Int {
                val after = nodes.subList(from, nodes.size)
                    .indexOfFirst { it.equals(key, true) || it.equals("$key sleep", true) }
                if (after >= 0) return from + after
                return nodes.indexOfLast { it.equals(key, true) || it.equals("$key sleep", true) }
            }

            val deepAt = locate("deep")
            val lightAt = locate("light")
            val remAt = locate("rem")
            if (deepAt < 0 || lightAt < 0 || remAt < 0) return Legend(null, null, null)

            val columnMajor = lightAt == deepAt + 1 && remAt == lightAt + 1
            if (columnMajor) {
                val values = durationsAfter(remAt, 3)
                return Legend(values.getOrNull(0), values.getOrNull(1), values.getOrNull(2))
            }
            return Legend(
                durationBetween(deepAt, lightAt),
                durationBetween(lightAt, remAt),
                durationBetween(remAt, remAt + 4),
            )
        }

        private fun durationBetween(from: Int, until: Int): Int? {
            var i = from + 1
            while (i < minOf(until, nodes.size) && nodes[i] != BREAK) {
                parseDuration(nodes[i])?.let { return it }
                i++
            }
            return null
        }

        private fun durationsAfter(index: Int, count: Int): List<Int> = buildList {
            var i = index + 1
            while (size < count && i < nodes.size && nodes[i] != BREAK) {
                parseDuration(nodes[i])?.let { add(it) }
                i++
            }
        }
    }
}
