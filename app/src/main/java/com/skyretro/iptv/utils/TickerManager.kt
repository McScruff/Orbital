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

    var tickerEnabled = false
    var liveScores: List<LiveScore> = emptyList()

    private const val PREF = "ticker_prefs"
    private const val KEY_GAMES = "selected_games"

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
