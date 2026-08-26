package com.vivohealthbridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.vivohealthbridge.data.models.ParsedHealthData
import com.vivohealthbridge.parser.VivoHealthParser

/**
 * Accessibility service that automates Vivo Health to extract sleep data.
 *
 * Flow (matches the user's manual steps):
 *   1. WAIT_FOR_HOME      – Vivo Health is open, wait for home screen
 *   2. PULL_TO_SYNC       – Swipe down to trigger sync, wait ~20s for "sync complete"
 *   3. CLICK_SLEEP        – Tap the "Sleep" card (first option)
 *   4. WAIT_SLEEP_LOAD    – Wait ~15–20s for the sleep detail to load
 *   5. COLLECT_INITIAL    – Collect texts (total duration, chart labels, first stat card)
 *   6. SWIPE_RIGHT_CARDS  – Swipe right on the stat-card area to reveal
 *                           Respiratory rate → SpO2 → HRV (collect at each step)
 *   7. SCROLL_TO_ANALYSIS – Scroll down to "More analysis"
 *   8. CLICK_MORE_ANALYSIS– Tap "More analysis" if it's a button
 *   9. COLLECT_ANALYSIS   – Collect stage durations, awakenings, continuity, avg SpO2
 *  10. DONE               – Parse everything & deliver via callback
 */
class VivoHealthAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VivoHealthA11y"

        val VIVO_HEALTH_PACKAGES = listOf(
            "com.vivo.health",
            "com.vivo.sports",
        )

        var instance: VivoHealthAccessibilityService? = null
            private set

        var isSyncing = false
            private set

        var syncCallback: ((ParsedHealthData) -> Unit)? = null

        /** Human-readable step for the UI to display. */
        var currentStepDescription: String = "Idle"
            private set

        // ── State machine ─────────────────────────────────
        private var step = Step.IDLE
        private var collectedSleepTexts = mutableListOf<String>()
        private var swipeCount = 0
        private const val MAX_CARD_SWIPES = 4  // HR → Resp → SpO2 → HRV
        private var retryCount = 0
        private const val MAX_RETRIES = 4
        private var syncWaitStart = 0L
        private const val SYNC_TIMEOUT_MS = 25_000L
        private var loadWaitStart = 0L
        private const val LOAD_TIMEOUT_MS = 22_000L

        fun isServiceRunning(): Boolean = instance != null

        fun startSync() {
            isSyncing = true
            step = Step.WAIT_FOR_HOME
            collectedSleepTexts.clear()
            swipeCount = 0
            retryCount = 0
            currentStepDescription = "Waiting for Vivo Health…"
            Log.d(TAG, "Sync started – waiting for Vivo Health home")
        }

        fun stopSync() {
            isSyncing = false
            step = Step.IDLE
            currentStepDescription = "Idle"
        }
    }

    // Steps in the automation flow
    private enum class Step {
        IDLE,
        WAIT_FOR_HOME,
        PULL_TO_SYNC,
        WAIT_SYNC_COMPLETE,
        CLICK_SLEEP,
        WAIT_SLEEP_LOAD,
        COLLECT_INITIAL,
        SWIPE_RIGHT_CARDS,
        SCROLL_TO_ANALYSIS,
        CLICK_MORE_ANALYSIS,
        COLLECT_ANALYSIS,
        DONE
    }

    private val parser = VivoHealthParser()
    private val handler = Handler(Looper.getMainLooper())
    private var lastEventTime = 0L
    @Volatile private var isProcessing = false

    // ─────────────────────────────────────────────────────
    //  Service lifecycle
    // ─────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility Service connected")

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 300
        }
    }

    // ─────────────────────────────────────────────────────
    //  Event handling
    // ─────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isSyncing || event == null || isProcessing) return

        val packageName = event.packageName?.toString() ?: return
        val isVivoHealth = VIVO_HEALTH_PACKAGES.any { packageName.contains(it, ignoreCase = true) } ||
                (packageName.contains("vivo", ignoreCase = true) && packageName.contains("health", ignoreCase = true))
        if (!isVivoHealth) return

        val now = System.currentTimeMillis()
        if (now - lastEventTime < 800) return
        lastEventTime = now

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ processStep() }, 1200)
            }
        }
    }

    // ─────────────────────────────────────────────────────
    //  State machine dispatcher
    // ─────────────────────────────────────────────────────

    private fun processStep() {
        if (!isSyncing || isProcessing) return
        isProcessing = true

        try {
            val root = rootInActiveWindow ?: run {
                isProcessing = false
                return
            }

            val texts = mutableListOf<String>()
            collectAllText(root, texts)

            Log.d(TAG, "Step=$step, collected ${texts.size} text nodes")
            texts.take(30).forEachIndexed { idx, t -> Log.v(TAG, "  [$idx] \"$t\"") }

            when (step) {
                Step.WAIT_FOR_HOME -> handleHome(root, texts)
                Step.PULL_TO_SYNC -> handlePullToSync(root)
                Step.WAIT_SYNC_COMPLETE -> handleWaitSyncComplete(texts)
                Step.CLICK_SLEEP -> handleClickSleep(root)
                Step.WAIT_SLEEP_LOAD -> handleWaitSleepLoad(texts)
                Step.COLLECT_INITIAL -> handleCollectInitial(root, texts)
                Step.SWIPE_RIGHT_CARDS -> handleSwipeRightCards(root, texts)
                Step.SCROLL_TO_ANALYSIS -> handleScrollToAnalysis(root, texts)
                Step.CLICK_MORE_ANALYSIS -> handleClickMoreAnalysis(root, texts)
                Step.COLLECT_ANALYSIS -> handleCollectAnalysis(texts)
                Step.DONE -> { /* already finished */ }
                Step.IDLE -> { /* not syncing */ }
            }

            root.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error in processStep ($step)", e)
        } finally {
            isProcessing = false
        }
    }

    // ─────────────────────────────────────────────────────
    //  Step handlers
    // ─────────────────────────────────────────────────────

    /** Step 1: Home screen detected → pull down to sync. */
    private fun handleHome(root: AccessibilityNodeInfo, texts: List<String>) {
        currentStepDescription = "Home detected, pulling down to sync…"
        Log.d(TAG, "Home screen detected, performing pull-to-sync")
        step = Step.PULL_TO_SYNC
        // Small delay then swipe down
        handler.postDelayed({ processStep() }, 500)
    }

    /** Step 2: Perform a swipe-down gesture on the home screen. */
    private fun handlePullToSync(root: AccessibilityNodeInfo) {
        currentStepDescription = "Syncing data from watch…"
        Log.d(TAG, "Performing pull-to-sync gesture")
        performSwipeDown()
        step = Step.WAIT_SYNC_COMPLETE
        syncWaitStart = System.currentTimeMillis()
    }

    /** Step 3: Wait until "sync complete" or timeout (~20s). */
    private fun handleWaitSyncComplete(texts: List<String>) {
        val elapsed = System.currentTimeMillis() - syncWaitStart
        val syncDone = texts.any {
            it.contains("sync complete", ignoreCase = true) ||
            it.contains("synced", ignoreCase = true) ||
            it.contains("sync completed", ignoreCase = true) ||
            it.contains("data synchronized", ignoreCase = true)
        }

        if (syncDone || elapsed > SYNC_TIMEOUT_MS) {
            Log.d(TAG, "Sync complete detected=$syncDone, elapsed=${elapsed}ms")
            currentStepDescription = "Sync complete, opening Sleep…"
            step = Step.CLICK_SLEEP
            retryCount = 0
            handler.postDelayed({ processStep() }, 800)
        } else {
            // Keep waiting — re-check after delay
            currentStepDescription = "Waiting for sync to complete…"
            handler.postDelayed({ isProcessing = false; refreshAndReprocess() }, 3000)
        }
    }

    /** Step 4: Click the "Sleep" card on the home screen. */
    private fun handleClickSleep(root: AccessibilityNodeInfo) {
        currentStepDescription = "Opening Sleep details…"
        Log.d(TAG, "Looking for Sleep card")

        val clicked = findAndClickNode(root, "Sleep")
        if (clicked) {
            Log.d(TAG, "Clicked Sleep card")
            step = Step.WAIT_SLEEP_LOAD
            loadWaitStart = System.currentTimeMillis()
            retryCount = 0
        } else {
            retryCount++
            if (retryCount < MAX_RETRIES) {
                Log.w(TAG, "Sleep card not found, retry $retryCount")
                // Try scrolling to find it
                findScrollableNode(root)?.let {
                    it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    it.recycle()
                }
                handler.postDelayed({ isProcessing = false; refreshAndReprocess() }, 1500)
            } else {
                Log.e(TAG, "Could not find Sleep card after $MAX_RETRIES retries")
                finishSync()
            }
        }
    }

    /** Step 5: Wait for sleep detail to fully load (~15–20s). */
    private fun handleWaitSleepLoad(texts: List<String>) {
        val elapsed = System.currentTimeMillis() - loadWaitStart
        // Detect loaded state: look for sleep-related keywords
        val loaded = texts.any {
            it.contains("total sleep", ignoreCase = true) ||
            it.contains("sleep duration", ignoreCase = true) ||
            it.contains("sleep score", ignoreCase = true) ||
            it.contains("sleeping heart rate", ignoreCase = true) ||
            it.contains("hr", ignoreCase = true) && it.contains("min", ignoreCase = true)
        }

        if (loaded || elapsed > LOAD_TIMEOUT_MS) {
            Log.d(TAG, "Sleep detail loaded (detected=$loaded, elapsed=${elapsed}ms)")
            currentStepDescription = "Reading sleep data…"
            step = Step.COLLECT_INITIAL
            handler.postDelayed({ processStep() }, 1000)
        } else {
            currentStepDescription = "Waiting for sleep data to load…"
            handler.postDelayed({ isProcessing = false; refreshAndReprocess() }, 3000)
        }
    }

    /** Step 6: Collect the initially visible sleep data. */
    private fun handleCollectInitial(root: AccessibilityNodeInfo, texts: List<String>) {
        currentStepDescription = "Collecting sleep overview…"
        collectedSleepTexts.addAll(texts)
        Log.d(TAG, "Collected initial sleep texts (${texts.size} nodes)")
        step = Step.SWIPE_RIGHT_CARDS
        swipeCount = 0
        handler.postDelayed({ processStep() }, 500)
    }

    /** Step 7: Swipe right on the stat-card area to reveal more cards. */
    private fun handleSwipeRightCards(root: AccessibilityNodeInfo, texts: List<String>) {
        if (swipeCount >= MAX_CARD_SWIPES) {
            Log.d(TAG, "Done swiping stat cards ($swipeCount swipes)")
            step = Step.SCROLL_TO_ANALYSIS
            handler.postDelayed({ processStep() }, 500)
            return
        }

        currentStepDescription = "Sliding stat cards (${swipeCount + 1}/$MAX_CARD_SWIPES)…"
        collectedSleepTexts.addAll(texts)

        // Perform a horizontal swipe-right gesture on the card area
        performSwipeRight()
        swipeCount++
        Log.d(TAG, "Swipe right #$swipeCount performed")
        // Wait for the card to settle, then processStep will collect
        handler.postDelayed({ isProcessing = false; refreshAndReprocess() }, 2000)
    }

    /** Step 8: Scroll down to find "More analysis". */
    private fun handleScrollToAnalysis(root: AccessibilityNodeInfo, texts: List<String>) {
        currentStepDescription = "Looking for More analysis…"
        collectedSleepTexts.addAll(texts)

        val foundMoreAnalysis = texts.any {
            it.contains("more analysis", ignoreCase = true) ||
            it.contains("view more", ignoreCase = true)
        }

        if (foundMoreAnalysis) {
            Log.d(TAG, "Found More analysis section")
            step = Step.CLICK_MORE_ANALYSIS
            handler.postDelayed({ processStep() }, 500)
        } else {
            // Scroll down to reveal it
            val scrolled = findScrollableNode(root)?.let {
                it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                it.recycle()
                true
            } ?: false

            retryCount++
            if (!scrolled || retryCount > MAX_RETRIES) {
                Log.w(TAG, "Could not find More analysis, collecting what we have")
                step = Step.COLLECT_ANALYSIS
                handler.postDelayed({ processStep() }, 500)
            } else {
                Log.d(TAG, "Scrolled down, retry $retryCount")
                handler.postDelayed({ isProcessing = false; refreshAndReprocess() }, 1500)
            }
        }
    }

    /** Step 9: Click "More analysis" if clickable. */
    private fun handleClickMoreAnalysis(root: AccessibilityNodeInfo, texts: List<String>) {
        currentStepDescription = "Opening More analysis…"

        val clicked = findAndClickNode(root, "More analysis")
            || findAndClickNode(root, "more analysis")
            || findAndClickNode(root, "View more")

        if (clicked) {
            Log.d(TAG, "Clicked More analysis")
            // Wait for expanded view to load
            handler.postDelayed({
                step = Step.COLLECT_ANALYSIS
                isProcessing = false
                refreshAndReprocess()
            }, 2500)
        } else {
            // May already be visible / not a separate button
            Log.d(TAG, "More analysis not clickable, collecting visible data")
            step = Step.COLLECT_ANALYSIS
            handler.postDelayed({ processStep() }, 500)
        }
    }

    /** Step 10: Collect analysis data and finish. */
    private fun handleCollectAnalysis(texts: List<String>) {
        currentStepDescription = "Reading sleep analysis…"
        collectedSleepTexts.addAll(texts)
        Log.d(TAG, "Collected analysis texts (${texts.size} nodes)")
        finishSync()
    }

    // ─────────────────────────────────────────────────────
    //  Finish & deliver
    // ─────────────────────────────────────────────────────

    private fun finishSync() {
        currentStepDescription = "Parsing sleep data…"
        Log.d(TAG, "Finishing sync. Total collected: ${collectedSleepTexts.size} texts")
        collectedSleepTexts.forEachIndexed { i, t -> Log.d(TAG, "  all[$i] \"$t\"") }

        val parsed = parser.parseSleepScreen(collectedSleepTexts)
        Log.d(TAG, "Parsed sleep data: $parsed")

        currentStepDescription = "Done"
        step = Step.DONE
        syncCallback?.invoke(parsed)
        stopSync()

        // Navigate back to our app
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // ─────────────────────────────────────────────────────
    //  Gestures
    // ─────────────────────────────────────────────────────

    /** Swipe down from top ~30% to ~70% of screen (pull-to-refresh). */
    private fun performSwipeDown() {
        val displayMetrics = resources.displayMetrics
        val screenW = displayMetrics.widthPixels.toFloat()
        val screenH = displayMetrics.heightPixels.toFloat()

        val path = Path().apply {
            moveTo(screenW / 2f, screenH * 0.25f)
            lineTo(screenW / 2f, screenH * 0.75f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 600))
            .build()
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "Swipe-down gesture dispatched")
    }

    /** Swipe right on the stat-card area (middle of screen). */
    private fun performSwipeRight() {
        val displayMetrics = resources.displayMetrics
        val screenW = displayMetrics.widthPixels.toFloat()
        val screenH = displayMetrics.heightPixels.toFloat()

        val y = screenH * 0.55f  // roughly where stat cards sit
        val path = Path().apply {
            moveTo(screenW * 0.75f, y)
            lineTo(screenW * 0.25f, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "Swipe-right gesture dispatched")
    }

    // ─────────────────────────────────────────────────────
    //  Node helpers
    // ─────────────────────────────────────────────────────

    private fun refreshAndReprocess() {
        // Trigger a fresh accessibility event by reading the root again
        handler.postDelayed({ processStep() }, 500)
    }

    private fun collectAllText(node: AccessibilityNodeInfo?, texts: MutableList<String>) {
        node ?: return
        try {
            node.text?.toString()?.let { text ->
                if (text.isNotBlank()) texts.add(text.trim())
            }
            node.contentDescription?.toString()?.let { desc ->
                if (desc.isNotBlank() && !texts.contains(desc.trim())) {
                    texts.add(desc.trim())
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectAllText(child, texts)
                child.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting text", e)
        }
    }

    private fun findAndClickNode(node: AccessibilityNodeInfo?, targetText: String): Boolean {
        node ?: return false
        try {
            val nodes = node.findAccessibilityNodeInfosByText(targetText)
            if (nodes != null && nodes.isNotEmpty()) {
                for (n in nodes) {
                    if (n.isClickable) {
                        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        n.recycle()
                        return true
                    }
                    var parent = n.parent
                    var depth = 0
                    while (parent != null && depth < 6) {
                        if (parent.isClickable) {
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            parent.recycle()
                            n.recycle()
                            return true
                        }
                        val old = parent
                        parent = parent.parent
                        old.recycle()
                        depth++
                    }
                    parent?.recycle()
                    n.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding/clicking: $targetText", e)
        }
        return false
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findScrollableNode(child)
            if (result != null) return result
            child?.recycle()
        }
        return null
    }

    // ─────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted")
        if (isSyncing) {
            stopSync()
            syncCallback?.invoke(ParsedHealthData())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopSync()
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Accessibility Service destroyed")
    }
}
