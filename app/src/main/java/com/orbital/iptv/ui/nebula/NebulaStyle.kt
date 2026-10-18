package com.orbital.iptv.ui.nebula

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import com.orbital.iptv.utils.ThemeManager

/** Small shared NEBULA drawables for code-built views. */
object NebulaStyle {

    /** Pill chip: gradient when selected, flat otherwise, with a focus ring. */
    fun chipBg(density: Float, selected: Boolean, focused: Boolean): Drawable {
        val p = ThemeManager.palette()
        return GradientDrawable().apply {
            cornerRadius = 20 * density
            if (selected) {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(p.accent, p.accent2)
            } else {
                setColor(if (focused) p.focus else p.bgMid)
            }
            if (focused) setStroke((2 * density).toInt(), if (selected) 0xFFFFFFFF.toInt() else p.accent)
        }
    }

    fun chip(tv: TextView, selected: Boolean) {
        tv.background = chipBg(tv.resources.displayMetrics.density, selected, tv.hasFocus())
        tv.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFB8B5D6.toInt())
    }

    /** Rounded gradient fill for small action buttons (e.g. the per-episode Play pill). */
    fun gradientPill(density: Float, radiusDp: Float = 10f): Drawable {
        val p = ThemeManager.palette()
        return GradientDrawable().apply {
            cornerRadius = radiusDp * density
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            colors = intArrayOf(p.accent, p.accent2)
        }
    }

    fun rowBg(position: Int): Int = if (position % 2 == 0) 0xFF171531.toInt() else 0xFF11102A.toInt()

    /** Inset rounded-card treatment for list rows whose background is set per bind. */
    fun prepCard(v: android.view.View, hMarginDp: Int = 2) {
        val d = v.resources.displayMetrics.density
        (v.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.setMargins(
            (hMarginDp * d).toInt(), (3 * d).toInt(), (hMarginDp * d).toInt(), (3 * d).toInt()
        )
        v.clipToOutline = true
    }
}
