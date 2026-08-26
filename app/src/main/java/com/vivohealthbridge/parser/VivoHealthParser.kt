package com.vivohealthbridge.parser

import com.vivohealthbridge.data.models.ParsedHealthData

class VivoHealthParser {

    fun parseAllText(allTextNodes: List<String>): ParsedHealthData {
        var steps: Long? = null
        var heartRateBpm: Int? = null
        var restingHeartRateBpm: Int? = null
        var heartRateRangeMin: Int? = null
        var heartRateRangeMax: Int? = null
        var sleepTotalMinutes: Int? = null
        var sleepStartTime: String? = null
        var sleepEndTime: String? = null
        var deepSleepMinutes: Int? = null
        var lightSleepMinutes: Int? = null
        var remSleepMinutes: Int? = null
        var awakeMinutes: Int? = null
        var numberOfAwakenings: Int? = null
        var stressLevel: Int? = null
        var stressCategory: String? = null
        var averageStress: Int? = null
        var oxygenSaturation: Int? = null
        var averageOxygenSaturation: Int? = null
        var averageSleepSpO2: Int? = null
        var weightKg: Float? = null
        var exerciseDistanceKm: Float? = null

        var i = 0
        while (i < allTextNodes.size) {
            val text = allTextNodes[i].trim()

            // 1. Sleep total duration and time interval, e.g. "10hrs, 15mins (22:04-08:41)"
            if (text.contains("hr", ignoreCase = true) || text.contains("min", ignoreCase = true)) {
                val times = parseSleepTimes(text)
                if (times != null) {
                    sleepStartTime = times.first
                    sleepEndTime = times.second
                }
                if (sleepTotalMinutes == null && !text.contains("Deep", true) && !text.contains("Light", true) && !text.contains("REM", true) && !text.contains("awakening", true)) {
                    val dur = parseSleepDuration(text)
                    if (dur != null) sleepTotalMinutes = dur
                }
            }

            // 2. Sleep stages - Deep / Light / REM
            if (text.equals("Deep", ignoreCase = true) && i + 1 < allTextNodes.size) {
                deepSleepMinutes = parseSleepDuration(allTextNodes[i + 1]) ?: deepSleepMinutes
            }
            if (text.equals("Light", ignoreCase = true) && i + 1 < allTextNodes.size) {
                lightSleepMinutes = parseSleepDuration(allTextNodes[i + 1]) ?: lightSleepMinutes
            }
            if (text.equals("REM", ignoreCase = true) && i + 1 < allTextNodes.size) {
                remSleepMinutes = parseSleepDuration(allTextNodes[i + 1]) ?: remSleepMinutes
            }

            // 3. Number and duration of awakenings
            if (text.contains("Number of awakenings", ignoreCase = true) && i + 1 < allTextNodes.size) {
                numberOfAwakenings = allTextNodes[i + 1].replace(Regex("[^0-9]"), "").toIntOrNull()
            }
            if (text.contains("Duration of awakenings", ignoreCase = true) && i + 1 < allTextNodes.size) {
                awakeMinutes = parseSleepDuration(allTextNodes[i + 1])
            }

            // 4. Resting Heart Rate
            if (text.equals("Resting", ignoreCase = true) && i + 1 < allTextNodes.size) {
                restingHeartRateBpm = parseHeartRate(allTextNodes[i + 1])
            }

            // 5. Heart Rate (e.g. "76bpm" or "76 bpm")
            val hr = parseHeartRate(text)
            if (hr != null && heartRateBpm == null && !text.contains("Resting", true)) {
                heartRateBpm = hr
            }

            // 6. Heart rate range e.g. "51-133 bpm"
            if (text.contains("bpm", ignoreCase = true) && text.contains("-")) {
                val range = parseRange(text)
                if (range != null) {
                    heartRateRangeMin = range.first
                    heartRateRangeMax = range.second
                }
            }

            // 7. Oxygen Saturation (SpO2)
            if (text.contains("Average blood oxygen during sleep", ignoreCase = true) && i + 1 < allTextNodes.size) {
                averageSleepSpO2 = parseSpO2(allTextNodes[i + 1])
            } else if (text.contains("Average oxygen saturation", ignoreCase = true) && i + 1 < allTextNodes.size) {
                averageOxygenSaturation = parseSpO2(allTextNodes[i + 1])
            } else {
                val spo2 = parseSpO2(text)
                if (spo2 != null && oxygenSaturation == null && spo2 in 70..100) {
                    oxygenSaturation = spo2
                }
            }

            // 8. Stress
            if (text.contains("Average stress", ignoreCase = true) && i + 1 < allTextNodes.size) {
                val avgText = allTextNodes[i + 1]
                averageStress = avgText.replace(Regex("[^0-9]"), "").toIntOrNull()
            } else if (text.matches(Regex("""^\d{1,3}$"""))) {
                val num = text.toIntOrNull()
                if (num != null && num in 0..100 && i + 1 < allTextNodes.size) {
                    val next = allTextNodes[i + 1].trim()
                    if (next.equals("Relaxed", true) || next.equals("Moderate", true) || next.equals("High", true)) {
                        stressLevel = num
                        stressCategory = next
                    }
                }
            }

            // 9. Weight (e.g. "56.4 kg")
            val weight = parseWeight(text)
            if (weight != null && weightKg == null) {
                weightKg = weight
            }

            // 10. Exercise distance (e.g. "0.34km")
            if (text.endsWith("km", ignoreCase = true)) {
                val dist = text.replace("km", "", ignoreCase = true).trim().toFloatOrNull()
                if (dist != null && exerciseDistanceKm == null) {
                    exerciseDistanceKm = dist
                }
            }

            // 11. Steps (Chinese or English fallback)
            if (text.contains("步") || text.contains("steps", ignoreCase = true)) {
                val numStr = text.replace(Regex("[^0-9]"), "")
                val parsedSteps = numStr.toLongOrNull()
                if (parsedSteps != null && parsedSteps in 1..200000) {
                    steps = parsedSteps
                }
            }

            i++
        }

        return ParsedHealthData(
            steps = steps,
            heartRateBpm = heartRateBpm,
            restingHeartRateBpm = restingHeartRateBpm,
            heartRateRangeMin = heartRateRangeMin,
            heartRateRangeMax = heartRateRangeMax,
            sleepTotalMinutes = sleepTotalMinutes,
            sleepStartTime = sleepStartTime,
            sleepEndTime = sleepEndTime,
            deepSleepMinutes = deepSleepMinutes,
            lightSleepMinutes = lightSleepMinutes,
            remSleepMinutes = remSleepMinutes,
            awakeMinutes = awakeMinutes,
            numberOfAwakenings = numberOfAwakenings,
            stressLevel = stressLevel,
            stressCategory = stressCategory,
            averageStress = averageStress,
            oxygenSaturation = oxygenSaturation,
            averageOxygenSaturation = averageOxygenSaturation,
            averageSleepSpO2 = averageSleepSpO2,
            weightKg = weightKg,
            exerciseDistanceKm = exerciseDistanceKm
        )
    }

    fun parseSleepDuration(text: String): Int? {
        val clean = text.trim()
        val regex = Regex("""(?:(\d+)\s*hr[s]?\s*,?\s*)?(?:(\d+)\s*min[s]?)?""", RegexOption.IGNORE_CASE)
        val match = regex.find(clean) ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        val minutes = match.groupValues[2].toIntOrNull() ?: 0
        return if (hours > 0 || minutes > 0) hours * 60 + minutes else null
    }

    fun parseSleepTimes(text: String): Pair<String, String>? {
        val regex = Regex("""\((\d{2}:\d{2})-(\d{2}:\d{2})\)""")
        val match = regex.find(text) ?: return null
        return Pair(match.groupValues[1], match.groupValues[2])
    }

    fun parseHeartRate(text: String): Int? {
        val regex = Regex("""^(\d{2,3})\s*bpm$""", RegexOption.IGNORE_CASE)
        val match = regex.find(text.trim()) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    fun parseSpO2(text: String): Int? {
        val regex = Regex("""^(\d{2,3})\s*%$""")
        val match = regex.find(text.trim()) ?: return null
        val value = match.groupValues[1].toIntOrNull()
        return if (value != null && value in 50..100) value else null
    }

    fun parseWeight(text: String): Float? {
        val regex = Regex("""^([\d\.]+)\s*kg$""", RegexOption.IGNORE_CASE)
        val match = regex.find(text.trim()) ?: return null
        return match.groupValues[1].toFloatOrNull()
    }

    fun parseRange(text: String): Pair<Int, Int>? {
        val regex = Regex("""(\d+)\s*-\s*(\d+)""")
        val match = regex.find(text.trim()) ?: return null
        val min = match.groupValues[1].toIntOrNull()
        val max = match.groupValues[2].toIntOrNull()
        return if (min != null && max != null) Pair(min, max) else null
    }
}
