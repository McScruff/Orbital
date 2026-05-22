package com.skyretro.iptv.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.skyretro.iptv.data.model.SeriesStream
import com.skyretro.iptv.data.model.VodStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ContentCache {

    private const val TTL_MS = 24 * 60 * 60 * 1000L

    private fun dir(context: Context) = File(context.filesDir, "content_cache").also { it.mkdirs() }

    // Keyed by hash of serverUrl so different servers don't clash
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

    suspend fun getMovies(context: Context, serverUrl: String): List<VodStream>? = withContext(Dispatchers.IO) {
        val f = moviesFile(context, serverUrl)
        if (!f.exists()) return@withContext null
        try {
            val type = object : TypeToken<List<VodStream>>() {}.type
            Gson().fromJson<List<VodStream>>(f.readText(), type)
        } catch (_: Exception) { null }
    }

    suspend fun saveMovies(context: Context, serverUrl: String, movies: List<VodStream>) = withContext(Dispatchers.IO) {
        try { moviesFile(context, serverUrl).writeText(Gson().toJson(movies)) } catch (_: Exception) {}
    }

    suspend fun getSeries(context: Context, serverUrl: String): List<SeriesStream>? = withContext(Dispatchers.IO) {
        val f = seriesFile(context, serverUrl)
        if (!f.exists()) return@withContext null
        try {
            val type = object : TypeToken<List<SeriesStream>>() {}.type
            Gson().fromJson<List<SeriesStream>>(f.readText(), type)
        } catch (_: Exception) { null }
    }

    suspend fun saveSeries(context: Context, serverUrl: String, series: List<SeriesStream>) = withContext(Dispatchers.IO) {
        try { seriesFile(context, serverUrl).writeText(Gson().toJson(series)) } catch (_: Exception) {}
    }

    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        dir(context).listFiles()?.forEach { it.delete() }
    }
}
