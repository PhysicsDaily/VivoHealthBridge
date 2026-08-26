package com.vivohealthbridge.parser

import android.util.Log
import com.vivohealthbridge.data.models.ParsedHealthData

/**
 * Parses text nodes collected from the Vivo Health app.
 *
 * The sleep detail screen is structured (top → bottom) as:
 *   1. "Total sleep duration today"  +  e.g. "7hrs, 15mins"
 *   2. Sleep chart                    +  "Total sleep duration" repeated
 *   3. Sliding stat cards (swipe right to reveal):
 *        • Sleeping heart rate        "50-72 bpm"
 *        • Sleep respiratory rate     "12-18 times/min"  (or similar)
 *        • Sleep SpO2                 "95-99%"
 *        • Sleep HRV                  "35-80 ms"
 *      Sleep score badge              e.g. "78"
 *   4. "More analysis" section:
 *        • Deep / Light / REM duration + proportion %
 *        • Number of awakenings        e.g. "3"
 *        • Duration of awakenings      e.g. "25mins"
 *        • Deep sleep continuity       e.g. "Good" / "Normal" / "Poor"
 *        • Average blood oxygen during sleep  e.g. "97%"
 */
class VivoHealthParser {

    companion object {
        private const val TAG = "VivoHealthParser"
    }

    // ══════════════════════════════════════════════════════
    //  SLEEP PARSER
    // ══════════════════════════════════════════════════════

    /**
     * Parse all text collected from the sleep detail screen.
     * [sleepScreenTexts] may contain texts from multiple captures
     * (initial load, after sliding right, after expanding More analysis).
     */
    fun parseSleepScreen(sleepScreenTexts: List<String>): ParsedHealthData {
        var sleepTotalMinutes: Int? = null
        var sleepStartTime: String? = null
        var sleepEndTime: String? = null
        var sleepScore: Int? = null

        var sleepHeartRateMin: Int? = null
        var sleepHeartRateMax: Int? = null
        var sleepRespMin: Float? = null
        var sleepRespMax: Float? = null
        var sleepSpo2Min: Int? = null
        var sleepSpo2Max: Int? = null
        var sleepHrvMin: Int? = null
        var sleepHrvMax: Int? = null

        var deepMin: Int? = null
        var lightMin: Int? = null
        var remMin: Int? = null
        var deepPct: Int? = null
        var lightPct: Int? = null
        var remPct: Int? = null
        var awakenings: Int? = null
        var awakeMin: Int? = null
        var continuity: String? = null
        var avgSleepSpo2: Int? = null

        var i = 0
        while (i < sleepScreenTexts.size) {
            val text = sleepScreenTexts[i].trim()
            val next = sleepScreenTexts.getOrNull(i + 1)?.trim()
            Log.v(TAG, "sleepText[$i] = \"$text\"")

            // ── Total sleep duration + time window ─────────────
            //   e.g. "7hrs, 15mins"  or  "10hrs, 15mins (22:04-08:41)"
            if (text.contains("hr", ignoreCase = true) || text.contains("min", ignoreCase = true)) {
                parseSleepTimes(text)?.let { (s, e) ->
                    if (sleepStartTime == null) sleepStartTime = s
                    if (sleepEndTime == null) sleepEndTime = e
                }

                val isStageLine = text.contains("deep", true) ||
                        text.contains("light", true) ||
                        text.contains("rem", true) ||
                        text.contains("awaken", true)
                if (sleepTotalMinutes == null && !isStageLine) {
                    parseSleepDuration(text)?.let { sleepTotalMinutes = it }
                }
            }

            // ── Sleep score ───────────────────────────────────
            if (text.contains("sleep score", ignoreCase = true) ||
                (text.contains("score", ignoreCase = true) && sleepScore == null)
            ) {
                sleepScore = next?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                    ?: text.replace(Regex("[^0-9]"), "").toIntOrNull()
            }

            // ── Sleeping heart rate range ─────────────────────
            if (text.contains("sleeping heart rate", ignoreCase = true)) {
                val range = parseIntRange(next ?: text)
                if (range != null) {
                    sleepHeartRateMin = range.first
                    sleepHeartRateMax = range.second
                }
            }

            // ── Sleep respiratory rate range ──────────────────
            if (text.contains("respiratory", ignoreCase = true) ||
                text.contains("breathing", ignoreCase = true)
            ) {
                val range = parseFloatRange(next ?: text)
                if (range != null) {
                    sleepRespMin = range.first
                    sleepRespMax = range.second
                }
            }

            // ── Sleep SpO2 range ──────────────────────────────
            if (text.contains("spo2", ignoreCase = true) ||
                (text.contains("blood oxygen", ignoreCase = true) && !text.contains("average", true))
            ) {
                val range = parseIntRange(next ?: text)
                if (range != null && sleepSpo2Min == null) {
                    sleepSpo2Min = range.first
                    sleepSpo2Max = range.second
                }
            }

            // ── Sleep HRV range ───────────────────────────────
            if (text.contains("hrv", ignoreCase = true) ||
                text.contains("heart rate variability", ignoreCase = true)
            ) {
                val range = parseIntRange(next ?: text)
                if (range != null) {
                    sleepHrvMin = range.first
                    sleepHrvMax = range.second
                }
            }

            // ── "More analysis" stage durations + proportions ─
            if (text.equals("deep", ignoreCase = true) || text.equals("deep sleep", ignoreCase = true)) {
                deepMin = parseSleepDuration(next ?: "") ?: deepMin
                deepPct = parsePercent(next ?: "")
                    ?: parsePercent(sleepScreenTexts.getOrNull(i + 2) ?: "")
                    ?: deepPct
            }
            if (text.equals("light", ignoreCase = true) || text.equals("light sleep", ignoreCase = true)) {
                lightMin = parseSleepDuration(next ?: "") ?: lightMin
                lightPct = parsePercent(next ?: "")
                    ?: parsePercent(sleepScreenTexts.getOrNull(i + 2) ?: "")
                    ?: lightPct
            }
            if (text.equals("rem", ignoreCase = true) || text.equals("rem sleep", ignoreCase = true)) {
                remMin = parseSleepDuration(next ?: "") ?: remMin
                remPct = parsePercent(next ?: "")
                    ?: parsePercent(sleepScreenTexts.getOrNull(i + 2) ?: "")
                    ?: remPct
            }

            // ── Awakenings ────────────────────────────────────
            if (text.contains("number of awakenings", ignoreCase = true) ||
                text.equals("awakenings", ignoreCase = true)
            ) {
                awakenings = (next ?: text).replace(Regex("[^0-9]"), "").toIntOrNull() ?: awakenings
            }
            if (text.contains("duration of awakenings", ignoreCase = true) ||
                text.contains("awake duration", ignoreCase = true)
            ) {
                awakeMin = parseSleepDuration(next ?: "") ?: awakeMin
            }

            // ── Deep sleep continuity ─────────────────────────
            if (text.contains("deep sleep continuity", ignoreCase = true) ||
                text.contains("continuity", ignoreCase = true)
            ) {
                continuity = next?.takeIf { it.isNotBlank() } ?: continuity
            }

            // ── Average blood oxygen during sleep ─────────────
            if (text.contains("average blood oxygen during sleep", ignoreCase = true)) {
                avgSleepSpo2 = parsePercent(next ?: "") ?: avgSleepSpo2
            }

            i++
        }

        return ParsedHealthData(
            sleepTotalMinutes = sleepTotalMinutes,
            sleepStartTime = sleepStartTime,
            sleepEndTime = sleepEndTime,
            sleepScore = sleepScore,
            sleepHeartRateMin = sleepHeartRateMin,
            sleepHeartRateMax = sleepHeartRateMax,
            sleepRespiratoryRateMin = sleepRespMin,
            sleepRespiratoryRateMax = sleepRespMax,
            sleepSpo2Min = sleepSpo2Min,
            sleepSpo2Max = sleepSpo2Max,
            sleepHrvMin = sleepHrvMin,
            sleepHrvMax = sleepHrvMax,
            deepSleepMinutes = deepMin,
            lightSleepMinutes = lightMin,
            remSleepMinutes = remMin,
            deepSleepPct = deepPct,
            lightSleepPct = lightPct,
            remSleepPct = remPct,
            numberOfAwakenings = awakenings,
            awakeMinutes = awakeMin,
            deepSleepContinuity = continuity,
            averageSleepSpo2 = avgSleepSpo2,
        )
    }

    // ══════════════════════════════════════════════════════
    //  GENERIC PARSER (kept for other metrics – later phases)
    // ══════════════════════════════════════════════════════

    /** Legacy all-in-one parse (still used by Manual Entry). */
    fun parseAllText(allTextNodes: List<String>): ParsedHealthData {
        // First try the dedicated sleep parser
        val sleep = parseSleepScreen(allTextNodes)

        var steps: Long? = null
        var heartRateBpm: Int? = null
        var restingHeartRateBpm: Int? = null
        var heartRateRangeMin: Int? = null
        var heartRateRangeMax: Int? = null
        var stressLevel: Int? = null
        var stressCategory: String? = null
        var averageStress: Int? = null
        var oxygenSaturation: Int? = null
        var averageOxygenSaturation: Int? = null
        var weightKg: Float? = null
        var exerciseDistanceKm: Float? = null

        var i = 0
        while (i < allTextNodes.size) {
            val text = allTextNodes[i].trim()

            // Resting HR
            if (text.equals("Resting", ignoreCase = true) && i + 1 < allTextNodes.size) {
                restingHeartRateBpm = parseHeartRate(allTextNodes[i + 1])
            }

            // Heart rate single value
            val hr = parseHeartRate(text)
            if (hr != null && heartRateBpm == null && !text.contains("Resting", true)) {
                heartRateBpm = hr
            }

            // HR range "51-133 bpm"
            if (text.contains("bpm", ignoreCase = true) && text.contains("-")) {
                parseRange(text)?.let {
                    heartRateRangeMin = it.first
                    heartRateRangeMax = it.second
                }
            }

            // SpO2
            if (text.contains("Average oxygen saturation", ignoreCase = true) && i + 1 < allTextNodes.size) {
                averageOxygenSaturation = parseSpO2(allTextNodes[i + 1])
            } else {
                val spo2 = parseSpO2(text)
                if (spo2 != null && oxygenSaturation == null && spo2 in 70..100) {
                    oxygenSaturation = spo2
                }
            }

            // Stress
            if (text.contains("Average stress", ignoreCase = true) && i + 1 < allTextNodes.size) {
                averageStress = allTextNodes[i + 1].replace(Regex("[^0-9]"), "").toIntOrNull()
            } else if (text.matches(Regex("""^\d{1,3}$"""))) {
                val num = text.toIntOrNull()
                if (num != null && num in 0..100 && i + 1 < allTextNodes.size) {
                    val nextTxt = allTextNodes[i + 1].trim()
                    if (nextTxt.equals("Relaxed", true) || nextTxt.equals("Moderate", true) || nextTxt.equals("High", true)) {
                        stressLevel = num
                        stressCategory = nextTxt
                    }
                }
            }

            // Weight
            parseWeight(text)?.let { if (weightKg == null) weightKg = it }

            // Exercise distance
            if (text.endsWith("km", ignoreCase = true)) {
                val dist = text.replace("km", "", ignoreCase = true).trim().toFloatOrNull()
                if (dist != null && exerciseDistanceKm == null) exerciseDistanceKm = dist
            }

            // Steps
            if (text.contains("步") || text.contains("steps", ignoreCase = true)) {
                val parsed = text.replace(Regex("[^0-9]"), "").toLongOrNull()
                if (parsed != null && parsed in 1..200000) steps = parsed
            }

            i++
        }

        return sleep.copy(
            steps = steps,
            heartRateBpm = heartRateBpm,
            restingHeartRateBpm = restingHeartRateBpm,
            heartRateRangeMin = heartRateRangeMin,
            heartRateRangeMax = heartRateRangeMax,
            stressLevel = stressLevel,
            stressCategory = stressCategory,
            averageStress = averageStress,
            oxygenSaturation = oxygenSaturation,
            averageOxygenSaturation = averageOxygenSaturation,
            weightKg = weightKg,
            exerciseDistanceKm = exerciseDistanceKm,
        )
    }

    // ══════════════════════════════════════════════════════
    //  LOW-LEVEL HELPERS
    // ══════════════════════════════════════════════════════

    /** "7hrs, 15mins" → 435  |  "25mins" → 25  |  "1hr 30min" → 90 */
    fun parseSleepDuration(text: String): Int? {
        val clean = text.trim()
        val regex = Regex("""(?:(\d+)\s*hr[s]?\s*,?\s*)?(?:(\d+)\s*min[s]?)?""", RegexOption.IGNORE_CASE)
        val match = regex.find(clean) ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        val minutes = match.groupValues[2].toIntOrNull() ?: 0
        return if (hours > 0 || minutes > 0) hours * 60 + minutes else null
    }

    /** "(22:04-08:41)" → Pair("22:04", "08:41") */
    fun parseSleepTimes(text: String): Pair<String, String>? {
        val regex = Regex("""\(?(\d{2}:\d{2})\s*-\s*(\d{2}:\d{2})\)?""")
        val match = regex.find(text) ?: return null
        return Pair(match.groupValues[1], match.groupValues[2])
    }

    /** "76bpm" / "76 bpm" → 76 */
    fun parseHeartRate(text: String): Int? {
        val regex = Regex("""^(\d{2,3})\s*bpm$""", RegexOption.IGNORE_CASE)
        val match = regex.find(text.trim()) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /** "95%" → 95 (only if in 50..100) */
    fun parseSpO2(text: String): Int? {
        val regex = Regex("""^(\d{2,3})\s*%$""")
        val match = regex.find(text.trim()) ?: return null
        val v = match.groupValues[1].toIntOrNull()
        return if (v != null && v in 50..100) v else null
    }

    /** "56.4 kg" → 56.4f */
    fun parseWeight(text: String): Float? {
        val regex = Regex("""^([\d.]+)\s*kg$""", RegexOption.IGNORE_CASE)
        val match = regex.find(text.trim()) ?: return null
        return match.groupValues[1].toFloatOrNull()
    }

    /** "51-133 bpm" → Pair(51, 133) */
    fun parseRange(text: String): Pair<Int, Int>? {
        val regex = Regex("""(\d+)\s*[-–]\s*(\d+)""")
        val match = regex.find(text.trim()) ?: return null
        val min = match.groupValues[1].toIntOrNull()
        val max = match.groupValues[2].toIntOrNull()
        return if (min != null && max != null) Pair(min, max) else null
    }

    /** "50-72" or "50-72 bpm" → Pair(50, 72) */
    private fun parseIntRange(text: String): Pair<Int, Int>? = parseRange(text)

    /** "12.5-18.3" or "12-18 times/min" → Pair(12f, 18f) */
    private fun parseFloatRange(text: String): Pair<Float, Float>? {
        val regex = Regex("""([\d.]+)\s*[-–]\s*([\d.]+)""")
        val match = regex.find(text.trim()) ?: return null
        val min = match.groupValues[1].toFloatOrNull()
        val max = match.groupValues[2].toFloatOrNull()
        return if (min != null && max != null) Pair(min, max) else null
    }

    /** "35%" → 35 */
    private fun parsePercent(text: String): Int? {
        val regex = Regex("""(\d{1,3})\s*%""")
        val match = regex.find(text.trim()) ?: return null
        return match.groupValues[1].toIntOrNull()
    }
}
