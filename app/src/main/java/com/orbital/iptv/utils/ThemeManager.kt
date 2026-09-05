package com.orbital.iptv.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView
import kotlin.math.max

object ThemeManager {

    enum class AppTheme(val label: String) {
        ORBITAL("ORBITAL"),
        MONOCHROME("BLACK & WHITE"),
        AURORA("AURORA")
    }

    data class Palette(
        val bgPrimary: Int,
        val bgHeader: Int,
        val bgMid: Int,
        val bgRowAlt: Int,
        val accent: Int,
        val highlight: Int,
        val focus: Int,
        val tabSelected: Int,
        // Card/row styling
        val rowEven: Int = bgMid,
        val rowOdd: Int = bgRowAlt,
        val rowSelected: Int = highlight,
        val cornerRadiusDp: Float = 0f,
        val itemMarginDp: Float = 0f,
        val cardElevation: Float = 0f,
        // Secondary accent — defaults to [accent] so existing themes are unaffected. Used to draw
        // a two-colour diagonal gradient (e.g. selected nav tabs) for themes designed around one.
        val accent2: Int = accent,
        // Text colour to use on top of the *selected* nav tab's fill. ORBITAL/MONOCHROME's
        // selected fill is a light tint (readable with black text); AURORA's is a saturated
        // blue-purple gradient, which needs light text instead.
        val tabTextOnSelected: Int = 0xFF000000.toInt()
    )

    private val palettes = mapOf(
        AppTheme.ORBITAL to Palette(
            bgPrimary   = 0xFF04060D.toInt(),   // near-black deep space
            bgHeader    = 0xFF070D1A.toInt(),   // dark midnight blue
            bgMid       = 0xFF0C1630.toInt(),   // deep blue for cards
            bgRowAlt    = 0xFF080E20.toInt(),   // slightly darker alternating
            accent      = 0xFF3A9EFF.toInt(),   // electric blue orbital glow
            highlight   = 0xFF6EC2FF.toInt(),   // lighter blue for selected
            focus       = 0xFF1A5CB8.toInt(),   // mid-blue focus
            tabSelected = 0xFF0E1E3A.toInt(),   // dark blue active tab
            rowEven     = 0xFF0C1630.toInt(),
            rowOdd      = 0xFF080E20.toInt(),
            rowSelected = 0xFF1A4A8A.toInt(),
            cornerRadiusDp = 0f,
            itemMarginDp   = 3f,
            cardElevation  = 4f
        ),
        AppTheme.MONOCHROME to Palette(
            bgPrimary   = 0xFF000000.toInt(),   // true black
            bgHeader    = 0xFF000000.toInt(),   // header blends into the background
            bgMid       = 0xFF121212.toInt(),   // card/row elevation grey
            bgRowAlt    = 0xFF000000.toInt(),
            accent      = 0xFFFFFFFF.toInt(),   // white accent line/glow
            highlight   = 0xFFFFFFFF.toInt(),   // selected state inverts to white (black text)
            focus       = 0xFF2A2A2A.toInt(),   // d-pad focus ring, distinct mid-grey
            tabSelected = 0xFF1A1A1A.toInt(),
            rowEven     = 0xFF121212.toInt(),
            rowOdd      = 0xFF000000.toInt(),
            rowSelected = 0xFFFFFFFF.toInt(),
            cornerRadiusDp = 0f,
            itemMarginDp   = 3f,
            cardElevation  = 4f
        ),
        // "AURORA" — deep navy/indigo base with a pink-blue-purple neon gradient accent, rounded
        // cards and pill-shaped nav tabs. Matches the user-supplied iptv-gui.html mockup, lifted
        // a shade brighter/more saturated than the mock's originals for more on-screen "pop".
        AppTheme.AURORA to Palette(
            bgPrimary   = 0xFF10193F.toInt(),   // bg-deep, lightened
            bgHeader    = 0xFF131D46.toInt(),
            bgMid       = 0xFF1D295E.toInt(),   // bg-panel (cards), lightened
            bgRowAlt    = 0xFF172253.toInt(),   // bg-mid (alternating rows), lightened
            accent      = 0xFF4E8CFF.toInt(),   // accent-b, brighter interactive blue
            highlight   = 0xFFFF5C97.toInt(),   // accent-a, brighter pink — selected/live highlight
            focus       = 0xFF9575FF.toInt(),   // accent-c, brighter purple — d-pad focus ring
            tabSelected = 0xFF1D295E.toInt(),
            rowEven     = 0xFF1D295E.toInt(),
            rowOdd      = 0xFF172253.toInt(),
            rowSelected = 0xFF9575FF.toInt(),
            cornerRadiusDp = 12f,
            itemMarginDp   = 4f,
            cardElevation  = 6f,
            accent2        = 0xFF9575FF.toInt(),  // purple — gradient partner for accent (blue)
            tabTextOnSelected = 0xFFF2F4FF.toInt() // text-hi — readable on the blue/purple gradient
        )
    )

    var current: AppTheme = AppTheme.ORBITAL
        private set

    fun palette(): Palette = palettes.getValue(current)

    fun load(context: Context) {
        val saved = PrefsManager.getTheme(context)
        current = try { AppTheme.valueOf(saved) } catch (_: Exception) { AppTheme.ORBITAL }
    }

    fun set(context: Context, theme: AppTheme) {
        current = theme
        PrefsManager.setTheme(context, theme.name)
    }

    val allThemes: List<AppTheme> get() = AppTheme.entries

    /**
     * The AlertDialog style resource to use for the current theme. AlertDialog.Builder takes a
     * static style resource ID — it can't read this object's palette at runtime — so every
     * dialog call site across the app uses this instead of referencing R.style.Theme_Orbital_Dialog
     * directly. Add a new `Theme.Orbital.Dialog.X` style (see themes.xml) per new theme.
     */
    fun dialogStyle(): Int = when (current) {
        AppTheme.MONOCHROME -> com.orbital.iptv.R.style.Theme_Orbital_Dialog_Mono
        AppTheme.AURORA -> com.orbital.iptv.R.style.Theme_Orbital_Dialog_Aurora
        else -> com.orbital.iptv.R.style.Theme_Orbital_Dialog
    }

    fun roundedBg(color: Int, density: Float): Drawable {
        val r = palette().cornerRadiusDp
        return if (r <= 0f) ColorDrawable(color)
        else GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = r * density
        }
    }

    fun roundedBg(color: Int, radiusDp: Float, density: Float): Drawable {
        return if (radiusDp <= 0f) ColorDrawable(color)
        else GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp * density
        }
    }

    // ── Colour math ───────────────────────────────────────────────────────────

    /** Replace the alpha channel of an opaque colour, keeping its RGB. */
    fun withAlpha(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0x00FFFFFF)

    /** Scale a colour towards black — used for "past"/de-emphasised row states. */
    fun dim(color: Int, factor: Float = 0.55f): Int {
        val r = (((color shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Nudge a colour towards white by a fixed amount — used to split one base shade into two. */
    fun lighten(color: Int, amount: Int): Int {
        val r = (((color shr 16) and 0xFF) + amount).coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) + amount).coerceIn(0, 255)
        val b = ((color and 0xFF) + amount).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Themed replacement for the static bg_btn_hud/bg_btn_back drawable selectors — those
     * hardcoded a fixed blue regardless of app theme. [withAccentStroke] adds a thin accent
     * border on focus, matching the old "hud" button look; the "back" button omits it.
     * Must be called once per View (a StateListDrawable's state is shared if the same
     * instance is assigned to multiple views, causing focus visuals to desync).
     */
    fun hudButtonDrawable(density: Float, withAccentStroke: Boolean = true): StateListDrawable {
        val p = palette()
        val radius = max(4f, p.cornerRadiusDp)
        fun shape(color: Int, strokeColor: Int? = null) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius * density
            strokeColor?.let { setStroke(max(1, (1 * density).toInt()), it) }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                shape(p.focus, if (withAccentStroke) withAlpha(p.accent, 0x88) else null))
            addState(intArrayOf(android.R.attr.state_pressed), shape(p.focus))
            addState(intArrayOf(), shape(p.bgMid))
        }
    }

    /**
     * TV Mode main-menu row background — a flat fill, plus (when [focused]) a thin accent
     * bar pinned to the start edge so the focused row reads clearly even at a glance from
     * the couch, on top of the fill-color swap the caller already does on focus.
     */
    fun menuRowDrawable(density: Float, baseColor: Int, focused: Boolean): Drawable {
        if (!focused) return ColorDrawable(baseColor)
        val p = palette()
        val bar = GradientDrawable().apply { setColor(p.highlight) }
        val box = GradientDrawable().apply {
            setColor(baseColor)
            setStroke(max(1, (2 * density).toInt()), p.accent)
        }
        return LayerDrawable(arrayOf<Drawable>(box, bar)).apply {
            setLayerWidth(1, (4 * density).toInt())
            setLayerGravity(1, Gravity.START)
        }
    }

    /**
     * Generic boxed focus treatment for anything that currently only swaps background colour
     * on D-pad focus (category menus, sidebar rows, list-adapter rows, plain buttons) — a
     * colour-only cue is easy to miss at a glance from the couch, so every focusable row/button
     * in the app should draw a visible accent-coloured box around itself when focused, matching
     * the stroke treatment already used by the VOD/Series poster grids.
     */
    fun focusRowDrawable(
        density: Float,
        baseColor: Int,
        focused: Boolean,
        focusFillColor: Int = palette().focus,
        strokeWidthDp: Float = 2f
    ): Drawable {
        val p = palette()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(if (focused) focusFillColor else baseColor)
            cornerRadius = p.cornerRadiusDp * density
            if (focused) setStroke(max(1, (strokeWidthDp * density).toInt()), p.accent)
        }
    }

    /**
     * Angled "sci-fi HUD" parallelogram background for the Home screen's top nav tabs (TV
     * GUIDE / BOX OFFICE / RADIO / INTERACTIVE / SETTINGS) — replaces the old rounded-pill
     * look. [selected] is the persistently-active tab: it gets a diagonal accent gradient fill
     * and a thin accent outline even without focus; everything else is a flat neutral chip
     * that only lights up (with a soft layered glow) on D-pad focus.
     */
    fun navTabDrawable(density: Float, selected: Boolean): StateListDrawable {
        val p = palette()
        if (p.cornerRadiusDp > 0f) return roundedNavTabDrawable(density, selected, p)
        val skew = 12f * density
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                AngledTabDrawable(skew, p.focus, null, p.accent, glow = true)
            )
            addState(
                intArrayOf(android.R.attr.state_pressed),
                AngledTabDrawable(skew, p.focus, null, p.accent, glow = false)
            )
            addState(
                intArrayOf(),
                if (selected) AngledTabDrawable(skew, p.highlight, dim(p.highlight, 0.72f), p.accent, glow = false)
                else AngledTabDrawable(skew, p.bgMid, null, null, glow = false)
            )
        }
    }

    /**
     * Rounded-pill nav tab used by themes with [Palette.cornerRadiusDp] > 0 (e.g. AURORA) —
     * a flat neutral chip by default, a diagonal accent→accent2 gradient when selected (no
     * D-pad focus needed), and an accent-stroked focus ring on top of either, matching the
     * user-supplied iptv-gui.html mockup's `.tab` / `.tab.active` treatment.
     */
    private fun roundedNavTabDrawable(density: Float, selected: Boolean, p: Palette): StateListDrawable {
        val r = p.cornerRadiusDp * density
        fun chip(color1: Int, color2: Int? = null, stroke: Int? = null) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            if (color2 != null) {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(color1, color2)
            } else {
                setColor(color1)
            }
            stroke?.let { setStroke(max(1, (2f * density).toInt()), it) }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), chip(p.focus, null, p.accent))
            addState(intArrayOf(android.R.attr.state_pressed), chip(p.focus, null, p.accent))
            addState(
                intArrayOf(),
                if (selected) chip(p.accent, p.accent2) else chip(withAlpha(p.bgMid, 0xB0))
            )
        }
    }

    /**
     * Parallelogram-shaped Drawable (top edge shifted right of the bottom edge by [skewPx]) —
     * the shape behind [navTabDrawable]. [fillColor2] non-null draws a diagonal gradient
     * instead of a flat fill. The glow on focus is drawn as a few widening, fading stroke
     * passes rather than Paint.setShadowLayer(), which isn't reliably hardware-accelerated on
     * every device this app targets (notably Fire TV Stick).
     */
    private class AngledTabDrawable(
        private val skewPx: Float,
        private val fillColor: Int,
        private val fillColor2: Int?,
        private val strokeColor: Int?,
        private val glow: Boolean
    ) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val path = Path()

        override fun onBoundsChange(b: Rect) {
            super.onBoundsChange(b)
            path.reset()
            path.moveTo(b.left + skewPx, b.top.toFloat())
            path.lineTo(b.right.toFloat(), b.top.toFloat())
            path.lineTo(b.right - skewPx, b.bottom.toFloat())
            path.lineTo(b.left.toFloat(), b.bottom.toFloat())
            path.close()
            fillPaint.shader = fillColor2?.let {
                LinearGradient(
                    b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                    fillColor, it, Shader.TileMode.CLAMP
                )
            }
            fillPaint.color = fillColor
        }

        override fun draw(canvas: Canvas) {
            canvas.drawPath(path, fillPaint)
            val stroke = strokeColor ?: return
            if (glow) {
                strokePaint.shader = null
                strokePaint.color = stroke
                strokePaint.alpha = 50
                strokePaint.strokeWidth = bounds.height() * 0.10f
                canvas.drawPath(path, strokePaint)
                strokePaint.alpha = 100
                strokePaint.strokeWidth = bounds.height() * 0.06f
                canvas.drawPath(path, strokePaint)
            }
            strokePaint.color = stroke
            strokePaint.alpha = 255
            strokePaint.strokeWidth = bounds.height() * 0.035f
            canvas.drawPath(path, strokePaint)
        }

        override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { fillPaint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
