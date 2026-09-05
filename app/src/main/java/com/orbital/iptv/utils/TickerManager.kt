package com.orbital.iptv.utils

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object TickerManager {

    // ESPN's CDN (Akamai Bot Manager) can 403-block requests that don't look like a real
    // browser — a spoofed User-Agent alone isn't enough, since Akamai also weighs the HTTP/2
    // fingerprint and the presence of Chrome's Client Hints headers. espnGet() below is the one
    // place every ESPN call in the app should go through, so this mitigation only needs tuning
    // in one spot.
    private const val ESPN_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 9a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val espnClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        // Forcing HTTP/1.1 avoids ESPN's Akamai front fingerprinting our HTTP/2 SETTINGS frame,
        // which differs from Chrome's even when every header matches.
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    fun espnGet(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ESPN_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-GB,en;q=0.9")
            // Deliberately NOT setting Accept-Encoding: OkHttp adds "gzip" itself and transparently
            // decompresses the response only when the app hasn't set that header — set it manually
            // and you get the raw compressed bytes back from body.string() instead (this bit us once).
            .header("Referer", "https://www.espn.com/")
            .header("Origin", "https://www.espn.com")
            .header("sec-ch-ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"")
            .header("sec-ch-ua-mobile", "?1")
            .header("sec-ch-ua-platform", "\"Android\"")
            .header("sec-fetch-dest", "empty")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-site", "same-site")
            .build()
        return espnClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            // Quiet on success; log enough to diagnose on a block/error without spamming
            // logcat on every normal poll (this endpoint gets hit frequently).
            if (!resp.isSuccessful || !body.trimStart().startsWith("{")) {
                android.util.Log.w(
                    "EspnApi",
                    "bad response ${resp.code} ct=${resp.header("content-type")} server=${resp.header("server")} " +
                        "from $url body=${body.take(300)}"
                )
            }
            body
        }
    }

    data class SelectedGame(
        val id: String,
        val leagueId: String,
        val homeTeam: String,
        val awayTeam: String,
        // ESPN sport path segment ("soccer", "football") — needed to rebuild the correct
        // scoreboard URL when re-polling this game's score (see PlayerActivity/TvModeActivity
        // fetchTickerScores()). Defaults to "soccer" so games pinned before this field existed
        // still parse from saved prefs.
        val sportPath: String = "soccer",
        // Local calendar date (yyyyMMdd) this game was pinned on — lets pruneStale() clear a
        // pin left ticked from a previous day. Defaults to today so pins saved before this field
        // existed aren't wrongly treated as stale the first time they're read back.
        val pinnedDay: String = todayKey()
    )

    data class LiveScore(
        val gameId: String,
        val homeTeam: String,
        val awayTeam: String,
        val homeScore: String,
        val awayScore: String,
        val state: String,
        val detail: String,
        val note: String = ""
    )

    data class SportFeed(
        val id: String,
        val name: String,
        val emoji: String,
        val rssUrl: String
    )

    val SPORT_FEEDS = listOf(
        SportFeed("football",  "Football",  "⚽", "https://feeds.bbci.co.uk/sport/football/rss.xml"),
        SportFeed("cricket",   "Cricket",   "🏏", "https://feeds.bbci.co.uk/sport/cricket/rss.xml"),
        SportFeed("boxing",    "Boxing",    "🥊", "https://feeds.bbci.co.uk/sport/boxing/rss.xml"),
        SportFeed("golf",      "Golf",      "⛳", "https://feeds.bbci.co.uk/sport/golf/rss.xml"),
        SportFeed("nfl",       "NFL",       "🏈", "https://feeds.bbci.co.uk/sport/american-football/rss.xml"),
        SportFeed("f1",        "Formula 1", "🏎", "https://feeds.bbci.co.uk/sport/formula1/rss.xml")
    )

    var tickerEnabled = false
    var liveScores: List<LiveScore> = emptyList()

    var newsTickerEnabled = false
    // keyed by SportFeed.id, preserves insertion order for display
    var sportHeadlines: LinkedHashMap<String, List<String>> = LinkedHashMap()

    private const val PREF = "ticker_prefs"
    private const val KEY_GAMES        = "selected_games"
    private const val KEY_SPORT_IDS    = "selected_sport_ids"

    private fun todayKey(): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())

    fun getSelected(context: Context): List<SelectedGame> {
        val json = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_GAMES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SelectedGame(o.optString("id"), o.optString("leagueId"),
                    o.optString("homeTeam"), o.optString("awayTeam"),
                    o.optString("sportPath", "soccer"),
                    o.optString("pinnedDay", todayKey()))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun isSelected(context: Context, gameId: String) =
        getSelected(context).any { it.id == gameId }

    fun toggle(context: Context, game: SelectedGame) {
        val list = getSelected(context).toMutableList()
        val idx = list.indexOfFirst { it.id == game.id }
        if (idx >= 0) list.removeAt(idx) else list.add(game)
        save(context, list)
    }

    /** Un-pins [gameId] once its match has finished — a finished game no longer needs tracking. */
    fun unpin(context: Context, gameId: String) {
        val list = getSelected(context)
        val kept = list.filter { it.id != gameId }
        if (kept.size != list.size) save(context, kept)
    }

    /** Un-pins any of [scores] that have reached full-time. */
    fun pruneFinished(context: Context, scores: List<LiveScore>) {
        val finishedIds = scores.filter { it.state == "post" }.map { it.gameId }.toSet()
        if (finishedIds.isEmpty()) return
        val list = getSelected(context)
        val kept = list.filter { it.id !in finishedIds }
        if (kept.size != list.size) save(context, kept)
    }

    /**
     * Clears any pin left over from a previous day — called once on app start so a game ticked
     * yesterday (that never got auto-unpinned, e.g. the app wasn't open when it finished) doesn't
     * silently keep polling/showing forever.
     */
    fun pruneStale(context: Context) {
        val today = todayKey()
        val list = getSelected(context)
        val kept = list.filter { it.pinnedDay == today }
        if (kept.size != list.size) save(context, kept)
    }

    private fun save(context: Context, games: List<SelectedGame>) {
        val arr = JSONArray()
        games.forEach { g ->
            arr.put(JSONObject().apply {
                put("id", g.id); put("leagueId", g.leagueId)
                put("homeTeam", g.homeTeam); put("awayTeam", g.awayTeam)
                put("sportPath", g.sportPath); put("pinnedDay", g.pinnedDay)
            })
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_GAMES, arr.toString()).apply()
    }

    // ── Sport selection persistence ──────────────────────────────────────────

    fun getSelectedSportIds(context: Context): Set<String> {
        val saved = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_SPORT_IDS, null)
        return if (saved == null) {
            // Default: football selected
            setOf("football")
        } else {
            saved.split(",").filter { it.isNotBlank() }.toSet()
        }
    }

    fun setSelectedSportIds(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_SPORT_IDS, ids.joinToString(",")).apply()
    }

    fun getSelectedSports(context: Context): List<SportFeed> {
        val ids = getSelectedSportIds(context)
        return SPORT_FEEDS.filter { it.id in ids }
    }

    // ── News text builder ─────────────────────────────────────────────────────

    fun buildNewsText(): String {
        if (sportHeadlines.isEmpty()) return "  LOADING SPORTS NEWS...  "
        val sb = StringBuilder("  ")
        var first = true
        SPORT_FEEDS.forEach { feed ->
            val headlines = sportHeadlines[feed.id]
            if (headlines.isNullOrEmpty()) return@forEach
            if (!first) sb.append("               ")
            sb.append("${feed.emoji} ${feed.name.uppercase()}  ▸  ")
            sb.append(headlines.joinToString("   ●   "))
            first = false
        }
        if (first) return "  LOADING SPORTS NEWS...  "
        sb.append("  ")
        return sb.toString()
    }

    fun buildTickerText(): String {
        if (liveScores.isEmpty()) return "  NO SCORES — SELECT GAMES IN INTERACTIVE › SPORTS  "
        return liveScores.joinToString("          ·          ") { s ->
            when (s.state) {
                "in"   -> "● ${s.homeTeam}  ${s.homeScore} – ${s.awayScore}  ${s.awayTeam}  ${s.detail}"
                "post" -> {
                    val ft = if (s.note.isNotBlank()) "FT  (${s.note})" else "FT"
                    "${s.homeTeam}  ${s.homeScore} – ${s.awayScore}  ${s.awayTeam}  $ft"
                }
                else   -> "${s.homeTeam}  vs  ${s.awayTeam}  ${s.detail}"
            }
        }
    }
}
