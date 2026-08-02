package com.orbital.iptv.utils

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import kotlin.math.max

object ThemeManager {

    enum class AppTheme(val label: String) {
        ORBITAL("ORBITAL"),
        MONOCHROME("BLACK & WHITE")
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
        val cardElevation: Float = 0f
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
        fun shape(color: Int, strokeColor: Int? = null) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = 4f * density
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
     * Rounded "bubble" pill background for the Home screen's top nav tabs (TV GUIDE / BOX
     * OFFICE / RADIO / INTERACTIVE / SETTINGS). [selected] is the persistently-active tab
     * (accent-filled, black text expected from the caller); everything else is a neutral pill
     * that only lights up on focus.
     */
    fun navTabDrawable(density: Float, selected: Boolean): StateListDrawable {
        val p = palette()
        fun shape(color: Int, strokeColor: Int? = null) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = 18f * density
            strokeColor?.let { setStroke(max(1, (2 * density).toInt()), it) }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(p.focus, p.accent))
            addState(intArrayOf(android.R.attr.state_pressed), shape(p.focus))
            addState(intArrayOf(), shape(if (selected) p.highlight else p.bgMid))
        }
    }
}
