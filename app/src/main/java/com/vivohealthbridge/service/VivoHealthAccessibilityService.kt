package com.vivohealthbridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.vivohealthbridge.data.models.ParsedHealthData
import com.vivohealthbridge.parser.VivoHealthParser

class VivoHealthAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VivoHealthA11y"
        
        // Known Vivo Health package names
        val VIVO_HEALTH_PACKAGES = listOf(
            "com.vivo.health",
            "com.vivo.sports",
            "com.vivo.weather" // sometimes bundled
        )

        var instance: VivoHealthAccessibilityService? = null
            private set

        var isSyncing = false
            private set

        var syncCallback: ((ParsedHealthData) -> Unit)? = null

        private var currentPage = "idle" // idle, home, sleep, heartrate, stress, spo2
        private var collectedHomeTexts = mutableListOf<String>()
        private var collectedDetailTexts = mutableMapOf<String, List<String>>()
        private var detailPages = listOf("Sleep", "Heart rate", "Stress", "Oxygen saturation")
        private var currentDetailIndex = -1
        private var retryCount = 0
        private const val MAX_RETRIES = 3

        fun isServiceRunning(): Boolean = instance != null

        fun startSync() {
            isSyncing = true
            currentPage = "home"
            collectedHomeTexts.clear()
            collectedDetailTexts.clear()
            currentDetailIndex = -1
            retryCount = 0
            Log.d(TAG, "Sync started - waiting for Vivo Health to open")
        }

        fun stopSync() {
            isSyncing = false
            currentPage = "idle"
        }
    }

    private val parser = VivoHealthParser()
    private val handler = Handler(Looper.getMainLooper())
    private var lastEventTime = 0L
    private var isProcessing = false

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
            notificationTimeout = 200
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isSyncing || event == null || isProcessing) return

        val packageName = event.packageName?.toString() ?: return

        // Only process events from Vivo Health
        val isVivoHealth = VIVO_HEALTH_PACKAGES.any { packageName.contains(it, ignoreCase = true) }
                || packageName.contains("vivo", ignoreCase = true) && packageName.contains("health", ignoreCase = true)

        if (!isVivoHealth) return

        // Debounce events
        val now = System.currentTimeMillis()
        if (now - lastEventTime < 500) return
        lastEventTime = now

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Delay to let UI settle
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({
                    processCurrentPage()
                }, 1500)
            }
        }
    }

    private fun processCurrentPage() {
        if (!isSyncing || isProcessing) return
        isProcessing = true

        try {
            val root = rootInActiveWindow ?: run {
                isProcessing = false
                return
            }

            val texts = mutableListOf<String>()
            collectAllText(root, texts)
            root.recycle()

            if (texts.isEmpty()) {
                isProcessing = false
                return
            }

            Log.d(TAG, "Page: $currentPage, collected ${texts.size} text nodes")

            when (currentPage) {
                "home" -> {
                    collectedHomeTexts.addAll(texts)
                    // After reading home, start reading detail pages
                    handler.postDelayed({
                        currentDetailIndex = 0
                        navigateToDetailPage()
                    }, 1000)
                }
                "detail" -> {
                    val pageName = if (currentDetailIndex in detailPages.indices) {
                        detailPages[currentDetailIndex]
                    } else "unknown"
                    collectedDetailTexts[pageName] = texts.toList()
                    Log.d(TAG, "Collected detail page: $pageName with ${texts.size} texts")

                    // Go back and move to next detail page
                    handler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        handler.postDelayed({
                            currentDetailIndex++
                            if (currentDetailIndex < detailPages.size) {
                                navigateToDetailPage()
                            } else {
                                finishSync()
                            }
                        }, 1500)
                    }, 500)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing page", e)
        } finally {
            isProcessing = false
        }
    }

    private fun navigateToDetailPage() {
        if (currentDetailIndex >= detailPages.size) {
            finishSync()
            return
        }

        val targetCard = detailPages[currentDetailIndex]
        Log.d(TAG, "Navigating to: $targetCard")

        val root = rootInActiveWindow ?: run {
            retryOrSkip()
            return
        }

        val found = findAndClickNode(root, targetCard)
        root.recycle()

        if (found) {
            currentPage = "detail"
            Log.d(TAG, "Clicked on: $targetCard")
        } else {
            Log.w(TAG, "Could not find card: $targetCard")
            retryOrSkip()
        }
    }

    private fun retryOrSkip() {
        retryCount++
        if (retryCount < MAX_RETRIES) {
            handler.postDelayed({ navigateToDetailPage() }, 2000)
        } else {
            retryCount = 0
            currentDetailIndex++
            if (currentDetailIndex < detailPages.size) {
                handler.postDelayed({ navigateToDetailPage() }, 1000)
            } else {
                finishSync()
            }
        }
    }

    private fun finishSync() {
        Log.d(TAG, "Finishing sync, parsing all collected data")

        // Combine all collected texts
        val allTexts = mutableListOf<String>()
        allTexts.addAll(collectedHomeTexts)
        collectedDetailTexts.values.forEach { allTexts.addAll(it) }

        // Parse the combined data
        val parsedData = parser.parseAllText(allTexts)
        Log.d(TAG, "Parsed data: $parsedData")

        // Deliver result
        syncCallback?.invoke(parsedData)
        stopSync()

        // Go back to our app
        performGlobalAction(GLOBAL_ACTION_BACK)
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
                val child = node.getChild(i)
                if (child != null) {
                    collectAllText(child, texts)
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting text from node", e)
        }
    }

    private fun findAndClickNode(node: AccessibilityNodeInfo?, targetText: String): Boolean {
        node ?: return false
        try {
            // First try finding by text directly
            val nodes = node.findAccessibilityNodeInfosByText(targetText)
            if (nodes != null && nodes.isNotEmpty()) {
                for (n in nodes) {
                    // Try clicking the node itself
                    if (n.isClickable) {
                        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        n.recycle()
                        return true
                    }
                    // Try clicking parent nodes
                    var parent = n.parent
                    var depth = 0
                    while (parent != null && depth < 5) {
                        if (parent.isClickable) {
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            parent.recycle()
                            n.recycle()
                            return true
                        }
                        val oldParent = parent
                        parent = parent.parent
                        oldParent.recycle()
                        depth++
                    }
                    parent?.recycle()
                    n.recycle()
                }
            }
            // If not directly visible, try scrolling forward
            findScrollableNode(node)?.let { scrollable ->
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                scrollable.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding/clicking node: $targetText", e)
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
