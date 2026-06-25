package com.orbital.iptv.utils

import android.content.Context

object PinManager {
    private const val PREFS           = "orbital_pin"
    private const val KEY_PIN         = "parental_pin"
    private const val KEY_LOCKED_CATS = "locked_category_ids"
    const val DEFAULT_PIN             = "0000"

    fun getPin(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN

    fun setPin(ctx: Context, pin: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PIN, pin).apply()
    }

    fun getLockedCategoryIds(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_LOCKED_CATS, emptySet()) ?: emptySet()

    fun setLockedCategoryIds(ctx: Context, ids: Set<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_LOCKED_CATS, ids).apply()
    }

    fun isCategoryLocked(ctx: Context, categoryId: String): Boolean =
        categoryId in getLockedCategoryIds(ctx)
}
