package com.orbital.iptv.data.torbox

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TorboxFile(
    val id: Long,
    val name: String,
    val shortName: String,
    val sizeBytes: Long,
    val mimeType: String
)

data class TorboxTorrent(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
    val downloadFinished: Boolean,
    val files: List<TorboxFile>
)

private val PLAYABLE_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "wmv", "flv",
    "mp3", "flac", "aac", "wav", "ogg", "m4a", "opus"
)

fun TorboxFile.isPlayable(): Boolean {
    if (mimeType.startsWith("video/") || mimeType.startsWith("audio/")) return true
    val ext = shortName.substringAfterLast('.', "").lowercase()
    return ext in PLAYABLE_EXTENSIONS
}

object TorboxRepository {

    private const val BASE = "https://api.torbox.app/v1/api"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Validates an API key against /user/me. Returns the account email on success. */
    suspend fun validateKey(apiKey: String): Result<String> = runCatching {
        val json = get("$BASE/user/me", apiKey)
        if (!json.optBoolean("success", false)) {
            throw Exception(json.optString("detail", "Invalid API key"))
        }
        json.optJSONObject("data")?.optString("email", "") ?: ""
    }

    suspend fun listTorrents(apiKey: String): Result<List<TorboxTorrent>> = runCatching {
        val json = get("$BASE/torrents/mylist?bypass_cache=true", apiKey)
        if (!json.optBoolean("success", false)) {
            throw Exception(json.optString("detail", "Failed to list downloads"))
        }
        val data = json.optJSONArray("data") ?: return@runCatching emptyList()
        (0 until data.length()).map { i ->
            val t = data.getJSONObject(i)
            val filesArr = t.optJSONArray("files")
            val files = if (filesArr != null) (0 until filesArr.length()).map { j ->
                val f = filesArr.getJSONObject(j)
                TorboxFile(
                    id = f.optLong("id"),
                    name = f.optString("name"),
                    shortName = f.optString("short_name", f.optString("name")),
                    sizeBytes = f.optLong("size"),
                    mimeType = f.optString("mimetype", "")
                )
            } else emptyList()
            TorboxTorrent(
                id = t.optLong("id"),
                name = t.optString("name"),
                sizeBytes = t.optLong("size"),
                downloadFinished = t.optBoolean("download_finished", false),
                files = files
            )
        }
    }

    /** Requests a direct CDN download/stream link for a single file within a torrent. */
    suspend fun requestDownloadLink(apiKey: String, torrentId: Long, fileId: Long): Result<String> = runCatching {
        val url = "$BASE/torrents/requestdl?token=$apiKey&torrent_id=$torrentId&file_id=$fileId&redirect=false"
        val json = get(url, apiKey)
        if (!json.optBoolean("success", false)) {
            throw Exception(json.optString("detail", "Failed to get download link"))
        }
        json.optString("data", "").ifBlank { throw Exception("Empty download link") }
    }

    private fun get(url: String, apiKey: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .build()
        val body = client.newCall(req).execute().use { it.body?.string() ?: "" }
        return JSONObject(body)
    }
}
