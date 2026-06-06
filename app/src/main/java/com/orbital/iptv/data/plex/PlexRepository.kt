package com.orbital.iptv.data.plex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class PlexRepository {
    companion object {
        private const val PLEX_TV   = "https://plex.tv"
        const val CLIENT_ID = "orbital-android"
        private const val PRODUCT   = "Orbital"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun plexTvBuilder(path: String, token: String? = null): Request.Builder =
        Request.Builder()
            .url("$PLEX_TV$path")
            .addHeader("X-Plex-Client-Identifier", CLIENT_ID)
            .addHeader("X-Plex-Product", PRODUCT)
            .addHeader("X-Plex-Version", "1.0")
            .addHeader("X-Plex-Platform", "Android")
            .addHeader("X-Plex-Platform-Version", "14")
            .addHeader("X-Plex-Device", "Android TV")
            .addHeader("X-Plex-Provides", "controller")
            .addHeader("Accept", "application/json")
            .apply { if (token != null) addHeader("X-Plex-Token", token) }

    private fun serverBuilder(serverUrl: String, path: String, token: String): Request.Builder {
        val base = serverUrl.trimEnd('/')
        val sep = if ('?' in path) '&' else '?'
        return Request.Builder()
            .url("$base$path${sep}X-Plex-Token=$token")
            .addHeader("X-Plex-Token", token)
            .addHeader("X-Plex-Client-Identifier", CLIENT_ID)
            .addHeader("X-Plex-Product", PRODUCT)
            .addHeader("X-Plex-Version", "1.0")
            .addHeader("X-Plex-Platform", "Android")
            .addHeader("X-Plex-Platform-Version", "14")
            .addHeader("X-Plex-Device", "Android TV")
            .addHeader("Accept", "application/json")
    }

    // ── Connection probing ────────────────────────────────────────────────────

    suspend fun findBestWorkingUrl(server: PlexServer, token: String): String? {
        // Collect unique candidate base URLs. Each plex.direct URI is tried alongside the raw
        // http://address:port form so DNS filtering / Docker internal IPs don't block us.
        val seen = linkedSetOf<String>()
        fun add(uri: String) { if (uri.isNotBlank()) seen.add(uri) }
        fun addConn(c: PlexConnection) {
            if (c.address.isNotBlank()) add("http://${c.address}:${c.port}")
            add(c.uri)
        }
        server.connections.filter { it.local && !it.relay }.forEach(::addConn)
        server.connections.filter { !it.local && !it.relay && it.uri.startsWith("https") }.forEach(::addConn)
        server.connections.filter { !it.local && !it.relay }.forEach(::addConn)
        server.connections.filter { it.relay }.forEach { add(it.uri) }
        if (seen.isEmpty()) return null

        // Probe all candidates in parallel — first 200 wins.
        return coroutineScope {
            val ch = Channel<String?>(Channel.UNLIMITED)
            val jobs = seen.map { base ->
                launch(Dispatchers.IO) {
                    val ok = try {
                        val req = Request.Builder()
                            .url("${base.trimEnd('/')}/identity?X-Plex-Token=$token")
                            .addHeader("X-Plex-Token", token)
                            .addHeader("X-Plex-Client-Identifier", CLIENT_ID)
                            .addHeader("X-Plex-Product", PRODUCT)
                            .addHeader("X-Plex-Version", "1.0")
                            .addHeader("X-Plex-Platform", "Android")
                            .addHeader("X-Plex-Platform-Version", "14")
                            .addHeader("X-Plex-Device", "Android TV")
                            .addHeader("Accept", "application/json")
                            .build()
                        probeClient.newCall(req).execute().use { it.code } in 200..299
                    } catch (_: Exception) { false }
                    ch.trySend(if (ok) base else null)
                }
            }
            var result: String? = null
            for (i in seen.indices) {
                val r = ch.receive()
                if (r != null) { result = r; break }
            }
            jobs.forEach { it.cancel() }
            ch.close()
            result
        }
    }

    // ── PIN flow ──────────────────────────────────────────────────────────────

    suspend fun requestPin(): Result<Pair<Long, String>> = runCatching {
        // No body / no 'strong' param → 4-char code for plex.tv/link
        // strong=true triggers the OAuth flow which gives a 25-char code
        val body = FormBody.Builder().build()
        val req = plexTvBuilder("/api/v2/pins").post(body).build()
        val resp = client.newCall(req).execute()
        val json = resp.use { it.body?.string() ?: "" }
        if (json.isBlank()) throw Exception("Empty response (HTTP ${resp.code})")
        val obj = JSONObject(json)
        if (obj.has("errors")) throw Exception(obj.optJSONArray("errors")?.optJSONObject(0)?.optString("message") ?: "Plex error")
        obj.getLong("id") to obj.getString("code")
    }

    suspend fun checkPin(pinId: Long, code: String): Result<String?> = runCatching {
        val req = plexTvBuilder("/api/v2/pins/$pinId?code=$code").build()
        val json = client.newCall(req).execute().use { it.body?.string() ?: "" }
        if (json.isBlank()) return@runCatching null
        // org.json returns the string "null" for JSON null values — must reject that explicitly
        val token = JSONObject(json).opt("authToken")
        if (token == null || token == JSONObject.NULL) return@runCatching null
        token.toString().takeIf { it.isNotBlank() && it != "null" }
    }

    suspend fun getUserName(token: String): Result<String> = runCatching {
        val req = plexTvBuilder("/api/v2/user", token).build()
        val json = client.newCall(req).execute().use { it.body?.string() ?: "" }
        JSONObject(json).optString("username").ifBlank { "Plex User" }
    }

    suspend fun getServers(token: String): Result<List<PlexServer>> = runCatching {
        // Token sent both as header and query param — some Plex API versions require the latter
        val req = plexTvBuilder(
            "/api/v2/resources?includeHttps=1&includeRelay=1&X-Plex-Token=$token",
            token
        ).build()
        val (code, json) = client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        if (code == 401 || code == 403) throw Exception("Authentication failed (HTTP $code) — check your Plex token")
        if (code !in 200..299) throw Exception("Server error HTTP $code")
        if (json.isBlank()) return@runCatching emptyList()
        val arr = org.json.JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.optString("provides").contains("server")) continue
                val conns = o.optJSONArray("connections")
                val connections = buildList {
                    if (conns != null) for (j in 0 until conns.length()) {
                        val c = conns.getJSONObject(j)
                        add(PlexConnection(
                        uri     = c.optString("uri"),
                        local   = c.optBoolean("local"),
                        relay   = c.optBoolean("relay"),
                        address = c.optString("address"),
                        port    = c.optInt("port", 32400)
                    ))
                    }
                }
                add(PlexServer(o.optString("name"), connections, o.optString("accessToken")))
            }
        }
    }

    // ── Metadata parsing ──────────────────────────────────────────────────────

    private fun resolutionRank(res: String?): Int = when (res?.lowercase()) {
        "4k", "2160", "uhd" -> 5
        "1080"              -> 4
        "720"               -> 3
        "480"               -> 2
        "sd"                -> 1
        else                -> 0
    }

    private fun bestMediaObject(o: org.json.JSONObject): org.json.JSONObject? {
        val arr = o.optJSONArray("Media") ?: return null
        var best: org.json.JSONObject? = null
        var bestRank = -1
        for (m in 0 until arr.length()) {
            val med = arr.optJSONObject(m) ?: continue
            val rank = resolutionRank(med.optString("videoResolution").ifBlank { null })
            if (rank > bestRank) { bestRank = rank; best = med }
        }
        return best
    }

    private fun parseMetadata(json: String): List<PlexItem> = buildList {
        try {
            val container = JSONObject(json).optJSONObject("MediaContainer") ?: return@buildList
            val metadata = container.optJSONArray("Metadata") ?: return@buildList
            for (i in 0 until metadata.length()) {
                val o = metadata.getJSONObject(i)
                val media = bestMediaObject(o)
                val partKey = media?.optJSONArray("Part")?.optJSONObject(0)?.optString("key")
                add(PlexItem(
                    ratingKey        = o.optString("ratingKey"),
                    title            = o.optString("title"),
                    type             = o.optString("type"),
                    year             = o.optInt("year", 0).takeIf { it > 0 },
                    thumb            = o.optString("thumb").ifBlank { null },
                    parentTitle      = o.optString("parentTitle").ifBlank { null },
                    grandparentTitle = o.optString("grandparentTitle").ifBlank { null },
                    parentIndex      = o.optInt("parentIndex", 0).takeIf { it > 0 },
                    index            = o.optInt("index", 0).takeIf { it > 0 },
                    duration         = o.optLong("duration", 0L).takeIf { it > 0L },
                    viewOffset       = o.optLong("viewOffset", 0L).takeIf { it > 0L },
                    partKey          = partKey?.ifBlank { null },
                    videoResolution  = media?.optString("videoResolution")?.ifBlank { null }
                ))
            }
        } catch (_: Exception) {}
    }

    // ── Library endpoints ──────────────────────────────────────────────────────

    suspend fun getOnDeck(serverUrl: String, token: String): Result<List<PlexItem>> = runCatching {
        val req = serverBuilder(serverUrl, "/library/onDeck", token).build()
        val (code, json) = client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        if (code == 401 || code == 403) throw Exception("Server auth failed (HTTP $code): ${json.take(120)}")
        if (code !in 200..299) throw Exception("Server error HTTP $code: ${json.take(80)}")
        parseMetadata(json)
    }

    private suspend fun sectionKeys(serverUrl: String, token: String, type: String): List<String> {
        val req = serverBuilder(serverUrl, "/library/sections", token).build()
        val (code, json) = client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        if (code == 401 || code == 403) throw Exception("Server auth failed (HTTP $code): ${json.take(120)}")
        if (code !in 200..299) throw Exception("Server error HTTP $code: ${json.take(80)}")
        val container = JSONObject(json).optJSONObject("MediaContainer") ?: return emptyList()
        val dirs = container.optJSONArray("Directory") ?: return emptyList()
        return buildList { for (i in 0 until dirs.length()) {
            val d = dirs.getJSONObject(i)
            if (d.optString("type") == type) add(d.optString("key"))
        }}
    }

    private suspend fun allFromSections(serverUrl: String, token: String, sectionType: String): List<PlexItem> {
        val all = mutableListOf<PlexItem>()
        val pageSize = 250
        for (key in sectionKeys(serverUrl, token, sectionType)) {
            var start = 0
            while (true) {
                val path = "/library/sections/$key/all?sort=titleSort" +
                    "&X-Plex-Container-Start=$start&X-Plex-Container-Size=$pageSize"
                val (code, json) = client.newCall(serverBuilder(serverUrl, path, token).build())
                    .execute().use { it.code to (it.body?.string() ?: "") }
                if (code !in 200..299) break
                val page = parseMetadata(json)
                all.addAll(page)
                if (page.size < pageSize) break
                start += pageSize
            }
        }
        return all.sortedBy { it.title.lowercase() }
    }

    suspend fun getMovies(serverUrl: String, token: String): Result<List<PlexItem>> = runCatching {
        allFromSections(serverUrl, token, "movie")
    }

    suspend fun getShows(serverUrl: String, token: String): Result<List<PlexItem>> = runCatching {
        allFromSections(serverUrl, token, "show")
    }

    suspend fun getArtists(serverUrl: String, token: String): Result<List<PlexItem>> = runCatching {
        allFromSections(serverUrl, token, "artist")
    }

    private suspend fun getSectionGenres(serverUrl: String, token: String, sectionType: String): List<PlexGenre> {
        val seen = mutableSetOf<String>()
        val genres = mutableListOf<PlexGenre>()
        for (key in sectionKeys(serverUrl, token, sectionType)) {
            val req = serverBuilder(serverUrl, "/library/sections/$key/genre", token).build()
            val (code, json) = client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
            if (code !in 200..299) continue
            val container = JSONObject(json).optJSONObject("MediaContainer") ?: continue
            val dirs = container.optJSONArray("Directory") ?: continue
            for (i in 0 until dirs.length()) {
                val d = dirs.getJSONObject(i)
                val title = d.optString("title")
                if (title.isNotBlank() && seen.add(title))
                    genres.add(PlexGenre(title, d.optString("ratingKey"), key))
            }
        }
        return genres.sortedBy { it.title }
    }

    private suspend fun getFromSectionsByGenreName(serverUrl: String, token: String, sectionType: String, genreName: String): Result<List<PlexItem>> = runCatching {
        val all = mutableListOf<PlexItem>()
        val pageSize = 250
        for (key in sectionKeys(serverUrl, token, sectionType)) {
            val genreReq = serverBuilder(serverUrl, "/library/sections/$key/genre", token).build()
            val (gCode, gJson) = client.newCall(genreReq).execute().use { it.code to (it.body?.string() ?: "") }
            if (gCode !in 200..299) continue
            val container = JSONObject(gJson).optJSONObject("MediaContainer") ?: continue
            val dirs = container.optJSONArray("Directory") ?: continue
            var genreKey: String? = null
            for (i in 0 until dirs.length()) {
                val d = dirs.getJSONObject(i)
                if (d.optString("title") == genreName) { genreKey = d.optString("ratingKey"); break }
            }
            if (genreKey == null) continue
            var start = 0
            while (true) {
                val path = "/library/sections/$key/all?sort=titleSort&genre=$genreKey" +
                    "&X-Plex-Container-Start=$start&X-Plex-Container-Size=$pageSize"
                val (code, json) = client.newCall(serverBuilder(serverUrl, path, token).build())
                    .execute().use { it.code to (it.body?.string() ?: "") }
                if (code !in 200..299) break
                val page = parseMetadata(json)
                all.addAll(page)
                if (page.size < pageSize) break
                start += pageSize
            }
        }
        all.sortedBy { it.title.lowercase() }
    }

    suspend fun getMovieGenres(serverUrl: String, token: String) = getSectionGenres(serverUrl, token, "movie")
    suspend fun getMoviesByGenreName(serverUrl: String, token: String, genreName: String) = getFromSectionsByGenreName(serverUrl, token, "movie", genreName)
    suspend fun getShowGenres(serverUrl: String, token: String) = getSectionGenres(serverUrl, token, "show")
    suspend fun getShowsByGenreName(serverUrl: String, token: String, genreName: String) = getFromSectionsByGenreName(serverUrl, token, "show", genreName)

    suspend fun getChildren(serverUrl: String, token: String, ratingKey: String): Result<List<PlexItem>> = runCatching {
        val req = serverBuilder(serverUrl, "/library/metadata/$ratingKey/children", token).build()
        parseMetadata(client.newCall(req).execute().use { it.body?.string() ?: "" })
    }

    suspend fun search(serverUrl: String, token: String, query: String): Result<List<PlexItem>> = runCatching {
        val q = URLEncoder.encode(query, "UTF-8")
        val req = serverBuilder(serverUrl, "/hubs/search?query=$q&limit=50", token).build()
        val json = client.newCall(req).execute().use { it.body?.string() ?: "" }
        val container = JSONObject(json).optJSONObject("MediaContainer") ?: return@runCatching emptyList()
        val hubs = container.optJSONArray("Hub") ?: return@runCatching emptyList()
        val playable = setOf("movie", "show", "episode", "artist", "album", "track")
        buildList {
            for (i in 0 until hubs.length()) {
                val hub = hubs.getJSONObject(i)
                if (hub.optString("type") !in playable) continue
                val metadata = hub.optJSONArray("Metadata") ?: continue
                for (j in 0 until metadata.length()) {
                    val o = metadata.getJSONObject(j)
                    val media = bestMediaObject(o)
                    val partKey = media?.optJSONArray("Part")?.optJSONObject(0)?.optString("key")
                    add(PlexItem(
                        ratingKey        = o.optString("ratingKey"),
                        title            = o.optString("title"),
                        type             = o.optString("type"),
                        year             = o.optInt("year", 0).takeIf { it > 0 },
                        thumb            = o.optString("thumb").ifBlank { null },
                        parentTitle      = o.optString("parentTitle").ifBlank { null },
                        grandparentTitle = o.optString("grandparentTitle").ifBlank { null },
                        parentIndex      = o.optInt("parentIndex", 0).takeIf { it > 0 },
                        index            = o.optInt("index", 0).takeIf { it > 0 },
                        duration         = o.optLong("duration", 0L).takeIf { it > 0L },
                        viewOffset       = o.optLong("viewOffset", 0L).takeIf { it > 0L },
                        partKey          = partKey?.ifBlank { null }
                    ))
                }
            }
        }
    }

    // ── Playback reporting ────────────────────────────────────────────────────

    suspend fun markPlayed(serverUrl: String, token: String, ratingKey: String): Result<Unit> =
        runCatching {
            val base = serverUrl.trimEnd('/')
            val url = "$base/:/scrobble?identifier=com.plexapp.plugins.library&key=$ratingKey" +
                "&X-Plex-Token=$token&X-Plex-Client-Identifier=$CLIENT_ID"
            client.newCall(Request.Builder().url(url).build()).execute().close()
        }

    suspend fun reportTimeline(
        serverUrl: String, token: String,
        ratingKey: String, state: String, timeMs: Long, durationMs: Long
    ): Result<Unit> = runCatching {
        val key = URLEncoder.encode("/library/metadata/$ratingKey", "UTF-8")
        val base = serverUrl.trimEnd('/')
        val url = "$base/:/timeline?ratingKey=$ratingKey&key=$key&state=$state" +
            "&time=$timeMs&duration=$durationMs" +
            "&X-Plex-Token=$token&X-Plex-Client-Identifier=$CLIENT_ID"
        client.newCall(Request.Builder().url(url).build()).execute().close()
    }

    // ── URL builders ──────────────────────────────────────────────────────────

    fun buildThumbUrl(serverUrl: String, thumb: String?, token: String): String? {
        if (thumb.isNullOrBlank()) return null
        return "${serverUrl.trimEnd('/')}$thumb?X-Plex-Token=$token"
    }

    fun buildStreamUrl(serverUrl: String, partKey: String, token: String): String =
        "${serverUrl.trimEnd('/')}$partKey?X-Plex-Token=$token&download=1&X-Plex-Client-Identifier=$CLIENT_ID"
}
