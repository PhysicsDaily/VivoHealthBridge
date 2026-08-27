package com.vivohealthbridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
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

/** Observable state of the live capture session, for the dashboard to render. */
data class SyncProgress(
    val active: Boolean = false,
    val step: String = "Idle",
    val detail: String = "",
    val percent: Int = 0,
)

/**
 * Reads Vivo Health while **you** navigate it. No gestures are ever injected —
 * the service only watches the accessibility tree, captures whatever metrics
 * are on screen (home rings, sleep detail, heart rate, stress, SpO₂), merges
 * them into [liveCapturedData], and hands everything back when you tap Sync.
 *
 * A self-scheduling [assistedTicker] polls every 500 ms so screens that go
 * quiet (charts animating, then silence) are still re-read, and
 * [onAccessibilityEvent] captures immediately on window content changes for a
 * snappy first read of each screen.
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

        private val _liveCapturedData = MutableStateFlow(ParsedHealthData())
        val liveCapturedData: StateFlow<ParsedHealthData> = _liveCapturedData.asStateFlow()

        val isSyncing: Boolean get() = _progress.value.active

        val isAssistedSyncActive: Boolean get() = isSyncing

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

        private const val MAX_NODES = 400
        private const val MAX_DEPTH = 40

        /** Any one of these on screen means we are on the sleep detail page. */
        private val SLEEP_MARKERS = listOf(
            "total sleep duration", "compared to last", "sleep heart rate",
            "more analysis", "sleep hrv", "sleep spo",
        )

        /**
         * Text only the home tab shows. The sleep detail page mentions "sleep"
         * all the way down, so this is what tells the sleep detail page apart
         * from the home list.
         */
        private val HOME_TAB_ONLY = listOf("steps", "step count", "calories", "stand")

        /**
         * Cards from the *lower* half of the home tab. When the tab is scrolled
         * down far enough to show Heart rate / Stress / Oxygen saturation, the
         * activity-ring labels ("Steps", "Calories", "Stand") are no longer in
         * the tree — without these markers that state would match nothing and
         * the metric cards would never be read.
         */
        private val HOME_CARD_MARKERS = listOf(
            "health care", "activity recommendation", "view ecg",
        )
    }

    private val parser = VivoHealthParser()
    private val handler = Handler(Looper.getMainLooper())

    // ── Assisted Live Capture state ───────────────────────────
    private val liveHomeCaptures = mutableListOf<List<String>>()
    private val liveSleepCaptures = mutableListOf<List<String>>()
    private val liveHeartRateCaptures = mutableListOf<List<String>>()
    private val liveStressCaptures = mutableListOf<List<String>>()
    private val liveOxygenCaptures = mutableListOf<List<String>>()
    private var overlay: LiveSyncOverlay? = null
    private var lastLiveCaptureSignature: String? = null

    private val assistedTicker = object : Runnable {
        override fun run() {
            if (!isSyncing) return
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
            if (isSyncing) {
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
        Log.d(TAG, "connected")
    }

    /** Inspect the screen as soon as window content or scroll changes. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (isSyncing) {
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

        // 2. Home Activity rings & Home Cards (Heart rate, Stress, Oxygen, Weight)
        val homeBlob = texts.joinToString(" | ").lowercase()
        val isHome = !isSleep && (
                texts.any {
                    it.equals("steps", true) || it.equals("calories", true) ||
                            it.equals("stand", true) || it.contains("step count", true)
                } ||
                        HOME_CARD_MARKERS.any { homeBlob.contains(it) } ||
                        // Several metric cards visible at once only happens on the
                        // home tab — each standalone detail screen shows one metric.
                        (homeBlob.contains("heart rate") &&
                                homeBlob.contains("oxygen saturation") &&
                                homeBlob.contains("stress"))
                )
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

    private fun abortRun(reason: String) {
        Log.w(TAG, "run aborted: $reason")
        handler.removeCallbacks(assistedTicker)
        overlay?.hide()
        _progress.value = SyncProgress(active = false, step = reason)
        val callback = syncCallback
        syncCallback = null
        callback?.invoke(ParsedHealthData())
    }

    // ─────────────────────────────────────────────────────
    //  Screen detection
    // ─────────────────────────────────────────────────────

    private fun onSleepDetail(texts: List<String>): Boolean {
        val blob = texts.joinToString(" | ").lowercase()
        return SLEEP_MARKERS.any { blob.contains(it) }
    }

    /**
     * The looser test, for a screen we are already reading rather than one we
     * are waiting to appear. Scrolled deep into the analysis section none of
     * the headline markers are on screen any more, so this asks a weaker
     * question: is this still something sleep-shaped, and not the home tab?
     */
    private fun stillOnSleepDetail(texts: List<String>): Boolean {
        if (onSleepDetail(texts)) return true
        val blob = texts.joinToString(" | ").lowercase()
        return blob.contains("sleep") && HOME_TAB_ONLY.none { blob.contains(it) }
    }

    // ─────────────────────────────────────────────────────
    //  Finish
    // ─────────────────────────────────────────────────────

    /** Bring our own UI back so the result is visible without the user hunting for it. */
    private fun returnToApp() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not return to the app", t)
        }
    }

    // ─────────────────────────────────────────────────────
    //  Node helpers
    // ─────────────────────────────────────────────────────

    private fun isVivoHealth(packageName: String?): Boolean {
        val name = packageName ?: return false
        return VIVO_HEALTH_PACKAGES.any { name.startsWith(it, ignoreCase = true) } ||
                (name.contains("vivo", true) && name.contains("health", true))
    }

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

    private fun addCapture(target: MutableList<List<String>>, texts: List<String>) {
        if (texts.isEmpty()) return
        if (target.isNotEmpty() && target.last() == texts) return
        target.add(texts)
    }
}