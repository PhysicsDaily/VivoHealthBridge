package com.vivohealthbridge.parser

import com.vivohealthbridge.data.models.DailyActivity
import com.vivohealthbridge.data.models.MetricRange
import com.vivohealthbridge.data.models.ParsedHealthData
import com.vivohealthbridge.data.models.SleepDetail
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
}
