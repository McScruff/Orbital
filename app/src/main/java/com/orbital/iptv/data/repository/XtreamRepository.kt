package com.orbital.iptv.data.repository

import android.util.Base64
import android.util.Log
import android.util.Xml
import com.orbital.iptv.data.api.ApiClient
import com.orbital.iptv.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit


class XtreamRepository {

    companion object {
        // XMLTV `channel id="..."` and LiveStream.epgChannelId are supposed to be the same
        // string, but some providers differ by case or stray whitespace between the two feeds —
        // normalize both sides before comparing so getFullEpgXmltv's map lookups aren't silently
        // missed by a cosmetic mismatch (e.g. "BBC1.uk" vs "bbc1.uk").
        fun normEpgId(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        private const val TAG = "XtreamRepository"

        /** For the full-guide download only (see streamFullEpgXmltv). IPv4 first, as in ApiClient. */
        private val bulkClient by lazy {
            OkHttpClient.Builder()
                .dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String) =
                        okhttp3.Dns.SYSTEM.lookup(hostname).sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
                })
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }

    suspend fun authenticate(serverUrl: String, username: String, password: String): Result<ServerInfo> {
        return try {
            val service = ApiClient.getService(serverUrl)
            val info = service.getServerInfo(username, password)
            if (info.userInfo?.status == "Active") {
                Result.success(info)
            } else {
                Result.failure(Exception("Account not active or invalid credentials"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLiveCategories(serverUrl: String, username: String, password: String): Result<List<LiveCategory>> {
        return try {
            val service = ApiClient.getService(serverUrl)
            val categories = service.getLiveCategories(username, password)
            Result.success(categories)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLiveStreams(serverUrl: String, username: String, password: String): Result<List<LiveStream>> {
        return try {
            val service = ApiClient.getService(serverUrl)
            val streams = service.getAllLiveStreams(username, password)
            Result.success(streams)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLiveStreamsByCategory(
        serverUrl: String,
        username: String,
        password: String,
        categoryId: String
    ): Result<List<LiveStream>> {
        return try {
            val service = ApiClient.getService(serverUrl)
            val streams = service.getLiveStreamsByCategory(username, password, categoryId = categoryId)
            Result.success(streams)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getShortEpg(serverUrl: String, username: String, password: String, streamId: Int): Result<EpgResponse> {
        return try {
            val service = ApiClient.getService(serverUrl)
            val epg = service.getShortEpg(username, password, streamId = streamId)
            Result.success(epg)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // get_short_epg caps out at a handful of near-term entries on many Xtream panels — the
    // `limit` param is only a hint and gets ignored/clamped server-side (confirmed: one provider
    // returned exactly 1 listing for limit=100, vs. 34 from get_simple_data_table for the same
    // channel). get_simple_data_table is the richer multi-day table already used by
    // getCatchupEpg(); falls back to the short-EPG call if a server doesn't support it.
    suspend fun getFullChannelEpg(serverUrl: String, username: String, password: String, streamId: Int): Result<EpgResponse> {
        val service = ApiClient.getService(serverUrl)
        return try {
            Result.success(service.getSimpleDataTable(username, password, streamId = streamId))
        } catch (e: Exception) {
            try {
                Result.success(service.getShortEpgWithLimit(username, password, streamId = streamId, limit = 100))
            } catch (e2: Exception) {
                Result.failure(e2)
            }
        }
    }

    fun buildStreamUrl(serverUrl: String, username: String, password: String, streamId: Int): String {
        return ApiClient.buildStreamUrl(serverUrl, username, password, streamId)
    }

    // XMLTV datetime: "20240115180000 +0000" (yyyyMMddHHmmss, space, RFC-822 offset).
    private val xmltvDateFmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)

    private fun parseXmltvDateSec(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return try { xmltvDateFmt.parse(raw.trim())?.time?.let { it / 1000 } } catch (_: Exception) { null }
    }

    // EpgListing.title/description are read everywhere via getDecodedTitle()/getDecodedDescription(),
    // which base64-decode — that's how Xtream's own JSON EPG endpoints deliver them. XMLTV gives
    // plain text, so it's re-encoded here at the parsing boundary to stay a drop-in match for every
    // existing call site (EpgView, EpgCache, ...) without touching any of them.
    private fun b64(s: String): String = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /**
     * Bulk EPG fetch: one request for the whole guide (every channel, full multi-day window)
     * instead of one get_simple_data_table call per channel. Keyed by normalizeEpgId(XMLTV
     * `channel` id) — the caller should look up with normEpgId(stream.epgChannelId) to match.
     * Streams the response with XmlPullParser rather than loading it into one big String, since a
     * full multi-day guide for a large channel list can run several MB.
     */
    suspend fun getFullEpgXmltv(serverUrl: String, username: String, password: String): Result<Map<String, List<EpgListing>>> =
        withContext(Dispatchers.IO) {
            try {
                val body = ApiClient.getService(serverUrl).getXmltv(username, password)
                val result = mutableMapOf<String, MutableList<EpgListing>>()
                body.byteStream().use { stream ->
                    val parser = Xml.newPullParser()
                    parser.setInput(stream, null)   // null = auto-detect encoding from the XML prolog
                    var eventType = parser.eventType
                    var curChannel: String? = null
                    var curStart: Long? = null
                    var curStop: Long? = null
                    var curTitle: String? = null
                    var curDesc: String? = null
                    var inTitle = false
                    var inDesc = false
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        when (eventType) {
                            XmlPullParser.START_TAG -> when (parser.name) {
                                "programme" -> {
                                    curChannel = parser.getAttributeValue(null, "channel")
                                    curStart = parseXmltvDateSec(parser.getAttributeValue(null, "start"))
                                    curStop = parseXmltvDateSec(parser.getAttributeValue(null, "stop"))
                                    curTitle = null
                                    curDesc = null
                                }
                                "title" -> inTitle = true
                                "desc" -> inDesc = true
                            }
                            XmlPullParser.TEXT -> {
                                if (inTitle) curTitle = (curTitle ?: "") + parser.text
                                else if (inDesc) curDesc = (curDesc ?: "") + parser.text
                            }
                            XmlPullParser.END_TAG -> when (parser.name) {
                                "title" -> inTitle = false
                                "desc" -> inDesc = false
                                "programme" -> {
                                    val ch = normEpgId(curChannel)
                                    val s = curStart
                                    val e = curStop
                                    if (ch != null && s != null && e != null) {
                                        result.getOrPut(ch) { mutableListOf() }.add(
                                            EpgListing(
                                                id = null,
                                                epgId = null,
                                                title = b64(curTitle ?: ""),
                                                description = b64(curDesc ?: ""),
                                                start = null,
                                                end = null,
                                                startTimestamp = s.toString(),
                                                stopTimestamp = e.toString()
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        eventType = parser.next()
                    }
                }
                android.util.Log.i("XtreamRepository", "xmltv.php: parsed ${result.size} channel ids, ${result.values.sumOf { it.size }} programmes total")
                Result.success(result)
            } catch (e: Exception) {
                android.util.Log.w("XtreamRepository", "xmltv.php bulk EPG fetch failed — falling back to per-channel", e)
                Result.failure(e)
            }
        }

    /**
     * Bulk EPG fetch: one request for the whole guide (every channel, full multi-day window)
     * instead of one call per channel. A provider's guide can be very large (one is 146 MB with
     * nearly half a million programmes), far more than the app may hold in memory, so nothing is
     * collected here: the response is stream-parsed and each channel's programmes are handed to
     * [onChannel] as soon as that channel ends, then dropped. Only channels in [wanted] (ids as
     * normEpgId gives them) are kept, and programmes that finished more than a day ago are skipped.
     * XMLTV lists a channel's programmes together; if a provider repeats a channel later in the
     * file, [onChannel] is called again for it with the extra programmes and [append] = true.
     * Returns how many channels were handed over. [onChannel] runs on the IO thread.
     */
    suspend fun streamFullEpgXmltv(
        serverUrl: String, username: String, password: String, wanted: Set<String>,
        onChannel: (epgId: String, listings: List<EpgListing>, append: Boolean) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "full guide: requesting")
            val url = (if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/").toHttpUrl().newBuilder()
                .addPathSegment("xmltv.php")
                .addQueryParameter("username", username)
                .addQueryParameter("password", password)
                .build()
            // Its own client, not the shared API one: the guide screen keeps that one's per-host
            // slots busy with per-channel requests, and this call used to queue behind them for
            // minutes without ever reaching the network.
            val response = bulkClient.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) {
                response.close()
                Log.w(TAG, "full guide: HTTP ${response.code}")
                throw retrofit2.HttpException(retrofit2.Response.error<Any>(response.code,
                    "".toResponseBody(null)))
            }
            Log.i(TAG, "full guide: downloading")
            val body = response.body ?: throw java.io.IOException("Empty guide response")
            val cutoff = System.currentTimeMillis() / 1000 - 24 * 3600
            val done = HashSet<String>()
            var current: String? = null
            var batch = ArrayList<EpgListing>()
            var programmes = 0
            fun flush() {
                val ch = current ?: return
                if (batch.isNotEmpty()) {
                    onChannel(ch, batch, !done.add(ch))
                    batch = ArrayList()
                    if (done.size % 500 == 0) Log.i(TAG, "full guide: ${done.size} channels so far")
                }
            }
            body.byteStream().use { stream ->
                val parser = Xml.newPullParser()
                parser.setInput(stream, null)   // null = auto-detect encoding from the XML prolog
                var eventType = parser.eventType
                var keep = false
                var curStart: Long? = null
                var curStop: Long? = null
                val title = StringBuilder()
                val desc = StringBuilder()
                var inTitle = false
                var inDesc = false
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "programme" -> {
                                val ch = normEpgId(parser.getAttributeValue(null, "channel"))
                                if (ch != current) { flush(); current = ch }
                                curStop = parseXmltvDateSec(parser.getAttributeValue(null, "stop"))
                                keep = ch != null && ch in wanted && (curStop ?: 0L) >= cutoff
                                curStart = if (keep) parseXmltvDateSec(parser.getAttributeValue(null, "start")) else null
                                title.setLength(0)
                                desc.setLength(0)
                            }
                            "title" -> inTitle = keep
                            "desc" -> inDesc = keep
                        }
                        XmlPullParser.TEXT -> {
                            if (inTitle) title.append(parser.text)
                            else if (inDesc) desc.append(parser.text)
                        }
                        XmlPullParser.END_TAG -> when (parser.name) {
                            "title" -> inTitle = false
                            "desc" -> inDesc = false
                            "programme" -> {
                                val s = curStart
                                val e = curStop
                                if (keep && s != null && e != null) {
                                    batch.add(EpgListing(
                                        id = null,
                                        epgId = null,
                                        title = b64(title.toString()),
                                        description = b64(desc.toString()),
                                        start = null,
                                        end = null,
                                        startTimestamp = s.toString(),
                                        stopTimestamp = e.toString()
                                    ))
                                    programmes++
                                }
                                keep = false
                            }
                        }
                    }
                    eventType = parser.next()
                }
                flush()
            }
            Log.i(TAG, "xmltv.php: saved ${done.size} channels, $programmes programmes")
            Result.success(done.size)
        } catch (e: Exception) {
            Log.w(TAG, "xmltv.php bulk EPG fetch failed: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    suspend fun getVodCategories(serverUrl: String, username: String, password: String): Result<List<VodCategory>> {
        return try {
            Result.success(ApiClient.getService(serverUrl).getVodCategories(username, password))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getVodStreams(serverUrl: String, username: String, password: String, categoryId: String): Result<List<VodStream>> {
        return try {
            Result.success(ApiClient.getService(serverUrl).getVodStreams(username, password, categoryId = categoryId))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getVodInfo(serverUrl: String, username: String, password: String, vodId: Int): Result<VodInfoResponse> {
        return try {
            Result.success(ApiClient.getService(serverUrl).getVodInfo(username, password, vodId = vodId))
        } catch (e: Exception) { Result.failure(e) }
    }

    fun buildVodUrl(serverUrl: String, username: String, password: String, streamId: Int, ext: String): String {
        return ApiClient.buildVodUrl(serverUrl, username, password, streamId, ext)
    }

    suspend fun getAllVodStreams(serverUrl: String, username: String, password: String): Result<List<VodStream>> {
        return try {
            Result.success(ApiClient.getService(serverUrl).getAllVodStreams(username, password))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getSeriesCategories(serverUrl: String, username: String, password: String): Result<List<SeriesCategory>> {
        return try {
            Result.success(ApiClient.getService(serverUrl).getSeriesCategories(username, password))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getSeriesByCategory(serverUrl: String, username: String, password: String, categoryId: String): Result<List<SeriesStream>> {
        return try {
            Result.success(ApiClient.getService(serverUrl).getSeriesByCategory(username, password, categoryId = categoryId))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getAllSeries(serverUrl: String, username: String, password: String): Result<List<SeriesStream>> {
        return try {
            Result.success(ApiClient.getService(serverUrl).getAllSeries(username, password))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getSeriesInfo(serverUrl: String, username: String, password: String, seriesId: Int): Result<SeriesInfoResponse> {
        return try {
            Result.success(ApiClient.getService(serverUrl).getSeriesInfo(username, password, seriesId = seriesId))
        } catch (e: Exception) { Result.failure(e) }
    }

    fun buildSeriesEpisodeUrl(serverUrl: String, username: String, password: String, episodeId: String, ext: String): String {
        return ApiClient.buildSeriesEpisodeUrl(serverUrl, username, password, episodeId, ext)
    }

    suspend fun getCatchupEpg(serverUrl: String, username: String, password: String, streamId: Int): Result<EpgResponse> {
        return try {
            // get_simple_data_table returns multi-day EPG history with real show titles
            Result.success(ApiClient.getService(serverUrl).getSimpleDataTable(username, password, streamId = streamId))
        } catch (e: Exception) {
            // fall back to short EPG if the endpoint isn't supported
            try {
                Result.success(ApiClient.getService(serverUrl).getShortEpgWithLimit(username, password, streamId = streamId, limit = 300))
            } catch (e2: Exception) { Result.failure(e2) }
        }
    }

    fun buildCatchupUrl(serverUrl: String, username: String, password: String, streamId: Int, startTimestamp: Long, durationMinutes: Int): String {
        return ApiClient.buildCatchupUrl(serverUrl, username, password, streamId, startTimestamp, durationMinutes)
    }
}
