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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.vivohealthbridge.data.models.ParsedHealthData

/**
 * Floating HUD overlay rendered on top of the Vivo Health app during assisted sync.
 *
 * Uses [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY], which accessibility
 * services can render without requiring extra overlay permissions.
 *
 * Provides real-time metrics feedback as the user navigates the app, and allows
 * committing the sync with one tap on [Sync to Health Connect].
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

    // Badges
    private var stepsTextView: TextView? = null
    private var sleepTextView: TextView? = null
    private var vitalsTextView: TextView? = null
    private var stagesTextView: TextView? = null
    private var syncButton: Button? = null

    // Window params
    private val params = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        width = dpToPx(280)
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dpToPx(48) // just below standard status bar
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
        if (!isShowing) return
        mainHandler.post {
            try {
                val act = data.activity
                val sleep = data.sleep

                // Steps
                if (act?.steps != null) {
                    val goal = act.stepsGoal?.let { "/$it" } ?: ""
                    stepsTextView?.text = "🏃 Steps: ${act.steps}$goal ✓"
                    stepsTextView?.setTextColor(COLOR_SUCCESS)
                } else {
                    stepsTextView?.text = "🏃 Steps: —"
                    stepsTextView?.setTextColor(COLOR_MUTED)
                }

                // Sleep Duration
                if (sleep?.totalMinutes != null) {
                    val h = sleep.totalMinutes / 60
                    val m = sleep.totalMinutes % 60
                    val scoreStr = sleep.score?.let { " ($it pts)" } ?: ""
                    sleepTextView?.text = "😴 Sleep: ${h}h ${m}m$scoreStr ✓"
                    sleepTextView?.setTextColor(COLOR_SUCCESS)
                } else {
                    sleepTextView?.text = "😴 Sleep: —"
                    sleepTextView?.setTextColor(COLOR_MUTED)
                }

                // Vitals
                val vitals = sleep?.vitalsCount ?: 0
                if (vitals > 0) {
                    vitalsTextView?.text = "💓 Vitals: $vitals/4 captured ✓"
                    vitalsTextView?.setTextColor(COLOR_SUCCESS)
                } else {
                    vitalsTextView?.text = "💓 Vitals: —"
                    vitalsTextView?.setTextColor(COLOR_MUTED)
                }

                // Stages
                val stages = sleep?.stagesCount ?: 0
                if (stages > 0) {
                    stagesTextView?.text = "📊 Stages: $stages/3 captured ✓"
                    stagesTextView?.setTextColor(COLOR_SUCCESS)
                } else {
                    stagesTextView?.text = "📊 Stages: —"
                    stagesTextView?.setTextColor(COLOR_MUTED)
                }

                // Highlight button if any data is ready
                if (data.hasAnyData()) {
                    syncButton?.text = "🚀 Sync to Health Connect"
                    syncButton?.isEnabled = true
                } else {
                    syncButton?.text = "👀 Looking for data…"
                }
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

    @SuppressLint("ClickableViewAccessibility")
    private fun buildOverlayView(): View {
        val rootCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(COLOR_BG)
                cornerRadius = dpToPx(16).toFloat()
                setStroke(dpToPx(1), COLOR_BORDER)
            }
            background = bg
            setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12))
            elevation = dpToPx(10).toFloat()
        }

        // ── Header Row ───────────────────────────────────────────
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
        }

        val title = TextView(context).apply {
            text = "🌉 VivoBridge Live"
            setTextColor(COLOR_ACCENT)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val dragHint = TextView(context).apply {
            text = "⠿ Drag"
            setTextColor(COLOR_MUTED)
            textSize = 11f
            setPadding(dpToPx(6), 0, dpToPx(8), 0)
        }

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#F87171"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(6), 0, dpToPx(4), 0)
            setOnClickListener { onCancelClicked() }
        }

        header.addView(title)
        header.addView(dragHint)
        header.addView(closeBtn)
        rootCard.addView(header)

        // ── Checklist Badges ─────────────────────────────────────
        stepsTextView = makeBadge("🏃 Steps: —")
        sleepTextView = makeBadge("😴 Sleep: —")
        vitalsTextView = makeBadge("💓 Vitals: —")
        stagesTextView = makeBadge("📊 Stages: —")

        rootCard.addView(stepsTextView)
        rootCard.addView(sleepTextView)
        rootCard.addView(vitalsTextView)
        rootCard.addView(stagesTextView)

        // ── Action Button ────────────────────────────────────────
        syncButton = Button(context).apply {
            text = "🚀 Sync to Health Connect"
            setTextColor(COLOR_TEXT)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            val btnBg = GradientDrawable().apply {
                setColor(COLOR_SUCCESS)
                cornerRadius = dpToPx(10).toFloat()
            }
            background = btnBg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(38)
            ).apply { topMargin = dpToPx(10) }
            setOnClickListener { onSyncClicked() }
        }
        rootCard.addView(syncButton)

        // ── Drag Listener ────────────────────────────────────────
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        rootCard.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
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
                            windowManager.updateViewLayout(rootCard, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isDragging
                }
                else -> false
            }
        }

        return rootCard
    }

    private fun makeBadge(initialText: String): TextView = TextView(context).apply {
        text = initialText
        setTextColor(COLOR_MUTED)
        textSize = 11.5f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dpToPx(6), dpToPx(3), dpToPx(6), dpToPx(3))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpToPx(2) }
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.toFloat(),
        context.resources.displayMetrics
    ).toInt()
}
