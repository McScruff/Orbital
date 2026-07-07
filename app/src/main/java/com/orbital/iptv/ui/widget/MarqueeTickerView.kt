package com.orbital.iptv.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Single-line, horizontally scrolling news ticker.
 *
 * It is deliberately *not* a wide TextView translated across the screen. That
 * approach makes the view as wide as its (potentially enormous) text, and once
 * the view width exceeds the GPU's maximum texture size everything past the
 * limit renders blank — which is why the concatenated multi-sport ticker only
 * ever showed the first sport before going blank.
 *
 * Instead this view stays the width of its container and draws the long string
 * at a scrolling offset in [onDraw]. Skia culls the off-screen glyphs, so the
 * text can be arbitrarily long and every sport scrolls fully into view.
 */
class MarqueeTickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFCC00.toInt()
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics
        )
    }

    private val padPx = 12f * resources.displayMetrics.density
    private val speedPxPerSec = 60f * resources.displayMetrics.density

    private var textWidth = 0f
    private var offset = 0f
    private var lastFrameNs = 0L
    private var running = false

    /** The full ticker string. Setting it restarts the scroll from the right edge. */
    var text: String = ""
        set(value) {
            if (field == value) return
            field = value
            textWidth = paint.measureText(value)
            offset = 0f
            invalidate()
        }

    var textColor: Int = 0xFFFFCC00.toInt()
        set(value) {
            field = value
            paint.color = value
            invalidate()
        }

    fun start() {
        if (running) return
        running = true
        lastFrameNs = 0L
        postOnAnimation(frame)
    }

    fun stop() {
        running = false
        removeCallbacks(frame)
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            if (lastFrameNs != 0L && width > 0) {
                val dt = (now - lastFrameNs) / 1_000_000_000f
                offset += speedPxPerSec * dt
                // Restart once the whole string has scrolled off the left edge.
                if (offset > width + textWidth) offset = 0f
            }
            lastFrameNs = now
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty() || width == 0) return
        val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, width - offset + padPx, baseline, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }
}
