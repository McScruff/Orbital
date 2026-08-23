package com.orbital.iptv.ui.widget

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.orbital.iptv.utils.ThemeManager
import kotlin.math.max

/**
 * Bottom-right stack of auto-dismissing "goal" cards for the Goal Flash feature. A vertical
 * LinearLayout anchored bottom|end in the player's root FrameLayout; each call to [addFlash]
 * appends one themed card, slides/fades it in, then removes it after [durationMs].
 */
class GoalFlashOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    companion object {
        private const val MAX_STACKED = 4
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.END
    }

    fun addFlash(event: com.orbital.iptv.utils.GoalFlashManager.GoalEvent, durationMs: Long) {
        // Cap the stack so a flurry of goals can't fill the screen — drop the oldest first.
        while (childCount >= MAX_STACKED) removeViewAt(0)

        val p = ThemeManager.palette()
        val density = resources.displayMetrics.density
        val accentColor = if (event.disallowed) 0xFFFF4444.toInt() else p.accent

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
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.setMargins((12 * density).toInt(), (5 * density).toInt(), (12 * density).toInt(), (5 * density).toInt())
            }
            alpha = 0f
            translationX = 100f * density
        }

        card.addView(TextView(context).apply {
            text = if (event.disallowed) "🚫  GOAL DISALLOWED" else "⚽  GOAL!"
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
                layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                    it.topMargin = (2 * density).toInt()
                }
            })
        }
        card.addView(TextView(context).apply {
            text = event.detailLabel
            setTextColor(0xFFBBCCDD.toInt())
            textSize = 11f
            typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.topMargin = (4 * density).toInt()
            }
        })

        addView(card)
        card.animate().alpha(1f).translationX(0f).setDuration(220).start()

        card.postDelayed({
            card.animate().alpha(0f).translationX(100f * density).setDuration(200)
                .withEndAction { (card.parent as? LinearLayout)?.removeView(card) }
                .start()
        }, durationMs)
    }
}
