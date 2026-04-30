package com.eds.overlay.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws animated, blurred green glow orbs that float and pulse
 * across the screen — inspired by Spotify's ambient background.
 *
 * Performance strategy:
 *   • Each orb is pre-rendered to a small 128×128 bitmap once.
 *   • Animation runs at ~20 fps (postDelayed) instead of 60 fps.
 *   • Hardware layer avoids cascading invalidation to parent views.
 *   • Zero allocations in the draw loop.
 */
class GlowParticlesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Particle data ──────────────────────────────────────────────
    private class Orb(
        var x: Float,
        var y: Float,
        val baseRadius: Float,
        val driftSpeed: Float,
        var driftAngle: Float,
        val pulseSpeed: Float,
        var pulsePhase: Float,
        val alpha: Int,
        var bitmap: Bitmap? = null
    )

    private val orbs = mutableListOf<Orb>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var tick = 0f
    private companion object {
        const val TICK_WRAP = 100_000f
        const val ORB_COUNT = 5          // reduced from 10
        const val BITMAP_SIZE = 128
        const val FRAME_INTERVAL_MS = 50L // ~20 fps — silky enough for ambient glow
    }

    private var coreColor = Color.parseColor("#4CAF50")

    // ── Timer-based animation (no Choreographer overhead) ──────────
    private var isAnimating = false
    private val animRunnable = object : Runnable {
        override fun run() {
            if (!isAnimating) return
            tick = (tick + 1f) % TICK_WRAP
            updateOrbs()
            invalidate()
            postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    // ── Public API ─────────────────────────────────────────────────
    fun setDarkMode(dark: Boolean) {
        coreColor = if (dark) Color.parseColor("#4CAF50") else Color.parseColor("#388E3C")
        for (orb in orbs) {
            val old = orb.bitmap
            orb.bitmap = buildOrbBitmap()
            old?.recycle()
        }
        invalidate()
    }

    private fun buildOrbBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(BITMAP_SIZE, BITMAP_SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val center = BITMAP_SIZE / 2f
        val radius = BITMAP_SIZE / 2f
        val gradient = RadialGradient(
            center, center, radius,
            Color.argb(255, Color.red(coreColor), Color.green(coreColor), Color.blue(coreColor)),
            Color.argb(0, Color.red(coreColor), Color.green(coreColor), Color.blue(coreColor)),
            Shader.TileMode.CLAMP
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = gradient
        c.drawCircle(center, center, radius, p)
        return bmp
    }

    // ── Lifecycle ──────────────────────────────────────────────────
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        initOrbs()
        startAnimation()
    }

    private fun initOrbs() {
        for (orb in orbs) orb.bitmap?.recycle()
        orbs.clear()
        val density = resources.displayMetrics.density

        for (i in 0 until ORB_COUNT) {
            orbs.add(
                Orb(
                    x = Random.nextFloat(),
                    y = Random.nextFloat(),
                    baseRadius = (60f + Random.nextFloat() * 100f) * density,
                    driftSpeed = (0.15f + Random.nextFloat() * 0.35f) * density,
                    driftAngle = Random.nextFloat() * (2f * Math.PI.toFloat()),
                    pulseSpeed = 0.008f + Random.nextFloat() * 0.012f,
                    pulsePhase = Random.nextFloat() * (2f * Math.PI.toFloat()),
                    alpha = 25 + Random.nextInt(50),
                    bitmap = buildOrbBitmap()
                )
            )
        }
    }

    private fun startAnimation() {
        if (isAnimating) return
        isAnimating = true
        // Hardware layer: view renders to an offscreen GPU texture.
        // Only this texture is re-drawn each frame — parent views are NOT invalidated.
        setLayerType(LAYER_TYPE_HARDWARE, null)
        post(animRunnable)
    }

    private fun stopAnimation() {
        isAnimating = false
        removeCallbacks(animRunnable)
        setLayerType(LAYER_TYPE_NONE, null)
    }

    private fun updateOrbs() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        for (orb in orbs) {
            orb.x += cos(orb.driftAngle) * orb.driftSpeed / w
            orb.y += sin(orb.driftAngle) * orb.driftSpeed / h
            orb.driftAngle += (Random.nextFloat() - 0.5f) * 0.06f

            val margin = 0.25f
            if (orb.x < -margin) orb.x += 1f + 2 * margin
            if (orb.x > 1f + margin) orb.x -= 1f + 2 * margin
            if (orb.y < -margin) orb.y += 1f + 2 * margin
            if (orb.y > 1f + margin) orb.y -= 1f + 2 * margin
        }
    }

    // ── Drawing (zero allocation) ─────────────────────────────────
    private val srcRect = Rect(0, 0, BITMAP_SIZE, BITMAP_SIZE)
    private val dstRect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        for (orb in orbs) {
            val bmp = orb.bitmap ?: continue
            val pulse = sin((tick * orb.pulseSpeed + orb.pulsePhase).toDouble()).toFloat()
            val radius = orb.baseRadius * (0.7f + 0.3f * pulse)
            val alpha = (orb.alpha * (0.6f + 0.4f * ((pulse + 1f) / 2f))).toInt().coerceIn(0, 255)

            val cx = orb.x * w
            val cy = orb.y * h
            dstRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            paint.alpha = alpha
            canvas.drawBitmap(bmp, srcRect, dstRect, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (orbs.isNotEmpty()) startAnimation()
    }
}
