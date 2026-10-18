package com.orbital.iptv.utils

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * What the guide (EPG) is updating right now, shown as a small panel in the top-left corner of
 * whichever screen is open: one line and progress bar per part (the full guide download, a TV
 * Guide category, Live TV's "on now"...). Once every part is done it says so for 3 seconds, then
 * hides. Settings → "EPG update indicator" turns the panel off (the tracking itself is free).
 *
 * Parts report in two ways: [set] for work with a known size (channels saved of channels
 * wanted), or [add]/[tick] for work that grows as it goes (each channel fetch adds one, each
 * finish ticks one off). A part is done when [finish]ed, or when every added item has ticked.
 */
object EpgProgress {

    private class Part(var label: String, var done: Int = 0, var total: Int = 0, var finished: Boolean = false)

    private val parts = LinkedHashMap<String, Part>()
    private val main = Handler(Looper.getMainLooper())
    private const val HOLD_MS = 3_000L

    private var activity: Activity? = null
    private var panel: LinearLayout? = null
    private val hideRunnable = Runnable { synchronized(parts) { if (parts.values.all { it.finished }) parts.clear() }; render() }

    // ── Reporting (any thread) ────────────────────────────────────────────────

    fun set(id: String, label: String, done: Int, total: Int) = change {
        val p = parts.getOrPut(id) { Part(label) }
        p.label = label; p.done = done; p.total = total; p.finished = false
    }

    fun add(id: String, label: String, count: Int = 1) = change {
        val p = parts[id]?.takeIf { !it.finished } ?: Part(label).also { parts[id] = it }
        p.label = label; p.total += count
    }

    fun tick(id: String, count: Int = 1) = change {
        val p = parts[id] ?: return@change
        p.done = minOf(p.total, p.done + count)
        if (p.total > 0 && p.done >= p.total) p.finished = true
    }

    fun finish(id: String, label: String? = null) = change {
        val p = parts[id] ?: return@change
        label?.let { p.label = it }
        p.done = p.total
        p.finished = true
    }

    private inline fun change(block: () -> Unit) {
        synchronized(parts) { block() }
        main.post { render() }
    }

    // ── Panel (main thread) ───────────────────────────────────────────────────

    /** Called from the app's activity callbacks: the panel follows whichever screen is in front. */
    fun attach(a: Activity) { activity = a; panel = null; render() }

    fun detach(a: Activity) {
        if (activity !== a) return
        (panel?.parent as? ViewGroup)?.removeView(panel)
        panel = null
        activity = null
    }

    private fun render() {
        val a = activity ?: return
        val snapshot = synchronized(parts) { parts.values.map { Part(it.label, it.done, it.total, it.finished) } }
        if (snapshot.isEmpty() || !PrefsManager.isEpgIndicatorEnabled(a)) {
            panel?.visibility = View.GONE
            return
        }
        val box = panel ?: build(a).also { panel = it }
        box.visibility = View.VISIBLE
        val allDone = snapshot.all { it.finished }
        val d = a.resources.displayMetrics.density
        box.removeAllViews()
        box.addView(TextView(a).apply {
            text = if (allDone) "✓  EPG updated" else "⟳  EPG updating"
            setTextColor(if (allDone) 0xFF22D3EE.toInt() else 0xFFFFFFFF.toInt())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        })
        snapshot.forEach { p ->
            box.addView(TextView(a).apply {
                text = if (p.total > 0) "${p.label}  ${p.done} / ${p.total}" else p.label
                setTextColor(0xFFB8B5D6.toInt())
                textSize = 11f
                setPadding(0, (6 * d).toInt(), 0, (2 * d).toInt())
            })
            box.addView(ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = p.total <= 0 && !p.finished
                max = 1000
                progress = if (p.finished) 1000 else if (p.total > 0) (p.done * 1000L / p.total).toInt() else 0
                progressTintList = android.content.res.ColorStateList.valueOf(if (p.finished) 0xFF22D3EE.toInt() else 0xFF7C6CFF.toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (6 * d).toInt())
            })
        }
        main.removeCallbacks(hideRunnable)
        if (allDone) main.postDelayed(hideRunnable, HOLD_MS)
    }

    private fun build(a: Activity): LinearLayout {
        val d = a.resources.displayMetrics.density
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(0xE6171531.toInt())
                setStroke((1 * d).toInt(), 0x667C6CFF)
            }
            elevation = 12 * d
            isFocusable = false
            isClickable = false
        }
        // Top left, just under the top bar so it doesn't cover the menu.
        (a.window.decorView as ViewGroup).addView(box, FrameLayout.LayoutParams((240 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = (16 * d).toInt()
            topMargin = (76 * d).toInt()
        })
        return box
    }
}
