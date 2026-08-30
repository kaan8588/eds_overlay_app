package com.eds.overlay.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.eds.overlay.R

/**
 * MorphToggleButton
 *
 * A custom interactive pill-shaped toggle button that replicates the modern
 * expand-and-slide hover effect on touch press:
 * - Rest: Clean pill surface with subtle outline and primary text.
 * - Press: An offset circular blob expands to flood the pill (green when off, red when on),
 *   while the resting label slides right/fades out and the active label + arrow slides in.
 * - Release: Executes the toggle action and returns to resting state with the updated label.
 */
class MorphToggleButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val blobView: View
    private val tvIdle: TextView
    private val layoutPressed: LinearLayout
    private val tvPressed: TextView
    private val ivArrow: ImageView

    private var isRunning: Boolean = false
    private var onToggleRequest: (() -> Unit)? = null

    private var morphAnimator: ValueAnimator? = null
    private var animProgress: Float = 0f
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private var touchDownX: Float = 0f
    private var touchDownY: Float = 0f
    private var isPressedInside: Boolean = false

    private val easeInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private val targetScale = 50f
    private val slideDistanceDp = 32f

    init {
        LayoutInflater.from(context).inflate(R.layout.view_morph_toggle, this, true)

        blobView = findViewById(R.id.view_morph_blob)
        tvIdle = findViewById(R.id.tv_morph_idle)
        layoutPressed = findViewById(R.id.layout_morph_pressed)
        tvPressed = findViewById(R.id.tv_morph_pressed)
        ivArrow = findViewById(R.id.iv_morph_arrow)

        // Clip blob scale to the rounded pill boundary
        setBackgroundResource(R.drawable.bg_morph_button_base)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true

        isClickable = true
        isFocusable = true

        updateLabels()
        applyMorphFraction(0f)
    }

    /**
     * Sets the toggle state and updates the corresponding text and color indicators.
     *
     * @param running True if the overlay service is currently running.
     */
    fun setRunning(running: Boolean) {
        if (isRunning != running) {
            isRunning = running
            updateLabels()
        }
        // Ensure animators are stopped and the button rests cleanly
        morphAnimator?.cancel()
        applyMorphFraction(0f)
    }

    /**
     * Registers the toggle action callback invoked when the user confirms a press.
     *
     * @param callback Lambda to execute on toggle.
     */
    fun setOnToggleRequest(callback: () -> Unit) {
        this.onToggleRequest = callback
    }

    /**
     * Updates label strings and accessibility content descriptions for current state.
     */
    private fun updateLabels() {
        val stringRes = if (isRunning) R.string.stop_service else R.string.start_service
        val text = context.getString(stringRes)
        tvIdle.text = text
        tvPressed.text = text
        contentDescription = text
    }

    /**
     * Updates blob tint color matching the target action (green to start, red to stop).
     */
    private fun updateBlobColor() {
        val colorRes = if (isRunning) R.color.accent_red else R.color.accent_green
        val color = ContextCompat.getColor(context, colorRes)
        blobView.backgroundTintList = ColorStateList.valueOf(color)
    }

    /**
     * Interpolates all view properties between idle (fraction = 0) and fully morphed (fraction = 1).
     *
     * @param fraction Morph progress from 0.0 (rest) to 1.0 (fully expanded).
     */
    private fun applyMorphFraction(fraction: Float) {
        animProgress = fraction
        val density = resources.displayMetrics.density
        val slidePx = slideDistanceDp * density

        if (fraction <= 0f) {
            blobView.visibility = View.INVISIBLE
            blobView.scaleX = 1f
            blobView.scaleY = 1f
            tvIdle.alpha = 1f
            tvIdle.translationX = 0f
            layoutPressed.alpha = 0f
            layoutPressed.translationX = slidePx
        } else {
            blobView.visibility = View.VISIBLE
            val currentScale = 1f + (targetScale - 1f) * fraction
            blobView.scaleX = currentScale
            blobView.scaleY = currentScale

            // Idle text slides right and fades out
            tvIdle.alpha = (1f - fraction).coerceIn(0f, 1f)
            tvIdle.translationX = slidePx * fraction

            // Pressed text & icon slide in from right and fade in
            layoutPressed.alpha = fraction.coerceIn(0f, 1f)
            layoutPressed.translationX = slidePx * (1f - fraction)
        }
    }

    /**
     * Runs a transition animation between the current fraction and the target fraction.
     *
     * @param target The target fraction (0f for idle, 1f for fully pressed).
     * @param durationMs Animation duration in milliseconds.
     * @param onEnd Optional completion callback.
     */
    private fun animateToFraction(target: Float, durationMs: Long, onEnd: (() -> Unit)? = null) {
        morphAnimator?.cancel()
        val start = animProgress
        morphAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = durationMs
            interpolator = easeInterpolator
            addUpdateListener { va ->
                applyMorphFraction(va.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                isPressedInside = true
                updateBlobColor()
                animateToFraction(1f, 260L)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val isInside = isPointInsideView(event.x, event.y, touchSlop.toFloat())
                if (isPressedInside && !isInside) {
                    // Dragged outside bounds -> animate back to rest
                    isPressedInside = false
                    animateToFraction(0f, 200L)
                } else if (!isPressedInside && isInside) {
                    // Dragged back inside -> re-expand
                    isPressedInside = true
                    updateBlobColor()
                    animateToFraction(1f, 260L)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasInside = isPointInsideView(event.x, event.y, touchSlop.toFloat())
                if (wasInside && isPressedInside) {
                    performClick()
                    onToggleRequest?.invoke()
                }
                isPressedInside = false
                animateToFraction(0f, 200L)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressedInside = false
                animateToFraction(0f, 200L)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * Determines whether a given coordinate point is within this view's boundaries plus slop tolerance.
     */
    private fun isPointInsideView(x: Float, y: Float, slop: Float): Boolean {
        return x >= -slop && x <= (width + slop) && y >= -slop && y <= (height + slop)
    }
}
