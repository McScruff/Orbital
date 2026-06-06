package com.orbital.iptv.data.opensubtitles

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SubtitleHit(
    val fileId: Long,
    val language: String,
    val releaseName: String,
    val fileName: String
)

object OpenSubtitlesRepository {

    private const val BASE = "https://api.opensubtitles.com/api/v1"
    private const val UA   = "Orbital v1.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun base(apiKey: String, path: String) = Request.Builder()
        .url("$BASE$path")
        .header("Api-Key", apiKey)
        .header("User-Agent", UA)
        .header("Accept", "application/json")

    suspend fun searchMovie(
        apiKey: String,
        query: String,
        year: String? = null
    ): Result<List<SubtitleHit>> = withContext(Dispatchers.IO) {
        runCatching {
            val q  = URLEncoder.encode(query, "UTF-8")
            val yr = if (!year.isNullOrBlank()) "&year=$year" else ""
            val req = base(apiKey, "/subtitles?query=$q&type=movie$yr").build()
            parse(client.newCall(req).execute().use { it.body?.string() ?: "" })
        }
    }

    suspend fun searchEpisode(
        apiKey: String,
        query: String,
        season: Int,
        episode: Int
    ): Result<List<SubtitleHit>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query, "UTF-8")
            val req = base(
                apiKey,
                "/subtitles?query=$q&type=episode&season_number=$season&episode_number=$episode"
            ).build()
            parse(client.newCall(req).execute().use { it.body?.string() ?: "" })
        }
    }

    private fun parse(json: String): List<SubtitleHit> = buildList {
        try {
            val data = JSONObject(json).optJSONArray("data") ?: return@buildList
            for (i in 0 until data.length()) {
                val attrs = data.getJSONObject(i).optJSONObject("attributes") ?: continue
                val files = attrs.optJSONArray("files") ?: continue
                if (files.length() == 0) continue
                val file  = files.getJSONObject(0)
                val id    = file.optLong("file_id", -1L)
                if (id < 0) continue
                add(SubtitleHit(
                    fileId      = id,
                    language    = attrs.optString("language", "?"),
                    releaseName = attrs.optString("release", ""),
                    fileName    = file.optString("file_name", "$id.srt")
                ))
            }
        } catch (_: Exception) {}
    }

    suspend fun downloadToCache(
        context: Context,
        apiKey: String,
        hit: SubtitleHit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val body = """{"file_id":${hit.fileId}}""".toRequestBody("application/json".toMediaType())
            val linkJson = client.newCall(base(apiKey, "/download").post(body).build())
                .execute().use { it.body?.string() ?: "" }
            val link = JSONObject(linkJson).getString("link")

            val ext  = hit.fileName.substringAfterLast('.', "srt")
            val dest = File(File(context.cacheDir, "subtitles").also { it.mkdirs() }, "${hit.fileId}.$ext")
            client.newCall(Request.Builder().url(link).header("User-Agent", UA).build())
                .execute().use { resp ->
                    resp.body?.byteStream()?.use { it.copyTo(dest.outputStream()) }
                }
            dest
        }
    }
}
