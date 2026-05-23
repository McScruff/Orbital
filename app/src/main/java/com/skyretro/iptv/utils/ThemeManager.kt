package com.skyretro.iptv.utils

import android.content.Context

object ThemeManager {

    enum class AppTheme(val label: String) {
        CLASSIC("CLASSIC"),
        BLACK("BLACK"),
        ORANGE("ORANGE"),
        GOLD("GOLD"),
        CRIMSON("CRIMSON"),
        FOREST("FOREST")
    }

    data class Palette(
        val bgPrimary: Int,
        val bgHeader: Int,
        val bgMid: Int,
        val bgRowAlt: Int,
        val accent: Int,
        val highlight: Int,
        val focus: Int,
        val tabSelected: Int
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
        AppTheme.BLACK to Palette(
            bgPrimary   = 0xFF080808.toInt(),
            bgHeader    = 0xFF1A1A1A.toInt(),
            bgMid       = 0xFF141414.toInt(),
            bgRowAlt    = 0xFF0A0A0A.toInt(),
            accent      = 0xFF00FF88.toInt(),
            highlight   = 0xFFFF6600.toInt(),
            focus       = 0xFF303030.toInt(),
            tabSelected = 0xFF222222.toInt()
        ),
        AppTheme.ORANGE to Palette(
            bgPrimary   = 0xFF160800.toInt(),
            bgHeader    = 0xFF7A3000.toInt(),
            bgMid       = 0xFF2A1200.toInt(),
            bgRowAlt    = 0xFF160800.toInt(),
            accent      = 0xFFFF8800.toInt(),
            highlight   = 0xFFFFDD00.toInt(),
            focus       = 0xFF9B4A00.toInt(),
            tabSelected = 0xFF4A1C00.toInt()
        ),
        AppTheme.GOLD to Palette(
            bgPrimary   = 0xFF141000.toInt(),
            bgHeader    = 0xFF3D3000.toInt(),
            bgMid       = 0xFF2A2200.toInt(),
            bgRowAlt    = 0xFF141000.toInt(),
            accent      = 0xFFFFD700.toInt(),
            highlight   = 0xFFFFFFFF.toInt(),
            focus       = 0xFF5A4800.toInt(),
            tabSelected = 0xFF2A2000.toInt()
        ),
        AppTheme.CRIMSON to Palette(
            bgPrimary   = 0xFF130008.toInt(),
            bgHeader    = 0xFF5C001A.toInt(),
            bgMid       = 0xFF220010.toInt(),
            bgRowAlt    = 0xFF130008.toInt(),
            accent      = 0xFFFF2244.toInt(),
            highlight   = 0xFFFFCC00.toInt(),
            focus       = 0xFF8B0030.toInt(),
            tabSelected = 0xFF3A0012.toInt()
        ),
        AppTheme.FOREST to Palette(
            bgPrimary   = 0xFF001209.toInt(),
            bgHeader    = 0xFF0A3818.toInt(),
            bgMid       = 0xFF0F2A14.toInt(),
            bgRowAlt    = 0xFF001209.toInt(),
            accent      = 0xFF00CC66.toInt(),
            highlight   = 0xFFFFCC00.toInt(),
            focus       = 0xFF1A6030.toInt(),
            tabSelected = 0xFF082810.toInt()
        )
    )

    var current: AppTheme = AppTheme.CLASSIC
        private set

    fun palette(): Palette = palettes.getValue(current)

    fun load(context: Context) {
        val saved = PrefsManager.getTheme(context)
        current = try { AppTheme.valueOf(saved) } catch (_: Exception) { AppTheme.CLASSIC }
    }

    fun set(context: Context, theme: AppTheme) {
        current = theme
        PrefsManager.setTheme(context, theme.name)
    }

    val allThemes: List<AppTheme> get() = AppTheme.entries
}
