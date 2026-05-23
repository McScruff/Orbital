package com.skyretro.iptv.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.skyretro.iptv.data.model.SeriesStream
import com.skyretro.iptv.data.model.VodStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ContentCache {

    private const val TTL_MS = 24 * 60 * 60 * 1000L

    private fun dir(context: Context) = File(context.filesDir, "content_cache").also { it.mkdirs() }

    private fun moviesFile(context: Context, serverUrl: String) =
        File(dir(context), "movies_${serverUrl.hashCode()}.json")

    private fun seriesFile(context: Context, serverUrl: String) =
        File(dir(context), "series_${serverUrl.hashCode()}.json")

    fun isMoviesValid(context: Context, serverUrl: String): Boolean {
        val f = moviesFile(context, serverUrl)
        return f.exists() && System.currentTimeMillis() - f.lastModified() < TTL_MS
    }

    fun isSeriesValid(context: Context, serverUrl: String): Boolean {
        val f = seriesFile(context, serverUrl)
        return f.exists() && System.currentTimeMillis() - f.lastModified() < TTL_MS
    }

    // Streaming read — avoids loading entire JSON string into memory
    suspend fun getMovies(context: Context, serverUrl: String): List<VodStream>? = withContext(Dispatchers.IO) {
        val f = moviesFile(context, serverUrl)
        if (!f.exists()) return@withContext null
        try {
            f.bufferedReader().use { reader ->
                val type = object : TypeToken<List<VodStream>>() {}.type
                Gson().fromJson<List<VodStream>>(JsonReader(reader), type)
            }
        } catch (_: Exception) { null }
    }

    // Streaming write — avoids allocating the full JSON string in memory
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
            f.bufferedReader().use { reader ->
                val type = object : TypeToken<List<SeriesStream>>() {}.type
                Gson().fromJson<List<SeriesStream>>(JsonReader(reader), type)
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

    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        dir(context).listFiles()?.forEach { it.delete() }
    }
}
