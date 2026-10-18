package com.orbital.iptv.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.orbital.iptv.data.model.EpgListing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object EpgCache {

    // Full per-channel EPG pulls cover a 7-day window; refreshing at 4 days keeps a comfortable
    // buffer before the cached data runs off the end of that window while still avoiding a
    // network hit on every app open.
    private const val PROGRESS_ID = "full"
    private const val KEY_AUTO_TRY = "auto_try_ms"
    /** After a failed automatic refresh, wait this long before trying again. */
    private const val AUTO_RETRY_MS = 6 * 60 * 60 * 1000L

    /** A full refresh (manual or automatic) is running: only one at a time. */
    @Volatile var fullRefreshRunning = false
    private val autoScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private const val TTL_MS      = 4 * 24 * 60 * 60 * 1000L
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

    /**
     * Once the guide is over 4 days old ([TTL_MS]) — or was never fully downloaded — refreshes the
     * whole guide in the background (the EPG panel shows its progress). Called whenever the home
     * screen opens; a failed attempt waits [AUTO_RETRY_MS] before the next.
     */
    fun autoRefreshIfStale(context: Context) {
        val app = context.applicationContext
        if (isBatchFresh(app) || fullRefreshRunning) return
        val now = System.currentTimeMillis()
        if (now - prefs(app).getLong(KEY_AUTO_TRY, 0L) < AUTO_RETRY_MS) return
        val creds = com.orbital.iptv.utils.PrefsManager.getCredentials(app) ?: return
        prefs(app).edit().putLong(KEY_AUTO_TRY, now).apply()
        fullRefreshRunning = true
        autoScope.launch {
            try {
                val streams = com.orbital.iptv.ui.sports.SportsChannels.get(app)
                if (streams.isNotEmpty()) refreshAllFromXmltv(app,
                    com.orbital.iptv.data.repository.XtreamRepository(),
                    creds.serverUrl, creds.username, creds.password, streams)
            } catch (_: Exception) {
            } finally {
                fullRefreshRunning = false
            }
        }
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

    // True only for a channel that WAS cached but has aged past the TTL — distinct from "never
    // cached", so callers can tell the user specifically when an existing guide is being renewed
    // rather than toasting on every ordinary first-time load.
    fun isStale(context: Context, streamId: Int): Boolean {
        val f = channelFile(context, streamId)
        return f.exists() && !isValid(context, streamId)
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

    // Never downgrade: only write if the new dataset is larger than what's already cached. This
    // protects the opportunistic/background paths (a short_epg fallback shouldn't clobber a
    // previously-successful full pull), but [force] bypasses it for an explicit "get me current
    // data now" refresh — a big dataset isn't necessarily a *fresh* one, and without this a stale
    // (but numerically larger) old 7-day window could block a genuinely current one from ever
    // landing, leaving the guide rendering real entries that are simply all in the past.
    suspend fun put(context: Context, streamId: Int, listings: List<EpgListing>, force: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            val file = channelFile(context, streamId)
            if (!force && file.exists()) {
                val existing = try {
                    val type = object : TypeToken<List<EpgListing>>() {}.type
                    Gson().fromJson<List<EpgListing>>(file.readText(), type)
                } catch (_: Exception) { null }
                if ((existing?.size ?: 0) > listings.size) return@withContext
            }
            file.writeText(Gson().toJson(listings))
        } catch (_: Exception) {}
    }

    /**
     * Full guide refresh from the provider's bulk XMLTV export: each channel is saved as it's
     * parsed, so memory use stays small however big the guide is. Returns how many of [streams]
     * got guide data, or a failure if the provider doesn't offer xmltv.php (callers then fall back
     * to per-channel loading).
     */
    suspend fun refreshAllFromXmltv(
        context: Context, repository: com.orbital.iptv.data.repository.XtreamRepository,
        serverUrl: String, username: String, password: String,
        streams: List<com.orbital.iptv.data.model.LiveStream>,
        onProgress: ((saved: Int) -> Unit)? = null
    ): Result<Int> {
        val byEpgId = HashMap<String, MutableList<Int>>()
        for (s in streams) {
            val id = com.orbital.iptv.data.repository.XtreamRepository.normEpgId(s.epgChannelId) ?: continue
            byEpgId.getOrPut(id) { mutableListOf() }.add(s.streamId)
        }
        val app = context.applicationContext
        var saved = 0
        var channels = 0
        EpgProgress.set(PROGRESS_ID, "Full guide download", 0, byEpgId.size)
        suspend fun attempt() = repository.streamFullEpgXmltv(serverUrl, username, password, byEpgId.keys) { epgId, listings, append ->
            val ids = byEpgId[epgId] ?: return@streamFullEpgXmltv
            writeNow(app, ids, listings, append)
            if (!append) {
                saved += ids.size
                channels++
                if (channels % 25 == 0) EpgProgress.set(PROGRESS_ID, "Full guide download", channels, byEpgId.size)
                if (saved / 1000 != (saved - ids.size) / 1000) onProgress?.invoke(saved)
            }
        }
        var result = attempt()
        // A stalled or dropped download (seen once as a read timeout) gets one more go; an HTTP
        // error means the provider has no bulk guide, so retrying wouldn't help.
        val err = result.exceptionOrNull()
        if (err is java.io.IOException && err !is retrofit2.HttpException) { saved = 0; channels = 0; result = attempt() }
        return result.mapCatching { found ->
            if (found == 0) throw IllegalStateException("No guide data matched these channels")
            markBatchRefreshed(app)
            saved
        }.also { EpgProgress.finish(PROGRESS_ID, if (it.isSuccess) "Full guide download" else "Full guide download failed") }
    }

    /**
     * Blocking write for the full-guide refresh, which runs on the IO thread and saves each
     * channel as soon as it's parsed. Overwrites the channel's cache (that refresh is an explicit
     * "get current data now"); [append] adds to what's there instead, for a channel the provider
     * lists in more than one place.
     */
    fun writeNow(context: Context, streamIds: Collection<Int>, listings: List<EpgListing>, append: Boolean) {
        val type = object : TypeToken<List<EpgListing>>() {}.type
        val gson = Gson()
        var json: String? = null
        for (id in streamIds) {
            try {
                val file = channelFile(context, id)
                val out = if (append && file.exists()) {
                    val existing = try { gson.fromJson<List<EpgListing>>(file.readText(), type) } catch (_: Exception) { null }
                    gson.toJson(existing.orEmpty() + listings)
                } else json ?: gson.toJson(listings).also { json = it }
                file.writeText(out)
            } catch (_: Exception) {}
        }
    }

    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        dir(context).listFiles()?.forEach { it.delete() }
        // Reset batch timestamp so the next load triggers fresh API calls
        prefs(context).edit().putLong(KEY_BATCH, 0L).apply()
    }

    fun getCachedCount(context: Context): Int =
        dir(context).listFiles()?.count { it.extension == "json" } ?: 0
}
