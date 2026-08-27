package com.vivohealthbridge.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.vivohealthbridge.data.models.ParsedHealthData

/**
 * Floating HUD overlay rendered on top of the Vivo Health app during assisted sync.
 *
 * Deliberately tiny: a single pill that reads "3/5 ✓" so it answers the only
 * question that matters mid-capture — *how much has been measured* — without
 * covering the screen being read. Tapping the pill expands the per-metric
 * checklist; tapping ✕ cancels; the pill itself drags anywhere.
 *
 * Uses [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY], which accessibility
 * services can render without requiring extra overlay permissions.
 */
class LiveSyncOverlay(
    private val context: Context,
    private val onSyncClicked: () -> Unit,
    private val onCancelClicked: () -> Unit,
) {
    companion object {
        private const val TAG = "LiveSyncOverlay"

        private const val COLOR_BG = 0xF0151A23.toInt()         // Dark charcoal slate
        private const val COLOR_BORDER = 0xFF00E5FF.toInt()     // Neon Cyan
        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()       // White
        private const val COLOR_MUTED = 0xFF94A3B8.toInt()      // Slate grey
        private const val COLOR_ACCENT = 0xFF00E5FF.toInt()     // Cyan accent
        private const val COLOR_SUCCESS = 0xFF10B981.toInt()    // Emerald green
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: View? = null
    var isShowing: Boolean = false
        private set

    // Compact state
    private var pillText: TextView? = null
    private var expandedPanel: LinearLayout? = null
    private var lastData: ParsedHealthData? = null
    private var expanded = false

    // Expanded-state badges
    private var stepsTextView: TextView? = null
    private var sleepTextView: TextView? = null
    private var hrTextView: TextView? = null
    private var stressTextView: TextView? = null
    private var spo2TextView: TextView? = null

    private val params = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dpToPx(24)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isShowing) return

        mainHandler.post {
            try {
                if (rootView == null) {
                    rootView = buildOverlayView()
                }
                windowManager.addView(rootView, params)
                isShowing = true
                Log.d(TAG, "Overlay shown successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to attach overlay window: ${e.message}")
                isShowing = false
            }
        }
    }

    fun update(data: ParsedHealthData) {
        lastData = data
        if (!isShowing) return
        mainHandler.post {
            try {
                updatePill(data)
                updateBadges(data)
            } catch (e: Exception) {
                Log.w(TAG, "Error updating overlay: ${e.message}")
            }
        }
    }

    fun hide() {
        if (!isShowing) return
        mainHandler.post {
            try {
                rootView?.let { windowManager.removeView(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove overlay window: ${e.message}")
            } finally {
                isShowing = false
            }
        }
    }

    // ── Compact pill ─────────────────────────────────────────

    /** How many of the five metric groups have been captured so far. */
    private fun capturedCount(data: ParsedHealthData): Int {
        var n = 0
        if ((data.activity?.steps ?: 0L) > 0 || data.activity?.activeCalories != null) n++
        if (data.sleep?.hasData() == true) n++
        if (data.heartRate?.hasData() == true || data.heartRateBpm != null) n++
        if (data.stress?.hasData() == true || data.stressLevel != null) n++
        if (data.oxygenSaturation?.hasData() == true) n++
        return n
    }

    private fun updatePill(data: ParsedHealthData) {
        val captured = capturedCount(data)
        val tv = pillText ?: return
        if (captured > 0) {
            tv.text = "$captured/5 ✓"
            tv.setTextColor(COLOR_SUCCESS)
        } else {
            tv.text = "0/5"
            tv.setTextColor(COLOR_MUTED)
        }
    }

    private fun toggleExpanded() {
        expanded = !expanded
        expandedPanel?.visibility = if (expanded) View.VISIBLE else View.GONE
        lastData?.let { updateBadges(it) }
    }

    // ── Expanded checklist ───────────────────────────────────

    private fun updateBadges(data: ParsedHealthData) {
        if (!expanded) return
        val act = data.activity
        val sleep = data.sleep
        val hr = data.heartRate
        val st = data.stress
        val oxy = data.oxygenSaturation

        stepsTextView?.let { tv ->
            if (act?.steps != null) {
                tv.text = "🏃 Steps: ${act.steps} ✓"
                tv.setTextColor(COLOR_SUCCESS)
            } else {
                tv.text = "🏃 Steps: —"
                tv.setTextColor(COLOR_MUTED)
            }
        }

        sleepTextView?.let { tv ->
            if (sleep?.totalMinutes != null) {
                tv.text = "😴 Sleep: ${sleep.totalMinutes / 60}h ${sleep.totalMinutes % 60}m ✓"
                tv.setTextColor(COLOR_SUCCESS)
            } else if ((sleep?.vitalsCount ?: 0) > 0 || (sleep?.stagesCount ?: 0) > 0) {
                tv.text = "😴 Sleep: ${sleep?.vitalsCount} vitals ✓"
                tv.setTextColor(COLOR_SUCCESS)
            } else {
                tv.text = "😴 Sleep: —"
                tv.setTextColor(COLOR_MUTED)
            }
        }

        hrTextView?.let { tv ->
            val value = hr?.currentBpm?.let { "$it bpm" }
                ?: hr?.range?.let { "${it.min}-${it.max}" }
                ?: data.heartRateBpm?.let { "$it bpm" }
            if (value != null) {
                tv.text = "💓 HR: $value ✓"
                tv.setTextColor(COLOR_SUCCESS)
            } else {
                tv.text = "💓 HR: —"
                tv.setTextColor(COLOR_MUTED)
            }
        }

        stressTextView?.let { tv ->
            val value = st?.current ?: st?.average ?: data.stressLevel
            if (value != null) {
                tv.text = "🧠 Stress: $value ✓"
                tv.setTextColor(COLOR_SUCCESS)
            } else {
                tv.text = "🧠 Stress: —"
                tv.setTextColor(COLOR_MUTED)
            }
        }

        spo2TextView?.let { tv ->
            val value = oxy?.current ?: oxy?.average ?: oxy?.range?.let { "${it.min}-${it.max}%" }
            if (value != null) {
                tv.text = "🫁 SpO₂: $value ✓"
                tv.setTextColor(COLOR_SUCCESS)
            } else {
                tv.text = "🫁 SpO₂: —"
                tv.setTextColor(COLOR_MUTED)
            }
        }
    }

    // ── View construction ────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun buildOverlayView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(COLOR_BG)
                cornerRadius = dpToPx(20).toFloat()
                setStroke(dpToPx(1), COLOR_BORDER)
            }
            background = bg
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            elevation = dpToPx(8).toFloat()
        }

        // ── Pill row: "3/5 ✓  |  Sync  ✕" ─────────────────────
        val pillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val pill = TextView(context).apply {
            text = "0/5"
            setTextColor(COLOR_MUTED)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(4), 0, dpToPx(8), 0)
            // Tap the pill to expand/collapse the checklist.
            setOnClickListener { toggleExpanded() }
        }
        pillText = pill

        val syncBtn = TextView(context).apply {
            text = "Sync"
            setTextColor(COLOR_TEXT)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            val btnBg = GradientDrawable().apply {
                setColor(COLOR_SUCCESS)
                cornerRadius = dpToPx(14).toFloat()
            }
            background = btnBg
            setPadding(dpToPx(12), dpToPx(5), dpToPx(12), dpToPx(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dpToPx(8) }
            setOnClickListener { onSyncClicked() }
        }

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#F87171"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(6), dpToPx(2), dpToPx(2), dpToPx(2))
            setOnClickListener { onCancelClicked() }
        }

        pillRow.addView(pill)
        pillRow.addView(syncBtn)
        pillRow.addView(closeBtn)
        root.addView(pillRow)

        // ── Expanded checklist (hidden by default) ────────────
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(4) }
        }
        stepsTextView = makeBadge("🏃 Steps: —")
        sleepTextView = makeBadge("😴 Sleep: —")
        hrTextView = makeBadge("💓 HR: —")
        stressTextView = makeBadge("🧠 Stress: —")
        spo2TextView = makeBadge("🫁 SpO₂: —")
        panel.addView(stepsTextView)
        panel.addView(sleepTextView)
        panel.addView(hrTextView)
        panel.addView(stressTextView)
        panel.addView(spo2TextView)
        root.addView(panel)
        expandedPanel = panel

        // ── Drag Listener ─────────────────────────────────────
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    // Children with click listeners consume DOWN before this
                    // runs, so claiming it here only affects empty areas and
                    // keeps the drag tracking alive.
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(root, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> isDragging
                else -> false
            }
        }

        return root
    }

    private fun makeBadge(initialText: String): TextView = TextView(context).apply {
        text = initialText
        setTextColor(COLOR_MUTED)
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.toFloat(),
        context.resources.displayMetrics
    ).toInt()
}