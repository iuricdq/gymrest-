package com.gymrest

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Circular rest-timer ring drawn entirely on Canvas.
 * Supports ambient mode (1-bit palette), warning flash, and smooth color transitions.
 */
class RestRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var progress = 0f       // 0..1  (1 = full, 0 = empty)
    private var ringColor = 0xFF444444.toInt()
    private var ambient   = false
    private var warning   = false
    private var warningPhase = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(8f)
        color = 0xFF1A1A1A.toInt()
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style  = Paint.Style.STROKE
        strokeWidth  = dpToPx(8f)
        strokeCap    = Paint.Cap.ROUND
    }

    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface  = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private val oval = RectF()

    // ── Public API ────────────────────────────────────────────────────────────

    fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        invalidate()
    }

    fun setColor(color: Int) {
        ringColor = color
        invalidate()
    }

    fun setAmbient(on: Boolean) {
        ambient = on
        invalidate()
    }

    fun setWarning(on: Boolean) {
        if (warning == on) return
        warning = on
        if (on) startWarningAnim() else invalidate()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width  / 2f
        val cy = height / 2f
        val r  = (minOf(width, height) / 2f) - dpToPx(12f)

        oval.set(cx - r, cy - r, cx + r, cy + r)

        if (ambient) {
            drawAmbient(canvas, cx, cy, r)
            return
        }

        // Track ring
        canvas.drawArc(oval, 0f, 360f, false, trackPaint)

        // Progress ring
        ringPaint.color = if (warning) lerpColor(ringColor, 0xFFFFAA00.toInt(), warningPhase)
                          else ringColor
        val sweep = -360f * progress
        canvas.drawArc(oval, -90f, sweep, false, ringPaint)

        // Inner dot at sweep end
        if (progress > 0.01f) {
            val angleRad = Math.toRadians((-90f + sweep).toDouble())
            val dx = (cx + r * cos(angleRad)).toFloat()
            val dy = (cy + r * sin(angleRad)).toFloat()
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ringPaint.color
                style = Paint.Style.FILL
            }
            canvas.drawCircle(dx, dy, dpToPx(4f), dotPaint)
        }
    }

    private fun drawAmbient(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(2f)
            color = Color.WHITE
        }
        canvas.drawArc(oval, -90f, -360f * progress, false, p)
    }

    // ── Warning animation ─────────────────────────────────────────────────────

    private val warningHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val warningRunnable = object : Runnable {
        override fun run() {
            if (!warning) { warningPhase = 0f; invalidate(); return }
            warningPhase = (warningPhase + 0.05f) % 1f
            invalidate()
            warningHandler.postDelayed(this, 50)
        }
    }

    private fun startWarningAnim() {
        warningHandler.removeCallbacks(warningRunnable)
        warningHandler.post(warningRunnable)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Float) =
        dp * context.resources.displayMetrics.density

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val pulse = (sin(t * Math.PI * 2).toFloat() + 1f) / 2f
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - pulse) +
                               ((b shr shift) and 0xFF) * pulse).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun cos(rad: Double) = Math.cos(rad)
    private fun sin(rad: Double) = Math.sin(rad)
}
