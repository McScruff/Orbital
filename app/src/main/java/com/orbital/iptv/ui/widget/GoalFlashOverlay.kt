package com.orbital.iptv.ui.widget

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.orbital.iptv.utils.ThemeManager
import kotlin.math.max

/**
 * Bottom-right "goal" notification for the Goal Flash feature, shown as a custom-view system
 * Toast rather than an in-app overlay child view. A Toast renders in its own system-level
 * window, composited by Android entirely outside this Activity's window — unlike the previous
 * in-app overlay, which sat alongside the video SurfaceView in the same window and suffered a
 * GPU-compositing race on some hardware (part of the card's pixels silently not painted, worse
 * on weaker devices). Routing through the system Toast path sidesteps that class of bug, at the
 * cost of losing simultaneous stacking: a rapid burst of events queues and shows one at a time
 * instead of appearing side by side.
 */
class GoalFlashOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val handler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var currentToast: Toast? = null

    fun addFlash(event: com.orbital.iptv.utils.GoalFlashManager.GoalEvent, durationMs: Long) {
        // Stop any still-running repeat loop from a prior toast so it doesn't keep re-showing
        // itself after this new event should have taken over.
        repeatRunnable?.let { handler.removeCallbacks(it) }
        currentToast?.cancel()

        val toast = buildToast(event)
        currentToast = toast

        // A single LENGTH_SHORT toast only stays up ~2s and there's no API for a longer custom
        // duration. Calling .show() again on the SAME Toast instance while it's still displaying
        // is a no-op on-device (confirmed: it does not extend or restart the window), so instead
        // a fresh Toast+view is built and shown each cycle — a genuinely new Toast queues/renews
        // properly, timed to land just before the previous one's ~2000ms window closes.
        val deadline = System.currentTimeMillis() + durationMs
        val runnable = object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() >= deadline) return
                val next = buildToast(event)
                currentToast = next
                next.show()
                handler.postDelayed(this, 1800)
            }
        }
        repeatRunnable = runnable
        toast.show()
        handler.postDelayed(runnable, 1800)
    }

    private fun buildToast(event: com.orbital.iptv.utils.GoalFlashManager.GoalEvent): Toast {
        val p = ThemeManager.palette()
        val density = resources.displayMetrics.density
        // Cards size themselves to their text with no width cap, so an uncapped long matchup
        // name (e.g. "Newcastle Utd vs Liverpool") could otherwise grow past a sensible width —
        // this caps it so the ellipsize below actually has something to do.
        val maxCardWidthPx = (resources.displayMetrics.widthPixels * 0.4f).toInt()
        val accentColor = when (event.type) {
            com.orbital.iptv.utils.GoalFlashManager.FlashType.DISALLOWED -> 0xFFFF4444.toInt()
            com.orbital.iptv.utils.GoalFlashManager.FlashType.GOAL -> p.accent
            com.orbital.iptv.utils.GoalFlashManager.FlashType.KICK_OFF,
            com.orbital.iptv.utils.GoalFlashManager.FlashType.FULL_TIME -> p.highlight
        }

        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(p.bgMid)
                setStroke(max(1, (2 * density).toInt()), accentColor)
            }
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
            elevation = 10f * density
        }

        card.addView(TextView(context).apply {
            text = when (event.type) {
                com.orbital.iptv.utils.GoalFlashManager.FlashType.DISALLOWED -> "🚫  GOAL DISALLOWED"
                com.orbital.iptv.utils.GoalFlashManager.FlashType.GOAL -> "⚽  GOAL!"
                com.orbital.iptv.utils.GoalFlashManager.FlashType.KICK_OFF -> "▶️  KICK-OFF"
                com.orbital.iptv.utils.GoalFlashManager.FlashType.FULL_TIME -> "🏁  FULL-TIME"
            }
            setTextColor(accentColor)
            textSize = 11f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            letterSpacing = 0.06f
        })
        card.addView(TextView(context).apply {
            text = event.gameLabel
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = maxCardWidthPx
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.topMargin = (2 * density).toInt()
            }
        })
        if (event.scoreLabel.isNotBlank()) {
            card.addView(TextView(context).apply {
                text = event.scoreLabel
                setTextColor(0xFFFFCC00.toInt())
                textSize = 16f
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = maxCardWidthPx
                layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                    it.topMargin = (2 * density).toInt()
                }
            })
        }
        if (event.detailLabel.isNotBlank()) {
            card.addView(TextView(context).apply {
                text = event.detailLabel
                setTextColor(0xFFBBCCDD.toInt())
                textSize = 11f
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = maxCardWidthPx
                layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                    it.topMargin = (4 * density).toInt()
                }
            })
        }

        @Suppress("DEPRECATION")
        return Toast(context.applicationContext).apply {
            duration = Toast.LENGTH_SHORT
            view = card
            setGravity(Gravity.BOTTOM or Gravity.END, (12 * density).toInt(), (70 * density).toInt())
        }
    }
}
