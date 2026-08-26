package com.vivohealthbridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.vivohealthbridge.MainActivity
import com.vivohealthbridge.data.models.ParsedHealthData
import com.vivohealthbridge.parser.VivoHealthParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observable state of one automated sync pass, for the dashboard to render. */
data class SyncProgress(
    val active: Boolean = false,
    val step: String = "Idle",
    val detail: String = "",
    val percent: Int = 0,
)

/**
 * Drives Vivo Health through the exact sequence a person would follow, reading
 * each screen with the accessibility tree.
 *
 * ```
 *  WAIT_APP      Vivo Health reaches the foreground
 *  SYNC_GESTURE  drag the top of the list to trigger a watch sync
 *  SYNC_WAIT     "Syncing…" → "Sync complete"                      (~20 s)
 *  HOME_COLLECT  scroll to the top, read Steps/Exercise/Calories/Stand
 *  OPEN_SLEEP    tap the Sleep card
 *  SLEEP_LOAD    the detail screen renders                         (~15–20 s)
 *  SLEEP_CARDS   swipe the stat carousel: heart rate → respiratory → SpO₂ → HRV
 *  SLEEP_SCROLL  scroll down, expand "More analysis", read the stages
 *  FINISH        parse everything, hand it back, return to this app
 * ```
 *
 * ## Why a ticker and not accessibility events
 * Vivo Health emits content-changed events in bursts while a chart animates and
 * then goes silent for seconds at a time. Driving the machine from those events
 * meant a step could stall forever with nothing to wake it. Instead a single
 * self-scheduling [tick] runs every [TICK_MS], every step carries its own
 * deadline, and [WATCHDOG_MS] guarantees the run always ends — with partial data
 * if need be, never with a spinner that never stops.
 */
class VivoHealthAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VivoHealthA11y"

        val VIVO_HEALTH_PACKAGES = listOf(
            "com.vivo.health",
            "com.vivo.sports",
            "com.vivo.healthwidget",
        )

        var instance: VivoHealthAccessibilityService? = null
            private set

        /** Invoked once per run with whatever was collected. Cleared by the caller. */
        var syncCallback: ((ParsedHealthData) -> Unit)? = null

        private val _progress = MutableStateFlow(SyncProgress())
        val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

        val isSyncing: Boolean get() = _progress.value.active

        /** Plain-text step label, for callers that cannot collect a flow. */
        val currentStepDescription: String get() = _progress.value.step

        fun isServiceRunning(): Boolean = instance != null

        /** @return false if the service is not enabled, or a run is already going. */
        fun startSync(): Boolean {
            val service = instance ?: run {
                Log.w(TAG, "startSync ignored – service is not connected")
                return false
            }
            if (isSyncing) {
                Log.w(TAG, "startSync ignored – already syncing")
                return false
            }
            service.beginRun()
            return true
        }

        fun stopSync() {
            instance?.abortRun("Cancelled")
        }

        // ── Timings ───────────────────────────────────────────────
        private const val TICK_MS = 900L
        private const val WATCHDOG_MS = 210_000L

        private const val WAIT_APP_MS = 15_000L
        private const val SYNC_WAIT_MS = 40_000L
        private const val SYNC_PROBE_MS = 8_000L      // no sync sign by now → try the other direction
        private const val HOME_COLLECT_MS = 15_000L
        private const val OPEN_SLEEP_MS = 15_000L
        private const val SLEEP_LOAD_MS = 40_000L
        private const val SLEEP_CARDS_MS = 40_000L
        private const val SLEEP_SCROLL_MS = 45_000L

        private const val MAX_CARD_SWIPES = 6
        private const val MAX_ANALYSIS_SCROLLS = 10
        private const val MAX_TOP_SCROLLS = 4
        private const val MAX_NODES = 400
        private const val MAX_DEPTH = 40
    }

    private enum class Step(val label: String, val percent: Int) {
        IDLE("Idle", 0),
        WAIT_APP("Opening Vivo Health…", 5),
        SYNC_GESTURE("Pulling to sync…", 12),
        SYNC_WAIT("Syncing with watch…", 20),
        HOME_COLLECT("Reading activity rings…", 40),
        OPEN_SLEEP("Opening Sleep…", 50),
        SLEEP_LOAD("Loading sleep data…", 58),
        SLEEP_CARDS("Reading sleep vitals…", 72),
        SLEEP_SCROLL("Reading sleep analysis…", 88),
        FINISH("Saving…", 96),
    }

    private val parser = VivoHealthParser()
    private val handler = Handler(Looper.getMainLooper())

    private var step = Step.IDLE
    private var stepDeadline = 0L
    private var stepStarted = 0L
    private var runDeadline = 0L
    private var pausedUntil = 0L

    private val homeCaptures = mutableListOf<List<String>>()
    private val sleepCaptures = mutableListOf<List<String>>()

    private var pulledDown = false
    private var pulledUp = false
    private var syncAttempts = 0
    private var sawSyncing = false
    private var topScrollsLeft = 0
    private var cardSwipes = 0
    private var lastCardSignature: String? = null
    private var analysisScrolls = 0
    private var expandedAnalysis = false
    private var lastScrollSignature: String? = null
    private var unchangedScrolls = 0
    private var sleepOpenAttempts = 0
    private var sleepLoadAttempts = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (step == Step.IDLE) return
            try {
                tick()
            } catch (t: Throwable) {
                Log.e(TAG, "tick failed in $step", t)
            }
            if (step != Step.IDLE) handler.postDelayed(this, TICK_MS)
        }
    }

    // ─────────────────────────────────────────────────────
    //  Service lifecycle
    // ─────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 200
        }
        Log.d(TAG, "connected (gestures=${serviceInfo.capabilities and
                AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES})")
    }

    /** The machine polls; events are only useful as a hint to look sooner. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        Log.w(TAG, "interrupted")
        if (step != Step.IDLE) abortRun("Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
        if (step != Step.IDLE) abortRun("Service stopped")
        instance = null
        Log.d(TAG, "destroyed")
    }

    // ─────────────────────────────────────────────────────
    //  Run control
    // ─────────────────────────────────────────────────────

    private fun beginRun() {
        homeCaptures.clear()
        sleepCaptures.clear()
        pulledDown = false
        pulledUp = false
        syncAttempts = 0
        sawSyncing = false
        topScrollsLeft = MAX_TOP_SCROLLS
        cardSwipes = 0
        lastCardSignature = null
        analysisScrolls = 0
        expandedAnalysis = false
        lastScrollSignature = null
        unchangedScrolls = 0
        sleepOpenAttempts = 0
        sleepLoadAttempts = 0
        pausedUntil = 0L
        runDeadline = now() + WATCHDOG_MS

        goTo(Step.WAIT_APP, WAIT_APP_MS)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        Log.d(TAG, "run started")
    }

    private fun abortRun(reason: String) {
        Log.w(TAG, "run aborted: $reason")
        step = Step.IDLE
        handler.removeCallbacks(ticker)
        _progress.value = SyncProgress(active = false, step = reason)
        val callback = syncCallback
        syncCallback = null
        callback?.invoke(ParsedHealthData())
    }

    private fun goTo(next: Step, timeoutMs: Long, detail: String = "") {
        step = next
        stepStarted = now()
        stepDeadline = stepStarted + timeoutMs
        publish(detail)
        Log.d(TAG, "→ $next (${timeoutMs}ms) $detail")
    }

    private fun publish(detail: String = "") {
        _progress.value = SyncProgress(
            active = step != Step.IDLE,
            step = step.label,
            detail = detail,
            percent = step.percent,
        )
    }

    private fun pause(ms: Long) {
        pausedUntil = now() + ms
    }

    private fun now() = System.currentTimeMillis()

    // ─────────────────────────────────────────────────────
    //  The machine
    // ─────────────────────────────────────────────────────

    private fun tick() {
        if (now() > runDeadline) {
            Log.w(TAG, "watchdog fired in $step – finishing with partial data")
            finish()
            return
        }
        if (now() < pausedUntil) return

        val root = rootInActiveWindow
        if (root == null) {
            // Between windows. Only WAIT_APP can time out on this.
            if (step == Step.WAIT_APP && now() > stepDeadline) {
                abortRun("Vivo Health did not open")
            }
            return
        }

        val bounds = screenBounds(root)
        val texts = capture(root)
        val timedOut = now() > stepDeadline

        when (step) {
            Step.WAIT_APP -> waitForApp(root, timedOut)
            Step.SYNC_GESTURE -> pullToSync(bounds)
            Step.SYNC_WAIT -> waitForSync(bounds, texts, timedOut)
            Step.HOME_COLLECT -> collectHome(root, texts, timedOut)
            Step.HOME_COLLECT -> collectHome(root, bounds, texts, timedOut)
            Step.OPEN_SLEEP -> openSleep(root, bounds, timedOut)
            Step.SLEEP_LOAD -> waitForSleep(texts, timedOut)
            Step.SLEEP_CARDS -> readSleepCards(root, bounds, texts, timedOut)
            Step.SLEEP_SCROLL -> readSleepAnalysis(root, texts, timedOut)
            Step.SLEEP_SCROLL -> readSleepAnalysis(root, bounds, texts, timedOut)
            Step.FINISH, Step.IDLE -> Unit
        }
    }

    /** Vivo Health has to be the app on screen before anything else means anything. */
    private fun waitForApp(root: AccessibilityNodeInfo, timedOut: Boolean) {
        if (isVivoHealth(root.packageName?.toString())) {
            goTo(Step.SYNC_GESTURE, 5_000L)
            return
        }
        if (timedOut) abortRun("Vivo Health did not open")
    }

    /**
     * The sync is triggered by dragging the list at the top of the Health tab.
     * Pull-to-refresh (downward) is the mechanic in this app family, so it goes
     * first; if no "Syncing" or "Sync complete" appears, [waitForSync] retries
     * with an upward drag, so either gesture direction gets us there.
     * The sync is triggered by dragging the list at the top of the Health tab
     * downward and releasing immediately (ACTION_UP) to trigger pull-to-refresh.
     */
    private fun pullToSync(bounds: Rect) {
        val midX = bounds.centerX().toFloat()
        if (!pulledDown) {
            pulledDown = true
            drag(midX, bounds.top + bounds.height() * 0.30f, midX, bounds.top + bounds.height() * 0.78f)
        } else {
            pulledUp = true
            drag(midX, bounds.top + bounds.height() * 0.78f, midX, bounds.top + bounds.height() * 0.30f)
        }
        val startY = bounds.top + bounds.height() * 0.22f
        val endY = bounds.top + bounds.height() * 0.75f
        syncAttempts++
        Log.d(TAG, "pullToSync attempt $syncAttempts (from $startY to $endY, releasing immediately)")
        drag(midX, startY, midX, endY, durationMs = 380L, holdMs = 0L)
        goTo(Step.SYNC_WAIT, SYNC_WAIT_MS)
        pause(2_000L)
        pause(1_800L)
    }

    private fun waitForSync(bounds: Rect, texts: List<String>, timedOut: Boolean) {
        val blob = texts.joinToString(" | ").lowercase()

        if (!sawSyncing && (blob.contains("syncing") || blob.contains("synchronizing") ||
                    blob.contains("synchronising"))
                    blob.contains("synchronising") || blob.contains("refreshing") ||
                    blob.contains("updating"))
        ) {
            sawSyncing = true
            publish("Watch is syncing…")
            Log.d(TAG, "sync in progress")
        }

        val done = blob.contains("sync complete") || blob.contains("sync completed") ||
                blob.contains("synced") || blob.contains("data synchronized") ||
                blob.contains("up to date")
                blob.contains("up to date") || blob.contains("updated just now")

        if (done) {
            Log.d(TAG, "sync complete")
            beginHomeCollect("Sync complete")
            return
        }

        // Nothing happened — the drag probably went the wrong way. Try the other.
        if (!sawSyncing && !pulledUp && now() - stepStarted > SYNC_PROBE_MS) {
            Log.d(TAG, "no sync indicator after ${SYNC_PROBE_MS}ms – trying the opposite drag")
        // If no sync indicator appeared and we haven't exhausted attempts, retry pull down
        if (!sawSyncing && syncAttempts < 2 && now() - stepStarted > SYNC_PROBE_MS) {
            Log.d(TAG, "no sync indicator after ${SYNC_PROBE_MS}ms – retrying pull down")
            goTo(Step.SYNC_GESTURE, 5_000L, "Retrying sync gesture…")
            return
        }

        if (timedOut) {
            // Whatever is on screen is what the watch last delivered; read it.
            Log.w(TAG, "sync wait timed out (sawSyncing=$sawSyncing) – reading anyway")
            beginHomeCollect("Sync timed out, reading cached data")
        }
    }

    private fun beginHomeCollect(detail: String) {
        topScrollsLeft = MAX_TOP_SCROLLS
        goTo(Step.HOME_COLLECT, HOME_COLLECT_MS, detail)
        pause(1_200L)
    }

    /**
     * The rings sit at the very top of the Health tab, which the sync drag may
     * have scrolled away from — so scroll back up before reading.
     * The rings sit at the very top of the Health tab. If not already at the top,
     * scroll back up before reading.
     */
    private fun collectHome(root: AccessibilityNodeInfo, texts: List<String>, timedOut: Boolean) {
        if (topScrollsLeft > 0) {
    private fun collectHome(root: AccessibilityNodeInfo, bounds: Rect, texts: List<String>, timedOut: Boolean) {
        val hasSteps = texts.any { it.equals("steps", ignoreCase = true) }
        if (!hasSteps && topScrollsLeft > 0) {
            topScrollsLeft--
            if (scroll(root, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                pause(700L)
                return
            }
            topScrollsLeft = 0  // already at the top
            scrollUp(bounds)
            pause(800L)
            return
        }
        topScrollsLeft = 0  // at the top or done scrolling

        addCapture(homeCaptures, texts)
        val activity = parser.parseHomeActivity(homeCaptures)

        // Steps is the ring that is always populated; the others can legitimately
        // read 0 / goal, which still parses, so one full capture is enough.
        if (activity.steps != null || timedOut) {
            Log.d(TAG, "home activity: $activity")
            sleepOpenAttempts = 0
            goTo(Step.OPEN_SLEEP, OPEN_SLEEP_MS)
        }
    }

    /** Taps the Sleep card, avoiding the "Sleep" entry in the bottom nav bar. */
    private fun openSleep(root: AccessibilityNodeInfo, bounds: Rect, timedOut: Boolean) {
        if (clickSleepCard(root, bounds)) {
            sleepLoadAttempts++
            goTo(Step.SLEEP_LOAD, SLEEP_LOAD_MS)
            pause(3_000L)
            return
        }

        sleepOpenAttempts++
        if (timedOut || sleepOpenAttempts > 4) {
            Log.e(TAG, "Sleep card never found")
            finish()
            return
        }
        // It may be below the fold; nudge the list and look again.
        scroll(root, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        // It may be below the fold; scroll down slightly and look again
        scrollDown(bounds)
        pause(1_200L)
    }

    private fun waitForSleep(texts: List<String>, timedOut: Boolean) {
        val blob = texts.joinToString(" | ").lowercase()
        val loading = blob.contains("loading") || blob.contains("please wait")
        val ready = !loading && (
                blob.contains("total sleep duration") ||
                        blob.contains("compared to last") ||
                        blob.contains("sleep heart rate") ||
                        blob.contains("more analysis")
                )

        if (ready) {
            addCapture(sleepCaptures, texts)
            cardSwipes = 0
            lastCardSignature = null
            goTo(Step.SLEEP_CARDS, SLEEP_CARDS_MS)
            return
        }

        if (timedOut) {
            if (sleepLoadAttempts < 2) {
                Log.w(TAG, "sleep detail never rendered – trying the card again")
                sleepOpenAttempts = 0
                goTo(Step.OPEN_SLEEP, OPEN_SLEEP_MS, "Retrying Sleep…")
            } else {
                Log.e(TAG, "sleep detail never rendered")
                finish()
            }
        }
    }

    /**
     * The vitals live in a horizontal carousel — total duration, heart rate,
     * respiratory rate, SpO₂, HRV — one card at a time. Swipe until all four
     * ranges have parsed, the carousel stops changing, or we run out of swipes.
     */
    private fun readSleepCards(
        root: AccessibilityNodeInfo,
        bounds: Rect,
        texts: List<String>,
        timedOut: Boolean,
    ) {
        addCapture(sleepCaptures, texts)

        val sleep = parser.parseSleepDetail(sleepCaptures)
        val haveAll = sleep.heartRate != null && sleep.respiratoryRate != null &&
                sleep.spo2 != null && sleep.hrv != null

        val signature = texts.joinToString("|")
        val stalled = signature == lastCardSignature
        lastCardSignature = signature

        if (haveAll || cardSwipes >= MAX_CARD_SWIPES || timedOut || (stalled && cardSwipes > 0)) {
            Log.d(
                TAG,
                "carousel done after $cardSwipes swipes " +
                        "(hr=${sleep.heartRate} rr=${sleep.respiratoryRate} " +
                        "spo2=${sleep.spo2} hrv=${sleep.hrv})"
            )
            analysisScrolls = 0
            expandedAnalysis = false
            lastScrollSignature = null
            unchangedScrolls = 0
            goTo(Step.SLEEP_SCROLL, SLEEP_SCROLL_MS)
            return
        }

        val y = carouselY(root, bounds)
        drag(
            bounds.left + bounds.width() * 0.80f, y,
            bounds.left + bounds.width() * 0.20f, y,
            durationMs = 320L,
        )
        // Keep swipe safely away from screen edges (28% margin) to prevent system back navigation
        val fromX = bounds.left + bounds.width() * 0.72f
        val toX = bounds.left + bounds.width() * 0.28f
        drag(fromX, y, toX, y, durationMs = 320L, holdMs = 0L)
        cardSwipes++
        publish("Sleep vitals card ${cardSwipes + 1}")
        pause(1_600L)
    }

    /**
     * Everything below the carousel: the score block, then "More analysis" which
     * expands the stage donut, awakenings, continuity and average blood oxygen.
     */
    private fun readSleepAnalysis(
        root: AccessibilityNodeInfo,
        bounds: Rect,
        texts: List<String>,
        timedOut: Boolean,
    ) {
        addCapture(sleepCaptures, texts)

        // Expand "More analysis" if visible and not already expanded
        if (!expandedAnalysis && texts.any { it.contains("more analysis", true) }) {
            if (clickByText(root, "More analysis")) {
                expandedAnalysis = true
                publish("Expanding More analysis…")
                Log.d(TAG, "tapped More analysis")
                pause(3_000L)
                pause(2_500L)
                return
            }
            // Not a button on this build — the section is already inline.
            expandedAnalysis = true
        }

        val signature = texts.joinToString("|")
        if (signature == lastScrollSignature) unchangedScrolls++ else unchangedScrolls = 0
        if (signature == lastScrollSignature) {
            unchangedScrolls++
        } else {
            unchangedScrolls = 0
        }
        lastScrollSignature = signature

        val bottom = unchangedScrolls >= 2
        // Must have scrolled down at least 2 times before checking for bottom
        val bottom = analysisScrolls >= 2 && unchangedScrolls >= 2
        if (bottom || analysisScrolls >= MAX_ANALYSIS_SCROLLS || timedOut) {
            Log.d(TAG, "analysis read after $analysisScrolls scrolls (bottom=$bottom)")
            Log.d(TAG, "analysis read after $analysisScrolls scrolls (bottom=$bottom, timedOut=$timedOut)")
            finish()
            return
        }

        if (!scroll(root, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            unchangedScrolls++
        }
        publish("Reading sleep analysis (${analysisScrolls + 1})…")
        scrollDown(bounds)
        analysisScrolls++
        pause(1_300L)
        pause(1_400L)
    }

    // ─────────────────────────────────────────────────────
    //  Finish
    // ─────────────────────────────────────────────────────

    private fun finish() {
        step = Step.FINISH
        publish("Parsing…")
        handler.removeCallbacks(ticker)

        val activity = if (homeCaptures.isEmpty()) null else parser.parseHomeActivity(homeCaptures)
        val sleep = if (sleepCaptures.isEmpty()) null else parser.parseSleepDetail(sleepCaptures)

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            homeCaptures.forEachIndexed { i, c -> Log.d(TAG, "home[$i] = $c") }
            sleepCaptures.forEachIndexed { i, c -> Log.d(TAG, "sleep[$i] = $c") }
        }
        Log.d(TAG, "activity = $activity")
        Log.d(TAG, "sleep = $sleep")

        val parsed = ParsedHealthData(
            activity = activity?.takeIf { it.hasData() },
            sleep = sleep?.takeIf { it.hasData() },
        )

        step = Step.IDLE
        _progress.value = SyncProgress(
            active = false,
            step = if (parsed.hasAnyData()) "Done" else "No data found",
            percent = 100,
        )

        val callback = syncCallback
        syncCallback = null
        callback?.invoke(parsed)

        returnToApp()
    }

    /** Bring our own UI back so the result is visible without the user hunting for it. */
    private fun returnToApp() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({
            try {
                startActivity(
                    Intent(this, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            } catch (t: Throwable) {
                // Background activity starts can be refused; the back press above
                // is the fallback and the result is already delivered either way.
                Log.w(TAG, "could not return to the app", t)
            }
        }, 600L)
            startActivity(intent)
            Log.d(TAG, "returned to MainActivity directly via Intent")
        } catch (t: Throwable) {
            Log.w(TAG, "could not return to the app via Intent, using back as fallback", t)
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    // ─────────────────────────────────────────────────────
    //  Gestures
    // ─────────────────────────────────────────────────────

    /**
     * A slow drag with a brief hold at the end — pull-to-refresh implementations
     * measure the drag distance over time and ignore a flick.
     * Tap gesture at the specified screen coordinates.
     */
    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val builder = GestureDescription.Builder()
        val stroke = GestureDescription.StrokeDescription(path, 0, 100L)
        builder.addStroke(stroke)
        return dispatchGesture(builder.build(), null, null)
    }

    /**
     * Scrolls the screen down to reveal content below.
     * Uses a physical touch drag upward along the screen center.
     */
    private fun scrollDown(bounds: Rect) {
        val midX = bounds.centerX().toFloat()
        val startY = bounds.top + bounds.height() * 0.72f
        val endY = bounds.top + bounds.height() * 0.28f
        drag(midX, startY, midX, endY, durationMs = 350L, holdMs = 0L)
    }

    /**
     * Scrolls the screen up to return towards the top of the content.
     */
    private fun scrollUp(bounds: Rect) {
        val midX = bounds.centerX().toFloat()
        val startY = bounds.top + bounds.height() * 0.28f
        val endY = bounds.top + bounds.height() * 0.72f
        drag(midX, startY, midX, endY, durationMs = 350L, holdMs = 0L)
    }

    /**
     * Dispatches a gesture drag path from (fromX, fromY) to (toX, toY).
     * If holdMs == 0, finger is released immediately upon reaching (toX, toY).
     */
    private fun drag(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = 800L,
        holdMs: Long = 200L,
        durationMs: Long = 400L,
        holdMs: Long = 0L,
    ) {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val builder = GestureDescription.Builder()
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs, holdMs > 0)
        builder.addStroke(stroke)
        if (holdMs > 0) {
            // A 1px contour: a zero-length path is rejected by StrokeDescription.
            val hold = Path().apply {
                moveTo(toX, toY)
                lineTo(toX, toY + 1f)
            }
            builder.addStroke(stroke.continueStroke(hold, durationMs, holdMs, false))
            builder.addStroke(stroke.continueStroke(hold, 0, holdMs, false))
        }
        val dispatched = dispatchGesture(builder.build(), null, null)
        if (!dispatched) {
            Log.e(
                TAG,
                "dispatchGesture refused – canPerformGestures is missing from " +
                        "accessibility_service_config.xml"
            )
        }
    }

    /** Vertical centre of the vitals carousel, taken from the card's own bounds. */
    private fun carouselY(root: AccessibilityNodeInfo, bounds: Rect): Float {
        val anchor = findNode(root) { node ->
            val text = nodeText(node)?.lowercase() ?: return@findNode false
            text.contains("sleep heart rate") || text.contains("total sleep duration") ||
                    text.contains("respiratory") || text.contains("spo2") ||
                    text.contains("spo₂") || text.contains("hrv")
        }
        val rect = Rect()
        anchor?.getBoundsInScreen(rect)
        if (rect.height() > 0 && rect.centerY() > bounds.top) return rect.centerY().toFloat()
        return bounds.top + bounds.height() * 0.45f
    }

    // ─────────────────────────────────────────────────────
    //  Node helpers
    // ─────────────────────────────────────────────────────

    private fun screenBounds(root: AccessibilityNodeInfo?): Rect {
        val rect = Rect()
        root?.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) return rect
        val dm = resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun isVivoHealth(packageName: String?): Boolean {
        val name = packageName ?: return false
        return VIVO_HEALTH_PACKAGES.any { name.startsWith(it, ignoreCase = true) } ||
                (name.contains("vivo", true) && name.contains("health", true))
    }

    private fun nodeText(node: AccessibilityNodeInfo): String? =
        node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }

    /**
     * Every text node in tree order. Order matters: the parser reads values by
     * their distance from a label, so the sequence must not be reshuffled or
     * de-duplicated across the screen.
     */
    private fun capture(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>(64)
        walk(root, 0, out)
        return out
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int, out: MutableList<String>) {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_NODES) return
        try {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) out.add(text)

            val desc = node.contentDescription?.toString()?.trim()
            if (!desc.isNullOrEmpty() && desc != text) out.add(desc)

            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1, out)
                if (out.size >= MAX_NODES) return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "walk failed at depth $depth", t)
        }
    }

    private fun findNode(
        root: AccessibilityNodeInfo?,
        depth: Int = 0,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (root == null || depth > MAX_DEPTH) return null
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            findNode(root.getChild(i), depth + 1, predicate)?.let { return it }
        }
        return null
    }

    private fun collectNodes(
        root: AccessibilityNodeInfo?,
        depth: Int,
        out: MutableList<AccessibilityNodeInfo>,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ) {
        if (root == null || depth > MAX_DEPTH || out.size >= 32) return
        if (predicate(root)) out.add(root)
        for (i in 0 until root.childCount) {
            collectNodes(root.getChild(i), depth + 1, out, predicate)
        }
    }

    /**
     * The home screen has three things reading "Sleep": the card we want, the
     * bottom navigation tab, and the "Sleep heart rate" label. Prefer an exact
     * match, ignore anything sitting in the bottom nav strip.
     */
    private fun clickSleepCard(root: AccessibilityNodeInfo, bounds: Rect): Boolean {
        val navTop = bounds.top + bounds.height() * 0.88f
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, 0, candidates) { node ->
            val text = nodeText(node)?.lowercase() ?: return@collectNodes false
            if (!text.startsWith("sleep")) return@collectNodes false
            val rect = Rect().also { node.getBoundsInScreen(it) }
            rect.height() > 0 && rect.top < navTop
        }

        val ordered = candidates.sortedBy { node ->
            when (nodeText(node)?.trim()?.lowercase()) {
                "sleep" -> 0
                else -> 1
            }
        }
        for (node in ordered) {
            if (clickSelfOrAncestor(node)) {
                Log.d(TAG, "clicked Sleep entry \"${nodeText(node)}\"")
                return true
            }
            // Fallback: tap the center of the node bounding box
            val rect = Rect().also { node.getBoundsInScreen(it) }
            if (rect.width() > 0 && rect.height() > 0 && rect.top < navTop) {
                tap(rect.centerX().toFloat(), rect.centerY().toFloat())
                Log.d(TAG, "tapped center of Sleep entry \"${nodeText(node)}\" at (${rect.centerX()}, ${rect.centerY()})")
                return true
            }
        }
        return false
    }

    private fun clickByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val matches = root.findAccessibilityNodeInfosByText(text) ?: return false
        for (node in matches) {
            if (clickSelfOrAncestor(node)) return true
            // Fallback: tap the center of the node on screen
            val rect = Rect().also { node.getBoundsInScreen(it) }
            if (rect.width() > 0 && rect.height() > 0) {
                tap(rect.centerX().toFloat(), rect.centerY().toFloat())
                Log.d(TAG, "tapped center of node with text \"$text\" at (${rect.centerX()}, ${rect.centerY()})")
                return true
            }
        }
        return false
    }

    private fun clickSelfOrAncestor(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isClickable && current.isEnabled) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return false
    }

    /** Scrolls the deepest scrollable container, which is the content list. */
    private fun scroll(root: AccessibilityNodeInfo, action: Int): Boolean {
        val scrollables = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, 0, scrollables) { it.isScrollable }
        for (node in scrollables.asReversed()) {
            if (node.performAction(action)) return true
        }
        return false
    }

    private fun addCapture(target: MutableList<List<String>>, texts: List<String>) {
        if (texts.isEmpty()) return
        if (target.isNotEmpty() && target.last() == texts) return
        target.add(texts)
    }
}
