package com.orbital.iptv.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.orbital.iptv.data.model.FavType
import com.orbital.iptv.data.model.FavouriteItem

/** Auto-maintained MRU list of the last few live channels watched — separate from FavouritesManager's
 *  user-curated favourites, since this list evicts itself and shouldn't be affected by (or affect)
 *  manual favouriting/unfavouriting. */
object RecentChannelsManager {
    private const val PREFS = "sky_recent_channels"
    private const val KEY   = "items"
    private const val MAX_ITEMS = 5
    private val gson = Gson()

    fun getAll(ctx: Context): List<FavouriteItem> {
        val json = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<FavouriteItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    fun record(ctx: Context, name: String, streamId: Int, streamUrl: String, iconUrl: String?) {
        if (streamId < 0 || name.isBlank()) return
        val list = getAll(ctx).filter { it.streamId != streamId }.toMutableList()
        list.add(0, FavouriteItem(
            id        = "recent_$streamId",
            type      = FavType.LIVE,
            title     = name,
            artUrl    = iconUrl ?: "",
            streamUrl = streamUrl,
            streamId  = streamId
        ))
        save(ctx, list.take(MAX_ITEMS))
    }

    fun remove(ctx: Context, streamId: Int) {
        save(ctx, getAll(ctx).filter { it.streamId != streamId })
    }

    fun clear(ctx: Context) = save(ctx, emptyList())

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun save(ctx: Context, list: List<FavouriteItem>) {
        prefs(ctx).edit().putString(KEY, gson.toJson(list)).apply()
    }
}
