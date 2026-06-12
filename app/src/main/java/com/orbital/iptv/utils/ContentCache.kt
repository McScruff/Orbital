package com.orbital.iptv.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.orbital.iptv.data.api.ApiClient
import com.orbital.iptv.data.model.LiveStream
import com.orbital.iptv.data.model.SeriesStream
import com.orbital.iptv.data.model.VodStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object ContentCache {

    private const val TTL_MS = 24 * 60 * 60 * 1000L

    // Separate client with a longer read timeout for large catalog downloads.
    private val downloadClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private fun dir(context: Context) = File(context.filesDir, "content_cache").also { it.mkdirs() }

    private fun moviesFile(context: Context, serverUrl: String) =
        File(dir(context), "movies_${serverUrl.hashCode()}.json")

    private fun seriesFile(context: Context, serverUrl: String) =
        File(dir(context), "series_${serverUrl.hashCode()}.json")

    private fun liveFile(context: Context, serverUrl: String) =
        File(dir(context), "live_${serverUrl.hashCode()}.json")

    fun isMoviesValid(context: Context, serverUrl: String): Boolean {
        val f = moviesFile(context, serverUrl)
        return f.exists() && System.currentTimeMillis() - f.lastModified() < TTL_MS
    }

    fun isSeriesValid(context: Context, serverUrl: String): Boolean {
        val f = seriesFile(context, serverUrl)
        return f.exists() && System.currentTimeMillis() - f.lastModified() < TTL_MS
    }

    // ── Streaming cache build ────────────────────────────────────────────────────
    // Pipes the raw HTTP response body directly to disk — never materialises the
    // full JSON as a String or as a List in memory.  Write goes to a .tmp file
    // first; only renamed to the target on success so a partial download never
    // leaves a corrupt cache behind.

    suspend fun downloadAndSaveMovies(
        context: Context, serverUrl: String, username: String, password: String
    ) = withContext(Dispatchers.IO) {
        streamToFile(
            ApiClient.buildApiUrl(serverUrl, username, password, "get_vod_streams"),
            moviesFile(context, serverUrl)
        )
    }

    suspend fun downloadAndSaveSeries(
        context: Context, serverUrl: String, username: String, password: String
    ) = withContext(Dispatchers.IO) {
        streamToFile(
            ApiClient.buildApiUrl(serverUrl, username, password, "get_series"),
            seriesFile(context, serverUrl)
        )
    }

    private fun streamToFile(url: String, target: File) {
        val temp = File(target.parent, "${target.name}.tmp")
        try {
            downloadClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return
                val body = response.body ?: return
                body.byteStream().use { input ->
                    temp.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            temp.renameTo(target)
        } catch (_: Throwable) {
            temp.delete()
        }
    }

    // ── Streaming search ─────────────────────────────────────────────────────────
    // Reads the cache file one object at a time using JsonReader so the full list
    // is never in memory simultaneously.  Only matching objects are deserialised.

    suspend fun searchMovies(context: Context, serverUrl: String, query: String): List<VodStream> =
        withContext(Dispatchers.IO) {
            streamSearch(moviesFile(context, serverUrl), query, VodStream::class.java)
        }

    suspend fun searchSeries(context: Context, serverUrl: String, query: String): List<SeriesStream> =
        withContext(Dispatchers.IO) {
            streamSearch(seriesFile(context, serverUrl), query, SeriesStream::class.java)
        }

    private fun <T> streamSearch(file: File, query: String, cls: Class<T>): List<T> {
        if (!file.exists()) return emptyList()
        val results = mutableListOf<T>()
        val gson = Gson()
        val elementAdapter = gson.getAdapter(JsonElement::class.java)
        try {
            file.bufferedReader(Charsets.UTF_8).use { br ->
                br.mark(1)
                if (br.read() != '\uFEFF'.code) br.reset()
                val reader = JsonReader(br).also { it.isLenient = true }
                reader.beginArray()
                while (reader.hasNext()) {
                    if (results.size >= 50) { reader.skipValue(); continue }
                    val el = elementAdapter.read(reader) ?: continue
                    val name = try { el.asJsonObject?.get("name")?.asString }
                                catch (_: Exception) { null } ?: continue
                    if (name.contains(query, ignoreCase = true)) {
                        try { gson.fromJson(el, cls)?.let { results.add(it) } }
                        catch (_: Exception) {}
                    }
                }
                try { reader.endArray() } catch (_: Exception) {}
            }
        } catch (_: Throwable) {}
        return results
    }

    // ── Full-list accessors (live streams, and legacy callers) ───────────────────

    suspend fun getMovies(context: Context, serverUrl: String): List<VodStream>? = withContext(Dispatchers.IO) {
        val f = moviesFile(context, serverUrl)
        if (!f.exists()) return@withContext null
        try {
            f.bufferedReader(Charsets.UTF_8).use { br ->
                br.mark(1)
                if (br.read() != '\uFEFF'.code) br.reset()
                val type = object : TypeToken<List<VodStream>>() {}.type
                Gson().fromJson<List<VodStream>>(JsonReader(br), type)
            }
        } catch (_: Exception) { null }
    }

    suspend fun saveMovies(context: Context, serverUrl: String, movies: List<VodStream>) = withContext(Dispatchers.IO) {
        try {
            moviesFile(context, serverUrl).bufferedWriter().use { writer ->
                Gson().toJson(movies, object : TypeToken<List<VodStream>>() {}.type, JsonWriter(writer))
            }
        } catch (_: Exception) {}
    }

    suspend fun getSeries(context: Context, serverUrl: String): List<SeriesStream>? = withContext(Dispatchers.IO) {
        val f = seriesFile(context, serverUrl)
        if (!f.exists()) return@withContext null
        try {
            f.bufferedReader(Charsets.UTF_8).use { br ->
                br.mark(1)
                if (br.read() != '\uFEFF'.code) br.reset()
                val type = object : TypeToken<List<SeriesStream>>() {}.type
                Gson().fromJson<List<SeriesStream>>(JsonReader(br), type)
            }
        } catch (_: Exception) { null }
    }

    suspend fun saveSeries(context: Context, serverUrl: String, series: List<SeriesStream>) = withContext(Dispatchers.IO) {
        try {
            seriesFile(context, serverUrl).bufferedWriter().use { writer ->
                Gson().toJson(series, object : TypeToken<List<SeriesStream>>() {}.type, JsonWriter(writer))
            }
        } catch (_: Exception) {}
    }

    suspend fun getLiveStreams(context: Context, serverUrl: String): List<LiveStream>? = withContext(Dispatchers.IO) {
        val f = liveFile(context, serverUrl)
        if (!f.exists()) return@withContext null
        try {
            f.bufferedReader(Charsets.UTF_8).use { br ->
                br.mark(1)
                if (br.read() != '\uFEFF'.code) br.reset()
                val type = object : TypeToken<List<LiveStream>>() {}.type
                Gson().fromJson<List<LiveStream>>(JsonReader(br), type)
            }
        } catch (_: Exception) { null }
    }

    suspend fun saveLiveStreams(context: Context, serverUrl: String, streams: List<LiveStream>) = withContext(Dispatchers.IO) {
        try {
            liveFile(context, serverUrl).bufferedWriter().use { writer ->
                Gson().toJson(streams, object : TypeToken<List<LiveStream>>() {}.type, JsonWriter(writer))
            }
        } catch (_: Exception) {}
    }

    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        dir(context).listFiles()?.forEach { it.delete() }
    }
}
