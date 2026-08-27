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
import com.vivohealthbridge.data.models.merge
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
 *  SYNC_GESTURE  scroll to the top, pull down and release to trigger a sync
 *  SYNC_WAIT     wait 10 s for watch to synchronize
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
 *
 * ## Where gestures are allowed to land
 * Only inside the content band ([TOP_SAFE]…[BOTTOM_SAFE], inset from the side
 * edges by [EDGE_SAFE]). The strip above holds the toolbar and its back arrow,
 * the strip below is the navigation bar and the system gesture area, and the
 * side edges are the back gesture — a swipe that strays into any of them
 * navigates away instead of scrolling.
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

        enum class SyncMode {
            ASSISTED_MANUAL,
            AUTONOMOUS
        }

        var currentSyncMode: SyncMode = SyncMode.ASSISTED_MANUAL
            private set

        private val _liveCapturedData = MutableStateFlow(ParsedHealthData())
        val liveCapturedData: StateFlow<ParsedHealthData> = _liveCapturedData.asStateFlow()

        val isSyncing: Boolean get() = _progress.value.active

        val isAssistedSyncActive: Boolean
            get() = currentSyncMode == SyncMode.ASSISTED_MANUAL && isSyncing

        /** Plain-text step label, for callers that cannot collect a flow. */
        val currentStepDescription: String get() = _progress.value.step

        fun isServiceRunning(): Boolean = instance != null

        /** Starts the assisted live capture mode (user navigates, service reads). */
        fun startAssistedSync(): Boolean {
            val service = instance ?: run {
                Log.w(TAG, "startAssistedSync ignored – service is not connected")
                return false
            }
            if (isSyncing) {
                Log.w(TAG, "startAssistedSync ignored – already syncing")
                return false
            }
            service.beginAssistedRun()
            return true
        }

        /** Finalizes assisted sync and syncs all collected data to Health Connect. */
        fun finalizeAssistedSync() {
            instance?.finishAssistedRun()
        }

        fun cancelAssistedSync() {
            instance?.abortRun("Assisted sync cancelled")
        }

        /** Legacy: Starts autonomous gesture-driven bot sync. */
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
        private const val SYNC_WAIT_MS = 10_000L      // wait 10s after pulling down
        private const val HOME_COLLECT_MS = 15_000L
        private const val OPEN_SLEEP_MS = 15_000L
        private const val SLEEP_LOAD_MS = 40_000L
        private const val SLEEP_CARDS_MS = 40_000L
        private const val SLEEP_SCROLL_MS = 45_000L

        private const val MAX_CARD_SWIPES = 6
        private const val MAX_ANALYSIS_SCROLLS = 10
        private const val MAX_TOP_SCROLLS = 4
        private const val MAX_SLEEP_OPENS = 3
        private const val MAX_NODES = 400
        private const val MAX_DEPTH = 40

        // ── Where a gesture may land, as a fraction of the window ──
        private const val TOP_SAFE = 0.14f      // above: toolbar, back arrow, tabs
        private const val BOTTOM_SAFE = 0.86f   // below: nav bar / gesture strip
        private const val EDGE_SAFE = 0.12f     // sides: system back gesture

        /** Any one of these on screen means we are on the sleep detail page. */
        private val SLEEP_MARKERS = listOf(
            "total sleep duration", "compared to last", "sleep heart rate",
            "more analysis", "sleep hrv", "sleep spo",
        )

        /**
         * Labels that begin with "Sleep" but are not the home screen's Sleep
         * card — the tiles and headings on the detail page itself. Tapping one
         * of these opens a sub-screen, or walks up into the toolbar and presses
         * back, which is how a retry used to bounce us out of Sleep entirely.
         */
        private val NOT_THE_SLEEP_CARD = listOf(
            "heart rate", "hrv", "spo", "respiratory", "breath", "score",
            "analysis", "stage", "efficiency",
        )

        /**
         * Text only the home tab shows. The sleep detail page mentions "sleep"
         * all the way down, so this is what tells "scrolled to the bottom of
         * Sleep" apart from "we are back on the home list".
         */
        private val HOME_TAB_ONLY = listOf("steps", "step count", "calories", "stand")
    }

    private enum class Step(val label: String, val percent: Int) {
        IDLE("Idle", 0),
        WAIT_APP("Opening Vivo Health…", 5),
        SYNC_GESTURE("Pulling down to sync…", 12),
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

    private var syncPulls = 0
    private var topScrollsLeft = 0
    private var cardSwipes = 0
    private var lastCardSignature: String? = null
    private var analysisScrolls = 0
    private var expandedAnalysis = false
    private var lastScrollSignature: String? = null
    private var unchangedScrolls = 0
    private var sleepOpenAttempts = 0

    /** Every re-entry into [Step.OPEN_SLEEP], however we got sent back there. */
    private var sleepReopens = 0
    private var offSleepStrikes = 0

    // ── Assisted Live Capture state ───────────────────────────
    private val liveHomeCaptures = mutableListOf<List<String>>()
    private val liveSleepCaptures = mutableListOf<List<String>>()
    private val liveHeartRateCaptures = mutableListOf<List<String>>()
    private val liveStressCaptures = mutableListOf<List<String>>()
    private val liveOxygenCaptures = mutableListOf<List<String>>()
    private var overlay: LiveSyncOverlay? = null
    private var lastLiveCaptureSignature: String? = null

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

    private val assistedTicker = object : Runnable {
        override fun run() {
            if (currentSyncMode != SyncMode.ASSISTED_MANUAL || !isSyncing) return
            try {
                val root = rootInActiveWindow
                if (root != null && isVivoHealth(root.packageName?.toString())) {
                    if (overlay?.isShowing != true) {
                        overlay?.show()
                    }
                    inspectAndCaptureLive(root)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "assistedTicker failed", t)
            }
            if (currentSyncMode == SyncMode.ASSISTED_MANUAL && isSyncing) {
                handler.postDelayed(this, 500L)
            }
        }
    }

    // ─────────────────────────────────────────────────────
    //  Service lifecycle
    // ─────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        overlay = LiveSyncOverlay(
            context = this,
            onSyncClicked = { finishAssistedRun() },
            onCancelClicked = { abortRun("Assisted sync cancelled") }
        )
        Log.d(TAG, "connected (gestures=${serviceInfo.capabilities and
                AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES})")
    }

    /** In assisted mode, inspect the screen as soon as window content or scroll changes. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (currentSyncMode == SyncMode.ASSISTED_MANUAL && isSyncing) {
            val root = rootInActiveWindow ?: return
            if (isVivoHealth(root.packageName?.toString())) {
                inspectAndCaptureLive(root)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "interrupted")
        if (isSyncing) abortRun("Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(assistedTicker)
        overlay?.hide()
        overlay = null
        if (isSyncing) abortRun("Service stopped")
        instance = null
        Log.d(TAG, "destroyed")
    }

    // ─────────────────────────────────────────────────────
    //  Assisted Run Control (Manual Navigation)
    // ─────────────────────────────────────────────────────

    private fun beginAssistedRun() {
        currentSyncMode = SyncMode.ASSISTED_MANUAL
        step = Step.IDLE // autonomous state machine stays idle
        liveHomeCaptures.clear()
        liveSleepCaptures.clear()
        liveHeartRateCaptures.clear()
        liveStressCaptures.clear()
        liveOxygenCaptures.clear()
        lastLiveCaptureSignature = null
        _liveCapturedData.value = ParsedHealthData()
        _progress.value = SyncProgress(
            active = true,
            step = "Live Capture Active",
            detail = "Browse Vivo Health — metrics capture automatically",
            percent = 50
        )
        if (overlay == null) {
            overlay = LiveSyncOverlay(
                context = this,
                onSyncClicked = { finishAssistedRun() },
                onCancelClicked = { abortRun("Assisted sync cancelled") }
            )
        }
        overlay?.show()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(assistedTicker)
        handler.post(assistedTicker)
        Log.d(TAG, "Assisted live sync started")
    }

    private fun inspectAndCaptureLive(root: AccessibilityNodeInfo) {
        val texts = capture(root)
        if (texts.isEmpty()) return

        val sig = texts.joinToString("|")
        if (sig == lastLiveCaptureSignature) return
        lastLiveCaptureSignature = sig

        var changed = false
        var current = _liveCapturedData.value

        // 1. Sleep Detail
        val isSleep = onSleepDetail(texts) || stillOnSleepDetail(texts)
        if (isSleep) {
            addCapture(liveSleepCaptures, texts)
            val sleep = parser.parseSleepDetail(liveSleepCaptures)
            if (sleep.hasData()) {
                val mergedSleep = current.sleep?.merge(sleep) ?: sleep
                if (mergedSleep != current.sleep) {
                    current = current.copy(sleep = mergedSleep)
                    changed = true
                }
            }
        }

        // 2. Home Activity rings
        // 2. Home Activity rings & Home Cards (Heart rate, Stress, Oxygen, Weight)
        val isHome = !isSleep && texts.any {
            it.equals("steps", true) || it.equals("calories", true) ||
                    it.equals("stand", true) || it.contains("step count", true)
        }
        if (isHome) {
            addCapture(liveHomeCaptures, texts)
            val act = parser.parseHomeActivity(liveHomeCaptures)
            if (act.hasData()) {
                val mergedAct = current.activity?.merge(act) ?: act
                if (mergedAct != current.activity) {
                    current = current.copy(activity = mergedAct)
                    changed = true
                }
            }
            val homeCards = parser.parseHomeCards(liveHomeCaptures)
            if (homeCards.hasAnyData()) {
                val merged = current.merge(homeCards)
                if (merged != current) {
                    current = merged
                    changed = true
                }
            }
        }

        // 3. Heart Rate Detail
        val isHeartRate = !isSleep && !isHome && texts.any {
            it.contains("resting heart rate", true) || it.contains("resting hr", true) ||
                    (it.contains("heart rate", true) && !it.contains("sleep", true))
        }
        if (isHeartRate) {
            addCapture(liveHeartRateCaptures, texts)
            val hr = parser.parseHeartRateDetail(liveHeartRateCaptures)
            if (hr.hasData()) {
                val mergedHr = current.heartRate?.merge(hr) ?: hr
                if (mergedHr != current.heartRate) {
                    current = current.copy(
                        heartRate = mergedHr,
                        restingHeartRateBpm = mergedHr.restingBpm ?: current.restingHeartRateBpm,
                        heartRateBpm = mergedHr.currentBpm ?: current.heartRateBpm
                    )
                    changed = true
                }
            }
        }

        // 4. Stress Detail
        val isStress = !isSleep && !isHome && texts.any {
            it.contains("stress", true) || it.contains("relaxed", true) ||
                    it.contains("pressure", true)
        }
        if (isStress) {
            addCapture(liveStressCaptures, texts)
            val stress = parser.parseStressDetail(liveStressCaptures)
            if (stress.hasData()) {
                val mergedStress = current.stress?.merge(stress) ?: stress
                if (mergedStress != current.stress) {
                    current = current.copy(
                        stress = mergedStress,
                        stressLevel = mergedStress.average ?: current.stressLevel,
                        stressLevel = mergedStress.current ?: mergedStress.average ?: current.stressLevel,
                        stressCategory = mergedStress.category ?: current.stressCategory
                    )
                    changed = true
                }
            }
        }

        // 5. Oxygen Saturation (SpO₂) Detail
        val isOxygen = !isSleep && !isHome && texts.any {
            (it.contains("spo2", true) || it.contains("spo₂", true) ||
                    it.contains("blood oxygen", true) || it.contains("oxygen saturation", true)) &&
                    !it.contains("sleep", true)
        }
        if (isOxygen) {
            addCapture(liveOxygenCaptures, texts)
            val oxygen = parser.parseOxygenDetail(liveOxygenCaptures)
            if (oxygen.hasData()) {
                val mergedOxygen = current.oxygenSaturation?.merge(oxygen) ?: oxygen
                if (mergedOxygen != current.oxygenSaturation) {
                    current = current.copy(oxygenSaturation = mergedOxygen)
                    changed = true
                }
            }
        }

        if (changed) {
            _liveCapturedData.value = current
            overlay?.update(current)
            _progress.value = SyncProgress(
                active = true,
                step = "Captured: ${current.summaryString()}",
                detail = "Tap 'Sync' on overlay or return to app when done",
                percent = 75
            )
            Log.d(TAG, "Live data updated: ${current.summaryString()}")
        }
    }

    private fun finishAssistedRun() {
        handler.removeCallbacks(assistedTicker)
        overlay?.hide()

        val finalData = _liveCapturedData.value
        Log.d(TAG, "finishAssistedRun with data: ${finalData.summaryString()}")

        _progress.value = SyncProgress(
            active = false,
            step = if (finalData.hasAnyData()) "Done" else "No data found",
            percent = 100
        )

        val callback = syncCallback
        syncCallback = null
        callback?.invoke(finalData)

        returnToApp()
    }

    // ─────────────────────────────────────────────────────
    //  Autonomous Run control (Legacy Bot Gestures)
    // ─────────────────────────────────────────────────────

    private fun beginRun() {
        currentSyncMode = SyncMode.AUTONOMOUS
        overlay?.hide()
        homeCaptures.clear()
        sleepCaptures.clear()
        syncPulls = 0
        topScrollsLeft = MAX_TOP_SCROLLS
        cardSwipes = 0
        lastCardSignature = null
        analysisScrolls = 0
        expandedAnalysis = false
        lastScrollSignature = null
        unchangedScrolls = 0
        sleepOpenAttempts = 0
        sleepReopens = 0
        offSleepStrikes = 0
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
        handler.removeCallbacks(assistedTicker)
        overlay?.hide()
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
            Step.SYNC_GESTURE -> pullToSync(root, bounds)
            Step.SYNC_WAIT -> waitForSync(timedOut)
            Step.HOME_COLLECT -> collectHome(root, texts, timedOut)
            Step.OPEN_SLEEP -> openSleep(root, bounds, texts, timedOut)
            Step.SLEEP_LOAD -> waitForSleep(texts, timedOut)
            Step.SLEEP_CARDS -> readSleepCards(root, bounds, texts, timedOut)
            Step.SLEEP_SCROLL -> readSleepAnalysis(root, bounds, texts, timedOut)
            Step.FINISH, Step.IDLE -> Unit
        }
    }

    /** Vivo Health has to be the app on screen before anything else means anything. */
    private fun waitForApp(root: AccessibilityNodeInfo, timedOut: Boolean) {
        if (isVivoHealth(root.packageName?.toString())) {
            topScrollsLeft = MAX_TOP_SCROLLS
            goTo(Step.SYNC_GESTURE, 12_000L)
            return
        }
        if (timedOut) abortRun("Vivo Health did not open")
    }

    /**
     * The watch sync is the Health tab's pull-to-refresh: press, pull straight
     * down, let go — the refresh fires on release, exactly like a feed.
     *
     * Two things this used to get wrong. It pulled from wherever the list
     * happened to be, but pull-to-refresh only arms when the list is already at
     * the top, so on a scrolled list the drag just scrolled content; and it held
     * the finger down at the end of the drag, which lets the list settle back
     * instead of releasing into the refresh. So: scroll to the top first, then
     * one clean pull with no hold. If nothing happens we pull again, further —
     * never upwards, which only scrolls away from the rings.
     */
    private fun pullToSync(root: AccessibilityNodeInfo, bounds: Rect) {
        if (topScrollsLeft > 0) {
            topScrollsLeft--
            if (scroll(root, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                pause(500L)
                return
            }
            topScrollsLeft = 0  // already at the top
        }

        val midX = bounds.centerX().toFloat()
        val from = bounds.top + bounds.height() * 0.24f
        val reach = 0.65f
        drag(bounds, midX, from, midX, bounds.top + bounds.height() * reach, durationMs = 320L)
        syncPulls++

        goTo(Step.SYNC_WAIT, SYNC_WAIT_MS, "10s remaining")
        pause(1_500L)
    }

    private fun waitForSync(timedOut: Boolean) {
        val remainingSec = (((stepDeadline - now()) + 999) / 1000).coerceAtLeast(0)
        publish("${remainingSec}s remaining")

        if (timedOut) {
            Log.d(TAG, "sync wait finished (10s) – collecting home activity")
            beginHomeCollect("Sync wait complete")
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
     */
    private fun collectHome(root: AccessibilityNodeInfo, texts: List<String>, timedOut: Boolean) {
        if (topScrollsLeft > 0) {
            topScrollsLeft--
            if (scroll(root, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                pause(700L)
                return
            }
            topScrollsLeft = 0  // already at the top
        }

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
    private fun openSleep(
        root: AccessibilityNodeInfo,
        bounds: Rect,
        texts: List<String>,
        timedOut: Boolean,
    ) {
        // Already on the detail screen — a retry can land here once the page
        // finally renders. Tapping anything now would only navigate off it.
        if (onSleepDetail(texts)) {
            Log.d(TAG, "already on the sleep detail screen")
            offSleepStrikes = 0
            goTo(Step.SLEEP_LOAD, SLEEP_LOAD_MS)
            return
        }

        if (clickSleepCard(root, bounds)) {
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
        pause(1_200L)
    }

    private fun waitForSleep(texts: List<String>, timedOut: Boolean) {
        val blob = texts.joinToString(" | ").lowercase()
        val loading = blob.contains("loading") || blob.contains("please wait")
        val ready = !loading && onSleepDetail(texts)

        if (ready) {
            addCapture(sleepCaptures, texts)
            cardSwipes = 0
            lastCardSignature = null
            offSleepStrikes = 0
            goTo(Step.SLEEP_CARDS, SLEEP_CARDS_MS)
            return
        }

        if (timedOut) {
            sleepReopens++
            if (sleepReopens <= MAX_SLEEP_OPENS) {
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
     * respiratory rate, SpO₂, HRV — one card at a time. Advance it with the
     * row's own scroll action where possible (a real gesture can be swallowed by
     * the page, or land somewhere it shouldn't) until all four ranges have
     * parsed, the carousel stops changing, or we run out of swipes.
     */
    private fun readSleepCards(
        root: AccessibilityNodeInfo,
        bounds: Rect,
        texts: List<String>,
        timedOut: Boolean,
    ) {
        if (!confirmOnSleepDetail(root, texts)) return

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

        advanceCarousel(root, bounds)
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
        if (!confirmOnSleepDetail(root, texts)) return

        addCapture(sleepCaptures, texts)

        // 1. Check if "More analysis" is already expanded
        if (!expandedAnalysis && isAnalysisExpanded(texts)) {
            expandedAnalysis = true
            Log.d(TAG, "detected More analysis is already expanded")
        }

        // 2. If not expanded yet, look for "More analysis" button to tap
        if (!expandedAnalysis && texts.any { it.contains("more analysis", true) }) {
            if (clickMoreAnalysis(root, bounds)) {
                expandedAnalysis = true
                publish("Expanding More analysis…")
                Log.d(TAG, "tapped More analysis")
                pause(2_500L)
                return
            }
        }

        // 3. Track scroll progress
        val signature = texts.joinToString("|")
        if (signature == lastScrollSignature) unchangedScrolls++ else unchangedScrolls = 0
        lastScrollSignature = signature

        val parsedSleep = parser.parseSleepDetail(sleepCaptures)
        val hasAllAnalysis = parsedSleep.deepPercent != null &&
                parsedSleep.awakenings != null &&
                parsedSleep.averageSpo2 != null

        val atBottom = expandedAnalysis && (hasAllAnalysis || texts.any { it.contains("about sleep", true) })
        val stalled = unchangedScrolls >= 2 && analysisScrolls >= 1

        if (stalled || atBottom || analysisScrolls >= MAX_ANALYSIS_SCROLLS || timedOut) {
            Log.d(
                TAG,
                "analysis read complete after $analysisScrolls scrolls " +
                        "(stalled=$stalled, atBottom=$atBottom, expanded=$expandedAnalysis)"
            )
            finish()
            return
        }

        // 4. Scroll down using physical gesture drag
        scrollDown(bounds)
        analysisScrolls++
        publish("Reading sleep analysis…")
        pause(1_500L)
    }

    private fun isAnalysisExpanded(texts: List<String>): Boolean {
        val blob = texts.joinToString(" | ").lowercase()
        return blob.contains("collapse") ||
                blob.contains("deep sleep proportion") ||
                blob.contains("light sleep proportion") ||
                blob.contains("number of awakenings") ||
                blob.contains("average blood oxygen")
    }

    private fun clickMoreAnalysis(root: AccessibilityNodeInfo, bounds: Rect): Boolean {
        val node = findNode(root) { n ->
            val text = nodeText(n)?.lowercase() ?: return@findNode false
            text.contains("more analysis")
        } ?: return false

        Log.d(TAG, "found More analysis node: \"${nodeText(node)}\"")

        // Try standard action click first
        if (clickSelfOrAncestor(node, bounds)) {
            Log.d(TAG, "clicked More analysis via ACTION_CLICK")
            return true
        }

        // Gesture tap fallback on the node's screen coordinates
        var targetNode: AccessibilityNodeInfo? = node
        val rect = Rect()
        while (targetNode != null) {
            targetNode.getBoundsInScreen(rect)
            if (rect.height() > 0 && rect.width() > 0) break
            targetNode = targetNode.parent
        }

        if (rect.height() > 0 && isInContentBand(bounds, rect)) {
            Log.d(TAG, "tapping More analysis via gesture at (${rect.exactCenterX()}, ${rect.exactCenterY()})")
            tap(bounds, rect.exactCenterX(), rect.exactCenterY())
            return true
        }

        return false
    }

    // ─────────────────────────────────────────────────────
    //  Staying on the sleep detail screen
    // ─────────────────────────────────────────────────────

    private fun onSleepDetail(texts: List<String>): Boolean {
        val blob = texts.joinToString(" | ").lowercase()
        return SLEEP_MARKERS.any { blob.contains(it) }
    }

    /**
     * The looser test, for a screen we are already reading rather than one we are
     * waiting to appear. Scrolled deep into the analysis section none of the
     * headline markers are on screen any more, so this asks a weaker question:
     * is this still something sleep-shaped, and not the home tab we came from?
     */
    private fun stillOnSleepDetail(texts: List<String>): Boolean {
        if (onSleepDetail(texts)) return true
        val blob = texts.joinToString(" | ").lowercase()
        return blob.contains("sleep") && HOME_TAB_ONLY.none { blob.contains(it) }
    }

    /**
     * Something can still take us off the sleep detail screen mid-read — a tap
     * that landed on the toolbar, or a swipe the app read as a back gesture.
     * Continuing to swipe at whatever replaced it is how a run ended up on the
     * home screen with nothing collected, so notice it and re-open Sleep.
     *
     * One miss is usually just a screen caught mid-transition, so it takes two
     * in a row to count. Captures already taken are kept either way.
     *
     * @return true when the caller may carry on reading this screen.
     */
    private fun confirmOnSleepDetail(root: AccessibilityNodeInfo, texts: List<String>): Boolean {
        if (stillOnSleepDetail(texts)) {
            offSleepStrikes = 0
            return true
        }

        offSleepStrikes++
        if (offSleepStrikes < 2) {
            pause(900L)
            return false
        }

        val pkg = root.packageName?.toString()
        Log.w(TAG, "no longer on the sleep detail screen during $step (package=$pkg)")
        if (!isVivoHealth(pkg)) relaunchVivoHealth()

        // Shared with the "detail never rendered" retry so the two recovery paths
        // cannot hand each other a fresh budget and loop until the watchdog.
        sleepReopens++
        if (sleepReopens <= MAX_SLEEP_OPENS) {
            offSleepStrikes = 0
            sleepOpenAttempts = 0
            goTo(Step.OPEN_SLEEP, OPEN_SLEEP_MS, "Reopening Sleep…")
        } else {
            Log.e(TAG, "could not stay on the sleep detail screen – finishing with what we have")
            finish()
        }
        return false
    }

    /** We were bounced out of Vivo Health entirely; bring it back to the front. */
    private fun relaunchVivoHealth() {
        for (pkg in VIVO_HEALTH_PACKAGES) {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Log.d(TAG, "relaunched $pkg")
                pause(2_500L)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "could not relaunch $pkg", t)
            }
        }
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
        val homeCards = if (homeCaptures.isEmpty()) null else parser.parseHomeCards(homeCaptures)

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            homeCaptures.forEachIndexed { i, c -> Log.d(TAG, "home[$i] = $c") }
            sleepCaptures.forEachIndexed { i, c -> Log.d(TAG, "sleep[$i] = $c") }
        }
        Log.d(TAG, "activity = $activity")
        Log.d(TAG, "sleep = $sleep")
        Log.d(TAG, "homeCards = $homeCards")

        val parsed = ParsedHealthData(
        var parsed = ParsedHealthData(
            activity = activity?.takeIf { it.hasData() },
            sleep = sleep?.takeIf { it.hasData() },
        )
        if (homeCards?.hasAnyData() == true) {
            parsed = parsed.merge(homeCards)
        }

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
        // Start our activity first and only fall back to Back: a global back
        // press is a navigation we cannot aim, and it is not needed when the
        // launch works.
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            )
        } catch (t: Throwable) {
            // Background activity starts can be refused; back out of Vivo Health
            // so the user is not left on someone else's screen. The result has
            // already been delivered either way.
            Log.w(TAG, "could not return to the app – falling back to Back", t)
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    // ─────────────────────────────────────────────────────
    //  Gestures
    // ─────────────────────────────────────────────────────

    /**
     * One press-drag-release, clamped into the content band by [safeX]/[safeY].
     *
     * There is deliberately no hold at the end: the finger lifts as soon as the
     * drag does, which is what makes a pull-to-refresh fire and what a person's
     * swipe actually looks like.
     */
    private fun drag(
        bounds: Rect,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = 320L,
    ) {
        val path = Path().apply {
            moveTo(safeX(bounds, fromX), safeY(bounds, fromY))
            lineTo(safeX(bounds, toX), safeY(bounds, toY))
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        if (!dispatchGesture(gesture, null, null)) {
            Log.e(
                TAG,
                "dispatchGesture refused – canPerformGestures is missing from " +
                        "accessibility_service_config.xml"
            )
        }
    }

    /** Keeps a gesture out of the toolbar above and the navigation bar below. */
    private fun safeY(bounds: Rect, y: Float): Float = y.coerceIn(
        bounds.top + bounds.height() * TOP_SAFE,
        bounds.top + bounds.height() * BOTTOM_SAFE,
    )

    /** Keeps a gesture off the side edges, where it becomes a back gesture. */
    private fun safeX(bounds: Rect, x: Float): Float = x.coerceIn(
        bounds.left + bounds.width() * EDGE_SAFE,
        bounds.left + bounds.width() * (1f - EDGE_SAFE),
    )

    /** Dispatches a single tap gesture inside the safe content band. */
    private fun tap(bounds: Rect, x: Float, y: Float) {
        val sx = safeX(bounds, x)
        val sy = safeY(bounds, y)
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(sx, sy)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60L))
            .build()
        if (!dispatchGesture(gesture, null, null)) {
            Log.e(TAG, "tap gesture refused")
        }
    }

    /** Swipes upwards to scroll page content down. */
    private fun scrollDown(bounds: Rect) {
        val midX = bounds.centerX().toFloat()
        val fromY = bounds.top + bounds.height() * 0.72f
        val toY = bounds.top + bounds.height() * 0.28f
        drag(bounds, midX, fromY, midX, toY, durationMs = 280L)
    }

    /**
     * Moves the vitals carousel on by one card.
     *
     * The row's own scroll action is tried first — it cannot miss and cannot tap
     * anything. Only if the row does not expose one do we swipe, and then across
     * the row's real bounds rather than a guessed fraction of the screen: the old
     * blind swipe could be handed a y from a node that was off screen, putting
     * the gesture down on the navigation bar.
     */
    private fun advanceCarousel(root: AccessibilityNodeInfo, bounds: Rect) {
        val row = carouselRow(root, bounds)
        if (row != null && row.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            Log.d(TAG, "carousel advanced with ACTION_SCROLL_FORWARD")
            return
        }

        val rect = Rect().also { row?.getBoundsInScreen(it) }
        val y = if (rect.height() > 0) rect.exactCenterY() else carouselY(root, bounds)
        val left = if (rect.width() > 0) rect.left.toFloat() else bounds.left.toFloat()
        val width = if (rect.width() > 0) rect.width().toFloat() else bounds.width().toFloat()
        drag(bounds, left + width * 0.78f, y, left + width * 0.22f, y)
    }

    /** The tile row: the horizontally scrollable ancestor of a vitals label. */
    private fun carouselRow(root: AccessibilityNodeInfo, bounds: Rect): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = vitalsAnchor(root, bounds)
        var depth = 0
        while (depth < 6) {
            val current = node ?: return null
            val rect = Rect().also { current.getBoundsInScreen(it) }
            if (current.isScrollable && rect.width() > rect.height()) return current
            node = current.parent
            depth++
        }
        return null
    }

    /** A visible vitals label, used to locate the carousel on screen. */
    private fun vitalsAnchor(root: AccessibilityNodeInfo, bounds: Rect): AccessibilityNodeInfo? =
        findNode(root) { node ->
            val text = nodeText(node)?.lowercase() ?: return@findNode false
            val matches = text.contains("sleep heart rate") ||
                    text.contains("total sleep duration") || text.contains("respiratory") ||
                    text.contains("spo2") || text.contains("spo₂") || text.contains("hrv")
            if (!matches) return@findNode false
            // Off-screen matches ("Average blood oxygen" further down the page)
            // would drag the gesture out of the content band.
            val rect = Rect().also { node.getBoundsInScreen(it) }
            rect.height() > 0 && isInContentBand(bounds, rect)
        }

    private fun isInContentBand(bounds: Rect, rect: Rect): Boolean =
        rect.centerY() > bounds.top + bounds.height() * TOP_SAFE &&
                rect.centerY() < bounds.top + bounds.height() * BOTTOM_SAFE

    /** Fallback vertical centre for the carousel when no label is visible. */
    private fun carouselY(root: AccessibilityNodeInfo, bounds: Rect): Float {
        val rect = Rect().also { vitalsAnchor(root, bounds)?.getBoundsInScreen(it) }
        if (rect.height() > 0) return rect.exactCenterY()
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
     * The home screen has several things reading "Sleep": the card we want, the
     * bottom navigation tab, and labels like "Sleep heart rate". Prefer an exact
     * match, and only consider nodes inside the content band — the toolbar strip
     * at the top holds the screen title and its back arrow, and "clicking" that
     * title walks up to the toolbar and presses back.
     */
    private fun clickSleepCard(root: AccessibilityNodeInfo, bounds: Rect): Boolean {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, 0, candidates) { node ->
            val text = nodeText(node)?.trim()?.lowercase() ?: return@collectNodes false
            if (!text.startsWith("sleep")) return@collectNodes false
            if (NOT_THE_SLEEP_CARD.any { text.contains(it) }) return@collectNodes false
            val rect = Rect().also { node.getBoundsInScreen(it) }
            rect.height() > 0 && isInContentBand(bounds, rect)
        }

        val ordered = candidates.sortedBy { node ->
            when (nodeText(node)?.trim()?.lowercase()) {
                "sleep" -> 0
                else -> 1
            }
        }
        for (node in ordered) {
            if (clickSelfOrAncestor(node, bounds)) {
                Log.d(TAG, "clicked Sleep entry \"${nodeText(node)}\"")
                return true
            }
        }
        return false
    }

    private fun clickByText(root: AccessibilityNodeInfo, text: String, bounds: Rect): Boolean {
        val matches = root.findAccessibilityNodeInfosByText(text) ?: return false
        for (node in matches) {
            if (clickSelfOrAncestor(node, bounds)) return true
        }
        return false
    }

    /**
     * Clicks the node, or the nearest clickable thing containing it.
     *
     * The walk stops early and refuses anything as tall as most of the screen:
     * those are the page container and the toolbar, not a card, and firing a
     * click on the toolbar is what took us back out of the Sleep screen.
     */
    private fun clickSelfOrAncestor(node: AccessibilityNodeInfo?, bounds: Rect): Boolean {
        var next = node
        var depth = 0
        while (depth < 4) {
            val current = next ?: return false
            val rect = Rect().also { current.getBoundsInScreen(it) }
            val tooBig = rect.height() > bounds.height() * 0.6f
            if (tooBig) return false
            if (current.isClickable && current.isEnabled && isInContentBand(bounds, rect)) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            next = current.parent
            depth++
        }
        return false
    }

    /** Scrolls the deepest vertical scrollable container, which is the content list. */
    private fun scroll(root: AccessibilityNodeInfo, action: Int): Boolean {
        val scrollables = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, 0, scrollables) { node ->
            if (!node.isScrollable) return@collectNodes false
            val rect = Rect().also { node.getBoundsInScreen(it) }
            rect.height() >= rect.width()
        }
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
