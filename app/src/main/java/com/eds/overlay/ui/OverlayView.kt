package com.eds.overlay.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.eds.overlay.R
import com.eds.overlay.algorithm.Threat
import kotlin.math.roundToInt

/**
 * View controller for the floating overlay widget.
 *
 * Handles:
 *  - Data binding (speed, distance, limit, alert level)
 *  - Color transitions (green → yellow → red)
 *  - Blink animation for danger state
 *  - Drag-to-move via touch interception
 */
@SuppressLint("ClickableViewAccessibility")
class OverlayView(
    val rootView: View,
    private val layoutParams: WindowManager.LayoutParams,
    private val windowManager: WindowManager
) {
    private val container: LinearLayout = rootView.findViewById(R.id.overlay_container)
    private val tvSpeed: TextView = rootView.findViewById(R.id.tv_current_speed)
    private val tvLimit: TextView = rootView.findViewById(R.id.tv_speed_limit)
    private val tvDistance: TextView = rootView.findViewById(R.id.tv_distance)
    private val tvStatus: TextView = rootView.findViewById(R.id.tv_status)
    private val ivIcon: ImageView = rootView.findViewById(R.id.iv_alert_icon)

    private var blinkAnimator: ObjectAnimator? = null
    private var currentLevel: Threat.Level? = null

    init {
        setupDragBehavior()
        setLevel(Threat.Level.SAFE)
    }

    // ── Public API ──────────────────────────────────────────────────

    fun update(speedKmh: Double, nearest: Threat?) {
        val context = rootView.context
        tvSpeed.text = "${speedKmh.roundToInt()}"

        if (nearest != null) {
            tvLimit.text = if (nearest.point.speedLimit > 0)
                "${nearest.point.speedLimit}" else "--"
            tvDistance.text = formatDistance(nearest.distanceM)
            tvStatus.text = when (nearest.level) {
                Threat.Level.DANGER -> context.getString(R.string.overlay_status_danger)
                Threat.Level.WARNING -> context.getString(R.string.overlay_status_warning)
                Threat.Level.SAFE -> context.getString(R.string.overlay_status_safe)
            }
            setLevel(nearest.level)
        } else {
            tvLimit.text = "--"
            tvDistance.text = "--"
            tvStatus.text = context.getString(R.string.overlay_status_idle)
            setLevel(Threat.Level.SAFE)
        }
    }

    fun show() {
        rootView.visibility = View.VISIBLE
    }

    fun hide() {
        rootView.visibility = View.GONE
        stopBlink()
    }

    // ── Level Styling ───────────────────────────────────────────────

    private fun setLevel(level: Threat.Level) {
        if (level == currentLevel) return
        currentLevel = level

        val bg = when (level) {
            Threat.Level.SAFE -> R.drawable.bg_overlay_safe
            Threat.Level.WARNING -> R.drawable.bg_overlay_warning
            Threat.Level.DANGER -> R.drawable.bg_overlay_danger
        }
        container.setBackgroundResource(bg)

        val icon = when (level) {
            Threat.Level.SAFE -> R.drawable.ic_muavin_mono
            Threat.Level.WARNING -> R.drawable.ic_warning
            Threat.Level.DANGER -> R.drawable.ic_danger
        }
        ivIcon.setImageResource(icon)

        if (level == Threat.Level.DANGER) startBlink() else stopBlink()
    }

    private fun startBlink() {
        stopBlink()
        blinkAnimator = ObjectAnimator.ofFloat(container, "alpha", 1f, 0.3f).apply {
            duration = 400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopBlink() {
        blinkAnimator?.cancel()
        blinkAnimator = null
        container.alpha = 1f
    }

    // ── Drag-to-Move ────────────────────────────────────────────────

    private fun setupDragBehavior() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        rootView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val metrics = rootView.context.resources.displayMetrics
                    val maxX = maxOf(0, metrics.widthPixels - rootView.width)
                    val maxY = maxOf(0, metrics.heightPixels - rootView.height)
                    layoutParams.x = (initialX - (event.rawX - initialTouchX).toInt()).coerceIn(0, maxX)
                    layoutParams.y = (initialY + (event.rawY - initialTouchY).toInt()).coerceIn(0, maxY)
                    windowManager.updateViewLayout(rootView, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun formatDistance(meters: Double): String = when {
        meters < 1000 -> "${meters.roundToInt()}m"
        else -> "${"%.1f".format(meters / 1000)}km"
    }
}
