package com.orbital.iptv.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.orbital.iptv.data.model.EpgListing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object EpgCache {

    private const val TTL_MS      = 24 * 60 * 60 * 1000L
    private const val PREF        = "epg_cache_meta"
    private const val KEY_BATCH   = "batch_ts_ms"

    private fun dir(context: Context) = File(context.filesDir, "epg_cache").also { it.mkdirs() }
    private fun channelFile(context: Context, streamId: Int) = File(dir(context), "$streamId.json")
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ── Batch-level freshness ─────────────────────────────────────────────────
    // A single 24-h clock shared across all channels. Once marked fresh, no API
    // calls are made for the rest of the day (only cached files are used).

    fun getBatchRefreshMs(context: Context): Long = prefs(context).getLong(KEY_BATCH, 0L)

    fun isBatchFresh(context: Context): Boolean =
        System.currentTimeMillis() - getBatchRefreshMs(context) < TTL_MS

    fun markBatchRefreshed(context: Context) {
        prefs(context).edit().putLong(KEY_BATCH, System.currentTimeMillis()).apply()
    }

    // ── Per-channel validity ──────────────────────────────────────────────────

    fun isValid(context: Context, streamId: Int): Boolean {
        val f = channelFile(context, streamId)
        if (!f.exists()) return false
        // If the batch was refreshed today, all existing files are treated as fresh
        if (isBatchFresh(context)) return true
        // Batch stale — fall back to per-file TTL (covers player-cached channels)
        return System.currentTimeMillis() - f.lastModified() < TTL_MS
    }

    // minCount: treat cache as stale if it has fewer entries than this (e.g. was saved from a short-epg call)
    suspend fun get(context: Context, streamId: Int, minCount: Int = 1): List<EpgListing>? = withContext(Dispatchers.IO) {
        if (!isValid(context, streamId)) return@withContext null
        try {
            val type = object : TypeToken<List<EpgListing>>() {}.type
            val list = Gson().fromJson<List<EpgListing>>(channelFile(context, streamId).readText(), type)
            if ((list?.size ?: 0) < minCount) null else list
        } catch (_: Exception) { null }
    }

    // Never downgrade: only write if the new dataset is larger than what's already cached.
    suspend fun put(context: Context, streamId: Int, listings: List<EpgListing>) = withContext(Dispatchers.IO) {
        try {
            val file = channelFile(context, streamId)
            if (file.exists()) {
                val existing = try {
                    val type = object : TypeToken<List<EpgListing>>() {}.type
                    Gson().fromJson<List<EpgListing>>(file.readText(), type)
                } catch (_: Exception) { null }
                if ((existing?.size ?: 0) > listings.size) return@withContext
            }
            file.writeText(Gson().toJson(listings))
        } catch (_: Exception) {}
    }

    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        dir(context).listFiles()?.forEach { it.delete() }
        // Reset batch timestamp so the next load triggers fresh API calls
        prefs(context).edit().putLong(KEY_BATCH, 0L).apply()
    }

    fun getCachedCount(context: Context): Int =
        dir(context).listFiles()?.count { it.extension == "json" } ?: 0
}
