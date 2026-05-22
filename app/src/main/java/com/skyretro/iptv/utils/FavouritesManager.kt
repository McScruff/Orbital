package com.skyretro.iptv.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.skyretro.iptv.data.model.FavouriteItem

object FavouritesManager {
    private const val PREFS = "sky_favourites"
    private const val KEY   = "items"
    private val gson = Gson()

    fun getAll(ctx: Context): List<FavouriteItem> {
        val json = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<FavouriteItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    fun addOrUpdate(ctx: Context, item: FavouriteItem) {
        val list = getAll(ctx).toMutableList()
        val idx  = list.indexOfFirst { it.id == item.id }
        if (idx >= 0) list[idx] = item else list.add(0, item)
        save(ctx, list)
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, getAll(ctx).filter { it.id != id })
    }

    fun removeBySeriesId(ctx: Context, seriesId: Int) {
        save(ctx, getAll(ctx).filter { it.seriesId != seriesId })
    }

    fun updateResume(ctx: Context, id: String, posMs: Long, durMs: Long) {
        val list = getAll(ctx).toMutableList()
        val idx  = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(resumePositionMs = posMs, durationMs = durMs)
            save(ctx, list)
        }
    }

    fun contains(ctx: Context, id: String) = getAll(ctx).any { it.id == id }

    fun getById(ctx: Context, id: String) = getAll(ctx).firstOrNull { it.id == id }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun save(ctx: Context, list: List<FavouriteItem>) {
        prefs(ctx).edit().putString(KEY, gson.toJson(list)).apply()
    }

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        val m = s / 60
        val h = m / 60
        return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s % 60)
               else       "%d:%02d".format(m, s % 60)
    }
}
