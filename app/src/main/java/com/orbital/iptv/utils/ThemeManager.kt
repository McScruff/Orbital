package com.orbital.iptv.utils

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

object ThemeManager {

    enum class AppTheme(val label: String) {
        CLASSIC("CLASSIC"),
        ORBITAL("ORBITAL")
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
        AppTheme.CLASSIC to Palette(
            bgPrimary   = 0xFF0D1B35.toInt(),
            bgHeader    = 0xFF1E3D72.toInt(),
            bgMid       = 0xFF1A3A6A.toInt(),
            bgRowAlt    = 0xFF0D1B35.toInt(),
            accent      = 0xFF00CCFF.toInt(),
            highlight   = 0xFFFFCC00.toInt(),
            focus       = 0xFF2D6090.toInt(),
            tabSelected = 0xFF132B55.toInt()
        ),
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
            cornerRadiusDp = 10f,
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
}
