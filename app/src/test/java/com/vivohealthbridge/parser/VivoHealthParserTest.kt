package com.vivohealthbridge.parser

import com.vivohealthbridge.data.models.DailyActivity
import com.vivohealthbridge.data.models.HeartRateDetail
import com.vivohealthbridge.data.models.MetricRange
import com.vivohealthbridge.data.models.OxygenSaturationDetail
import com.vivohealthbridge.data.models.ParsedHealthData
import com.vivohealthbridge.data.models.SleepDetail
import com.vivohealthbridge.data.models.StressDetail
import com.vivohealthbridge.data.models.merge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts the parser against the exact strings the Vivo Health app renders,
 * transcribed node-for-node from screenshots of the Health tab and the Sleep
 * detail screen.
 *
 * The escapes are deliberate — they are the whole reason this test exists:
 *  - `−` MINUS SIGN joins every range (`51−119 bpm`), not ASCII `-`
 *  - `₂` SUBSCRIPT TWO spells SpO₂, so a search for "spo2" never matches
 *  - ` ` NO-BREAK SPACE pads the ring goals (`/ 8,000steps`)
 */
class VivoHealthParserTest {

    private val parser = VivoHealthParser()

    companion object {
        /** U+2212 MINUS SIGN — what Vivo uses between the ends of a range. */
        private const val MINUS = "−"

        /** U+00A0 NO-BREAK SPACE — what Vivo uses inside the ring goals. */
        private const val NBSP = " "

        /** U+2082 SUBSCRIPT TWO — the "2" in SpO₂. */
        private const val SUB2 = "₂"
    }

    // ══════════════════════════════════════════════════════════════
    //  Text normalisation
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `normalize flattens Vivo typography to ASCII`() {
        assertEquals("51-119 bpm", parser.normalize("51${MINUS}119 bpm"))
        assertEquals("10.5-25.5 bpm", parser.normalize("10.5–25.5 bpm"))
        assertEquals("SpO2 during sleep", parser.normalize("SpO${SUB2} during sleep"))
        assertEquals("/ 8000steps", parser.normalize("/${NBSP}8,000steps"))
        assertEquals("94-100 %", parser.normalize("94${MINUS}100${NBSP}%"))
    }

    @Test
    fun `int range never fires inside a decimal pair`() {
        assertNull(parser.parseIntRange("10.5${MINUS}25.5"))
        assertEquals(MetricRange(51, 119), parser.parseIntRange("51${MINUS}119 bpm"))
        assertEquals(MetricRange(10.5f, 25.5f), parser.parseFloatRange("10.5${MINUS}25.5 bpm"))
    }

    @Test
    fun `durations parse in every form the app uses`() {
        assertEquals(615, parser.parseDuration("10 hrs, 15 mins"))
        assertEquals(110, parser.parseDuration("1 hr, 50 mins"))
        assertEquals(354, parser.parseDuration("5 hrs, 54 mins"))
        assertEquals(151, parser.parseDuration("2 hrs, 31 mins"))
        assertEquals(22, parser.parseDuration("22 mins"))
        assertNull(parser.parseDuration("Total sleep duration"))
    }

    // ══════════════════════════════════════════════════════════════
    //  Step 1 · Home screen activity rings
    // ══════════════════════════════════════════════════════════════

    /** The four rings + distance at the top of the Health tab. */
    private val homeCapture = listOf(
        "Health",
        "Steps", "76", "/${NBSP}8,000steps",
        "Exercise", "0", "/${NBSP}30min",
        "Calories", "4", "/${NBSP}400kcal",
        "Stand", "4", "/${NBSP}12Hour",
        "Total distance", "0.05 km",
        "Sleep", "10 hrs, 15 mins",
        "Exercise record", "No data yet",
        // bottom navigation bar
        "Health", "Exercise", "Device", "Mine",
    )

    @Test
    fun `home rings parse with their goals`() {
        val activity = parser.parseHomeActivity(homeCapture)

        assertEquals(76L, activity.steps)
        assertEquals(8000L, activity.stepsGoal)
        assertEquals(0, activity.exerciseMinutes)
        assertEquals(30, activity.exerciseGoalMinutes)
        assertEquals(4, activity.activeCalories)
        assertEquals(400, activity.activeCaloriesGoal)
        assertEquals(4, activity.standHours)
        assertEquals(12, activity.standGoalHours)
        assertEquals(0.05f, activity.distanceKm!!, 0.0001f)
        assertTrue(activity.hasData())
    }

    @Test
    fun `the Exercise record card and the nav bar tab are not read as a ring`() {
        val activity = parser.parseHomeActivity(
            listOf("Exercise record", "3 sessions", "Health", "Exercise", "Device", "Mine")
        )

        assertNull(activity.exerciseMinutes)
        assertNull(activity.exerciseGoalMinutes)
        assertFalse(activity.hasData())
    }

    // ══════════════════════════════════════════════════════════════
    //  Step 2 · Sleep detail screen, one capture per read
    // ══════════════════════════════════════════════════════════════

    /** First read, once "Loading" has cleared. */
    private val sleepLoaded = listOf(
        "Sleep",
        "Total sleep duration", "10 hrs, 15 mins", "Today",
        // hypnogram: stage labels down the y-axis, clock ticks along the x-axis
        "Awake", "REM", "Light", "Deep",
        "22:04", "00:00", "03:00", "06:00", "08:41",
        "Total sleep duration", "10 hrs, 15 mins (22:04${MINUS}08:41)",
        "72", "pts", "Slept fairly well", "Compared to last time", "+8",
        "More analysis",
    )

    /** After swiping the stat carousel from right to left four times. */
    private val sleepCards = listOf(
        "Sleep heart rate", "51${MINUS}119", "bpm", "Avg 62 bpm",
        "Sleep respiratory rate", "10.5${MINUS}25.5", "bpm",
        "SpO${SUB2} during sleep", "94${MINUS}100", "%",
        "Sleep HRV", "33${MINUS}88", "ms",
    )

    /** After tapping "More analysis" and scrolling it into view. */
    private val sleepAnalysis = listOf(
        "More analysis",
        "Total sleep duration", "10 hrs, 15 mins (22:04${MINUS}08:41)",
        "Deep sleep", "1 hr, 50 mins",
        "Light sleep", "5 hrs, 54 mins",
        "REM", "2 hrs, 31 mins",
        "Deep sleep proportion", "17%", "Low",
        "Light sleep proportion", "58%", "High",
        "REM proportion", "25%", "Normal",
        "Number of awakenings", "1 reps",
        "Duration of awakenings", "22 mins",
        "Deep sleep continuity", "100 pts", "Normal",
        "Average blood oxygen during sleep", "96%",
    )

    private val allSleepCaptures = listOf(sleepLoaded, sleepCards, sleepAnalysis)

    @Test
    fun `sleep header and hypnogram window`() {
        val sleep = parser.parseSleepDetail(allSleepCaptures)

        assertEquals(615, sleep.totalMinutes)
        assertEquals("22:04", sleep.bedTime)
        assertEquals("08:41", sleep.wakeTime)
        // 615 minutes asleep inside a 637-minute window — the 22 awake minutes.
        assertEquals(637, sleep.windowMinutes())
        assertTrue(sleep.hasData())
    }

    @Test
    fun `the four sliding vitals cards`() {
        val sleep = parser.parseSleepDetail(allSleepCaptures)

        assertEquals(MetricRange(51, 119), sleep.heartRate)
        assertEquals(MetricRange(10.5f, 25.5f), sleep.respiratoryRate)
        assertEquals(MetricRange(94, 100), sleep.spo2)
        assertEquals(MetricRange(33, 88), sleep.hrv)
    }

    @Test
    fun `sleep score is read from the score block, not from deep-sleep continuity`() {
        val sleep = parser.parseSleepDetail(allSleepCaptures)

        assertEquals(72, sleep.score)
        assertEquals("Slept fairly well", sleep.scoreLabel)
        assertEquals(100, sleep.continuityScore)
        assertEquals("Normal", sleep.continuityRating)
    }

    @Test
    fun `More analysis stages come from the donut legend, not the hypnogram axis`() {
        val sleep = parser.parseSleepDetail(allSleepCaptures)

        assertEquals(110, sleep.deepMinutes)
        assertEquals(354, sleep.lightMinutes)
        assertEquals(151, sleep.remMinutes)
        assertFalse(sleep.stagesDerived)
        // 110 + 354 + 151 == the reported total.
        assertEquals(615, sleep.deepMinutes!! + sleep.lightMinutes!! + sleep.remMinutes!!)
    }

    @Test
    fun `proportions carry both a percentage and a rating`() {
        val sleep = parser.parseSleepDetail(allSleepCaptures)

        assertEquals(17, sleep.deepPercent)
        assertEquals(58, sleep.lightPercent)
        assertEquals(25, sleep.remPercent)
        assertEquals("Low", sleep.deepRating)
        assertEquals("High", sleep.lightRating)
        assertEquals("Normal", sleep.remRating)
    }

    @Test
    fun `awakenings and average blood oxygen`() {
        val sleep = parser.parseSleepDetail(allSleepCaptures)

        assertEquals(1, sleep.awakenings)
        assertEquals(22, sleep.awakeMinutes)
        assertEquals(96, sleep.averageSpo2)
    }

    // ══════════════════════════════════════════════════════════════
    //  Degraded captures
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `stage minutes fall back to percent of total when the legend is missed`() {
        val sleep = parser.parseSleepDetail(
            listOf(
                sleepLoaded,
                listOf(
                    "Deep sleep proportion", "17%", "Low",
                    "Light sleep proportion", "58%", "High",
                    "REM proportion", "25%", "Normal",
                ),
            )
        )

        assertTrue(sleep.stagesDerived)
        assertEquals(104, sleep.deepMinutes)   // 615 * 17%
        assertEquals(356, sleep.lightMinutes)  // 615 * 58%
        assertEquals(153, sleep.remMinutes)    // 615 * 25%
    }

    @Test
    fun `without the card header the window comes from the first and last axis tick`() {
        val sleep = parser.parseSleepDetail(
            listOf(
                "Total sleep duration", "10 hrs, 15 mins", "Today",
                "Awake", "REM", "Light", "Deep",
                "22:04", "00:00", "03:00", "06:00", "08:41",
            )
        )

        // Not 22:04–00:00: the middle ticks are midnight/03:00/06:00 gridlines.
        assertEquals("22:04", sleep.bedTime)
        assertEquals("08:41", sleep.wakeTime)
        assertEquals(637, sleep.windowMinutes())
    }

    @Test
    fun `a single stray clock cannot invent a sleep window`() {
        val sleep = parser.parseSleepDetail(
            listOf("Total sleep duration", "10 hrs, 15 mins", "Last synced", "09:15")
        )

        assertEquals(615, sleep.totalMinutes)
        assertNull(sleep.bedTime)
        assertNull(sleep.wakeTime)
        assertNull(sleep.windowMinutes())
    }

    @Test
    fun `lookups never cross a capture boundary`() {
        // "Sleep heart rate" ends one capture; the next opens with an unrelated
        // range. Reading across the seam would report 999-999 bpm.
        val sleep = parser.parseSleepDetail(
            listOf(
                listOf("Sleep heart rate"),
                listOf("999${MINUS}999", "steps"),
            )
        )

        assertNull(sleep.heartRate)
    }

    @Test
    fun `an empty capture set yields no data`() {
        val sleep = parser.parseSleepDetail(emptyList<List<String>>())

        assertFalse(sleep.hasData())
        assertNull(sleep.totalMinutes)
        assertNull(sleep.score)
    }

    // ══════════════════════════════════════════════════════════════
    //  Step 3 · Live capture merge and aggregation tests
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `DailyActivity merge preserves earlier reads and overwrites with newer non-null values`() {
        val read1 = DailyActivity(steps = 5000L, stepsGoal = 8000L, activeCalories = 250)
        val read2 = DailyActivity(steps = 5500L, distanceKm = 3.2f, exerciseMinutes = 45)

        val merged = read1.merge(read2)
        assertEquals(5500L, merged.steps)
        assertEquals(8000L, merged.stepsGoal)
        assertEquals(250, merged.activeCalories)
        assertEquals(3.2f, merged.distanceKm!!, 0.001f)
        assertEquals(45, merged.exerciseMinutes)
    }

    @Test
    fun `SleepDetail merge aggregates vitals and stages across carousel swipes and scrolls`() {
        val headerRead = SleepDetail(totalMinutes = 480, bedTime = "23:00", wakeTime = "07:00", score = 82)
        val vitalsRead = SleepDetail(
            heartRate = MetricRange(55, 95),
            spo2 = MetricRange(96, 99)
        )
        val stagesRead = SleepDetail(
            deepMinutes = 90,
            lightMinutes = 240,
            remMinutes = 120,
            awakenings = 2
        )

        val accumulated = headerRead.merge(vitalsRead).merge(stagesRead)
        assertEquals(480, accumulated.totalMinutes)
        assertEquals("23:00", accumulated.bedTime)
        assertEquals("07:00", accumulated.wakeTime)
        assertEquals(82, accumulated.score)
        assertEquals(MetricRange(55, 95), accumulated.heartRate)
        assertEquals(MetricRange(96, 99), accumulated.spo2)
        assertEquals(90, accumulated.deepMinutes)
        assertEquals(240, accumulated.lightMinutes)
        assertEquals(120, accumulated.remMinutes)
        assertEquals(2, accumulated.awakenings)
        assertEquals(2, accumulated.vitalsCount)
        assertEquals(3, accumulated.stagesCount)
    }

    @Test
    fun `ParsedHealthData merge and summaryString reflect aggregated live state`() {
        val act = DailyActivity(steps = 8432L)
        val sleep = SleepDetail(
            totalMinutes = 440,
            heartRate = MetricRange(50, 110),
            spo2 = MetricRange(95, 100),
            deepMinutes = 70,
            lightMinutes = 250,
            remMinutes = 100
        )
        val data1 = ParsedHealthData(activity = act)
        val data2 = ParsedHealthData(sleep = sleep)

        val merged = data1.merge(data2)
        assertEquals(8432L, merged.activity?.steps)
        assertEquals(440, merged.sleep?.totalMinutes)
        assertEquals(2, merged.sleep?.vitalsCount)
        assertEquals(3, merged.sleep?.stagesCount)

        val summary = merged.summaryString()
        assertTrue(summary.contains("8432 steps"))
        assertTrue(summary.contains("7h 20m sleep"))
        assertTrue(summary.contains("2 vitals"))
        assertTrue(summary.contains("3 stages"))
    }

    // ══════════════════════════════════════════════════════════════
    //  Step 4 · Standalone Heart Rate screen
    // ══════════════════════════════════════════════════════════════

    private val heartRateCapture = listOf(
        "Heart rate", "Today",
        "Heart rate range", "51${MINUS}119", "bpm",
        "Resting heart rate", "58", "bpm",
        "Current", "72", "bpm",
    )

    @Test
    fun `standalone heart rate screen parses range, resting HR and current HR`() {
        val hr = parser.parseHeartRateDetail(heartRateCapture)

        assertEquals(MetricRange(51, 119), hr.range)
        assertEquals(58, hr.restingBpm)
        assertEquals(72, hr.currentBpm)
        assertTrue(hr.hasData())
    }

    @Test
    fun `heart rate screen with typography and compact layout`() {
        val hr = parser.parseHeartRateDetail(
            listOf("Heart rate", "55${MINUS}130${NBSP}bpm", "Resting HR", "62${NBSP}bpm")
        )

        assertEquals(MetricRange(55, 130), hr.range)
        assertEquals(62, hr.restingBpm)
        assertTrue(hr.hasData())
    }

    @Test
    fun `empty heart rate capture yields no data`() {
        val hr = parser.parseHeartRateDetail(emptyList<String>())
        assertFalse(hr.hasData())
        assertNull(hr.range)
        assertNull(hr.restingBpm)
    }

    // ══════════════════════════════════════════════════════════════
    //  Step 5 · Standalone Stress screen
    // ══════════════════════════════════════════════════════════════

    private val stressCapture = listOf(
        "Stress", "Today",
        "Average stress", "32", "Relaxed",
        "Stress range", "10${MINUS}65",
    )

    @Test
    fun `stress screen parses range, average score, and category`() {
        val stress = parser.parseStressDetail(stressCapture)

        assertEquals(MetricRange(10, 65), stress.range)
        assertEquals(32, stress.average)
        assertEquals("Relaxed", stress.category)
        assertTrue(stress.hasData())
    }

    @Test
    fun `stress screen infers category from average when explicit category missing`() {
        val stress = parser.parseStressDetail(
            listOf("Stress", "Average", "52", "Range", "25${MINUS}75")
        )

        assertEquals(MetricRange(25, 75), stress.range)
        assertEquals(52, stress.average)
        assertEquals("Moderate", stress.category)
        assertTrue(stress.hasData())
    }

    @Test
    fun `empty stress capture yields no data`() {
        val stress = parser.parseStressDetail(emptyList<String>())
        assertFalse(stress.hasData())
        assertNull(stress.range)
        assertNull(stress.average)
        assertNull(stress.category)
    }

    // ══════════════════════════════════════════════════════════════
    //  Step 6 · Standalone Oxygen Saturation (SpO₂) screen
    // ══════════════════════════════════════════════════════════════

    private val oxygenCapture = listOf(
        "SpO${SUB2}", "Today",
        "SpO${SUB2} range", "94${MINUS}100", "%",
        "Average blood oxygen", "98%",
        "Average blood oxygen during sleep", "96%",
    )

    @Test
    fun `oxygen saturation screen parses range, average, and sleep average`() {
        val oxygen = parser.parseOxygenDetail(oxygenCapture)

        assertEquals(MetricRange(94, 100), oxygen.range)
        assertEquals(98, oxygen.average)
        assertEquals(96, oxygen.averageSleep)
        assertTrue(oxygen.hasData())
    }

    @Test
    fun `oxygen saturation screen with subscript and non-breaking spaces`() {
        val oxygen = parser.parseOxygenSaturationDetail(
            listOf("Blood oxygen", "92${MINUS}99${NBSP}%", "Average SpO${SUB2}", "97${NBSP}%")
        )

        assertEquals(MetricRange(92, 99), oxygen.range)
        assertEquals(97, oxygen.average)
        assertTrue(oxygen.hasData())
    }

    @Test
    fun `empty oxygen capture yields no data`() {
        val oxygen = parser.parseOxygenDetail(emptyList<String>())
        assertFalse(oxygen.hasData())
        assertNull(oxygen.range)
        assertNull(oxygen.average)
        assertNull(oxygen.averageSleep)
    }

    // ══════════════════════════════════════════════════════════════
    //  Step 7 · Multi-Screen Merge Tests
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `HeartRateDetail merge preserves and overwrites non-null values`() {
        val hr1 = HeartRateDetail(range = MetricRange(50, 110))
        val hr2 = HeartRateDetail(restingBpm = 56, currentBpm = 72)
        val merged = hr1.merge(hr2)

        assertEquals(MetricRange(50, 110), merged.range)
        assertEquals(56, merged.restingBpm)
        assertEquals(72, merged.currentBpm)
    }

    @Test
    fun `StressDetail merge preserves and overwrites non-null values`() {
        val s1 = StressDetail(range = MetricRange(15, 60))
        val s2 = StressDetail(average = 35, category = "Moderate")
        val merged = s1.merge(s2)

        assertEquals(MetricRange(15, 60), merged.range)
        assertEquals(35, merged.average)
        assertEquals("Moderate", merged.category)
    }

    @Test
    fun `OxygenSaturationDetail merge preserves and overwrites non-null values`() {
        val o1 = OxygenSaturationDetail(range = MetricRange(93, 99))
        val o2 = OxygenSaturationDetail(average = 97, averageSleep = 95)
        val merged = o1.merge(o2)

        assertEquals(MetricRange(93, 99), merged.range)
        assertEquals(97, merged.average)
        assertEquals(95, merged.averageSleep)
    }

    @Test
    fun `stress screen with range before average does not confound average score with range minimum`() {
        val stress = parser.parseStressDetail(
            listOf("Stress", "Relaxed", "Stress range", "12${MINUS}45", "Daily average", "28")
        )

        assertEquals(MetricRange(12, 45), stress.range)
        assertEquals(28, stress.average)
        assertEquals("Relaxed", stress.category)
        assertTrue(stress.hasData())
    }

    @Test
    fun `stress screen infers High category for elevated stress`() {
        val stress = parser.parseStressDetail(
            listOf("Stress", "Average stress", "75")
        )

        assertEquals(75, stress.average)
        assertEquals("High", stress.category)
        assertTrue(stress.hasData())
    }

    @Test
    fun `oxygen saturation screen with range before average does not confound average with range`() {
        val oxygen = parser.parseOxygenDetail(
            listOf("SpO${SUB2}", "SpO${SUB2} range", "94${MINUS}99%", "Daily average", "98%", "Average sleep blood oxygen", "96%")
        )

        assertEquals(MetricRange(94, 99), oxygen.range)
        assertEquals(98, oxygen.average)
        assertEquals(96, oxygen.averageSleep)
        assertTrue(oxygen.hasData())
    }

    @Test
    fun `oxygen saturation screen parses current real-time measurement`() {
        val oxygen = parser.parseOxygenDetail(
            listOf("SpO${SUB2}", "Current", "99%")
        )

        assertEquals(99, oxygen.current)
        assertTrue(oxygen.hasData())
    }

    @Test
    fun `HeartRateDetail parses resting HR and range separately without cross-talk`() {
        val hrOnlyResting = parser.parseHeartRateDetail(listOf("Heart rate", "Resting HR", "55 bpm"))
        assertNull(hrOnlyResting.range)
        assertEquals(55, hrOnlyResting.restingBpm)
        assertTrue(hrOnlyResting.hasData())

        val hrOnlyRange = parser.parseHeartRateDetail(listOf("Heart rate", "Daily range", "48${MINUS}135 bpm"))
        assertEquals(MetricRange(48, 135), hrOnlyRange.range)
        assertNull(hrOnlyRange.restingBpm)
        assertTrue(hrOnlyRange.hasData())
    }

    @Test
    fun `SleepDetail merge clears stagesDerived flag when subsequent capture provides actual stage minutes`() {
        val derivedCapture = SleepDetail(
            totalMinutes = 600,
            deepPercent = 20,
            deepMinutes = 120,
            stagesDerived = true
        )
        val explicitCapture = SleepDetail(
            deepMinutes = 115,
            lightMinutes = 360,
            remMinutes = 125,
            stagesDerived = false
        )

        val merged = derivedCapture.merge(explicitCapture)
        assertEquals(115, merged.deepMinutes)
        assertEquals(360, merged.lightMinutes)
        assertEquals(125, merged.remMinutes)
        assertFalse(merged.stagesDerived)
    }

    @Test
    fun `SleepDetail hasData returns true for vitals-only or awakenings-only captures`() {
        val vitalsOnly = SleepDetail(heartRate = MetricRange(50, 100))
        assertTrue(vitalsOnly.hasData())

        val awakeningsOnly = SleepDetail(awakenings = 2)
        assertTrue(awakeningsOnly.hasData())

        val scoreOnly = SleepDetail(score = 80)
        assertTrue(scoreOnly.hasData())
    }

    @Test
    fun `SleepDetail merge preserves explicit stage minutes when other capture has derived stages`() {
        val explicitCapture = SleepDetail(
            totalMinutes = 600,
            deepMinutes = 110,
            lightMinutes = 360,
            remMinutes = 130,
            stagesDerived = false
        )
        val derivedCapture = SleepDetail(
            totalMinutes = 600,
            deepPercent = 20,
            deepMinutes = 120,
            stagesDerived = true
        )

        val merged = explicitCapture.merge(derivedCapture)
        assertEquals(110, merged.deepMinutes)
        assertEquals(360, merged.lightMinutes)
        assertEquals(130, merged.remMinutes)
        assertFalse(merged.stagesDerived)
    }

    @Test
    fun `stress category parsing handles prefixed and formatted strings`() {
        assertEquals("Relaxed", parser.parseStressCategory("Status: Relaxed"))
        assertEquals("Moderate", parser.parseStressCategory("Level: Moderate."))
        assertEquals("High", parser.parseStressCategory("Category: High!"))
        assertNull(parser.parseStressCategory("Stress"))
    }

    @Test
    fun `sleep detail parses awakenings with alternative phrasing`() {
        val sleep = parser.parseSleepDetail(
            listOf("Sleep", "Total sleep duration", "8 hrs", "Awakenings", "2 reps", "Awake duration", "15 mins")
        )
        assertEquals(2, sleep.awakenings)
        assertEquals(15, sleep.awakeMinutes)
    }

    @Test
    fun `ParsedHealthData merge aggregates all multi-screen metrics`() {
        val act = DailyActivity(steps = 10000L, standHours = 8)
        val sleep = SleepDetail(totalMinutes = 480, score = 85)
        val hr = HeartRateDetail(range = MetricRange(52, 120), restingBpm = 58)
        val stress = StressDetail(average = 28, category = "Relaxed")
        val spo2 = OxygenSaturationDetail(range = MetricRange(95, 100), average = 98)

        val d1 = ParsedHealthData(activity = act)
        val d2 = ParsedHealthData(sleep = sleep)
        val d3 = ParsedHealthData(heartRate = hr)
        val d4 = ParsedHealthData(stress = stress)
        val d5 = ParsedHealthData(oxygenSaturation = spo2)

        val merged = d1.merge(d2).merge(d3).merge(d4).merge(d5)

        assertEquals(10000L, merged.activity?.steps)
        assertEquals(8, merged.activity?.standHours)
        assertEquals(480, merged.sleep?.totalMinutes)
        assertEquals(MetricRange(52, 120), merged.heartRate?.range)
        assertEquals(58, merged.heartRate?.restingBpm)
        assertEquals(28, merged.stress?.average)
        assertEquals("Relaxed", merged.stress?.category)
        assertEquals(MetricRange(95, 100), merged.oxygenSaturation?.range)
        assertEquals(98, merged.oxygenSaturation?.average)
        assertTrue(merged.hasAnyData())

        val summary = merged.summaryString()
        assertTrue(summary.contains("10000 steps"))
        assertTrue(summary.contains("8h 0m sleep"))
        assertTrue(summary.contains("HR 52–120 bpm (resting 58)"))
        assertTrue(summary.contains("Stress avg 28 (Relaxed)"))
        assertTrue(summary.contains("SpO₂ avg 98%"))
    }

    @Test
    fun `home rings with variant labels and standalone counts without goals`() {
        val activity = parser.parseHomeActivity(
            listOf("Health", "Step count", "12500", "Active calories", "450", "Stand hours", "9", "Distance", "8.2 km")
        )

        assertEquals(12500L, activity.steps)
        assertNull(activity.stepsGoal)
        assertEquals(450, activity.activeCalories)
        assertNull(activity.activeCaloriesGoal)
        assertEquals(9, activity.standHours)
        assertNull(activity.standGoalHours)
        assertEquals(8.2f, activity.distanceKm!!, 0.001f)
        assertTrue(activity.hasData())
    }

    @Test
    fun `parseOxygenPercent correctly handles bare integers, ignores spo2 digit, and rejects ranges`() {
        assertEquals(98, parser.parseOxygenPercent("98%"))
        assertEquals(97, parser.parseOxygenPercent("97"))
        assertEquals(96, parser.parseOxygenPercent("Average SpO2 96%"))
        assertNull(parser.parseOxygenPercent("Average SpO2"))
        assertNull(parser.parseOxygenPercent("SpO2"))
        assertNull(parser.parseOxygenPercent("94-100%"))
        assertNull(parser.parseOxygenPercent("45")) // below 50
    }

    @Test
    fun `score parsing handles pts and points`() {
        assertEquals(85, parser.parseScore("85 pts"))
        assertEquals(90, parser.parseScore("90 points"))
        assertEquals(75, parser.parseScore("75pt"))
        assertEquals(100, parser.parseScore("100"))
        assertNull(parser.parseScore("Total score"))
    }

    @Test
    fun `stress category parsing handles extreme high and low categories`() {
        assertEquals("Extremely high", parser.parseStressCategory("Extremely high"))
        assertEquals("Very high", parser.parseStressCategory("Very high"))
        assertEquals("Low", parser.parseStressCategory("Level: Low"))
        assertEquals("Normal", parser.parseStressCategory("Status: normal."))
    }
}
