package com.skyretro.iptv.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.skyretro.iptv.data.model.EpgListing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object EpgCache {

    private const val TTL_MS = 24 * 60 * 60 * 1000L

    private fun dir(context: Context) = File(context.filesDir, "epg_cache").also { it.mkdirs() }
    private fun channelFile(context: Context, streamId: Int) = File(dir(context), "$streamId.json")

    fun isValid(context: Context, streamId: Int): Boolean {
        val f = channelFile(context, streamId)
        return f.exists() && System.currentTimeMillis() - f.lastModified() < TTL_MS
    }

    suspend fun get(context: Context, streamId: Int): List<EpgListing>? = withContext(Dispatchers.IO) {
        if (!isValid(context, streamId)) return@withContext null
        try {
            val type = object : TypeToken<List<EpgListing>>() {}.type
            Gson().fromJson<List<EpgListing>>(channelFile(context, streamId).readText(), type)
        } catch (_: Exception) { null }
    }

    suspend fun put(context: Context, streamId: Int, listings: List<EpgListing>) = withContext(Dispatchers.IO) {
        try { channelFile(context, streamId).writeText(Gson().toJson(listings)) } catch (_: Exception) {}
    }

    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    fun getLastRefreshTime(context: Context): Long? =
        dir(context).listFiles()?.maxOfOrNull { it.lastModified() }

    fun getCachedCount(context: Context): Int =
        dir(context).listFiles()?.count { it.extension == "json" } ?: 0
}
