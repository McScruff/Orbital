package com.skyretro.iptv.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object TickerManager {

    data class SelectedGame(
        val id: String,
        val leagueId: String,
        val homeTeam: String,
        val awayTeam: String
    )

    data class LiveScore(
        val gameId: String,
        val homeTeam: String,
        val awayTeam: String,
        val homeScore: String,
        val awayScore: String,
        val state: String,
        val detail: String
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

    fun getSelected(context: Context): List<SelectedGame> {
        val json = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_GAMES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SelectedGame(o.optString("id"), o.optString("leagueId"),
                    o.optString("homeTeam"), o.optString("awayTeam"))
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

    private fun save(context: Context, games: List<SelectedGame>) {
        val arr = JSONArray()
        games.forEach { g ->
            arr.put(JSONObject().apply {
                put("id", g.id); put("leagueId", g.leagueId)
                put("homeTeam", g.homeTeam); put("awayTeam", g.awayTeam)
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
                "post" -> "${s.homeTeam}  ${s.homeScore} – ${s.awayScore}  ${s.awayTeam}  FT"
                else   -> "${s.homeTeam}  vs  ${s.awayTeam}  ${s.detail}"
            }
        }
    }
}
