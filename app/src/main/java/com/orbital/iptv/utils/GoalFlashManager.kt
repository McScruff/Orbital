package com.orbital.iptv.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Polls ESPN for goals in whatever games the user has pinned in Interactive › Sports Bar (the
 * same selection [TickerManager] uses for the scores ticker) and fires [onGoal] whenever a new
 * goal — or a VAR-disallowed goal — appears.
 *
 * Traffic is kept deliberately light: a cheap per-league scoreboard call (one call covers every
 * pinned game in that league, and is the same call the SCORES ticker already makes) decides
 * which pinned games are actually live right now; only THOSE get the heavier per-event summary
 * call that carries scorer/minute/commentary detail. A finished game gets exactly one more
 * summary call (to catch a stoppage-time goal/VAR call) and is then left alone. This matters
 * because ESPN's endpoint is undocumented and has no published quota — hammering it (e.g. one
 * summary call per pinned game every poll, live or not) is exactly the traffic pattern that
 * trips its bot-detection.
 *
 * Kept independent of [TickerManager]'s own polling: Goal Flash is a separate on/off toggle in
 * the player HUD, so it must work whether or not the SCORES ticker is also running.
 */
object GoalFlashManager {

    data class GoalEvent(
        val gameLabel: String,
        val scoreLabel: String,
        val detailLabel: String,
        val disallowed: Boolean = false
    )

    // In-memory toggle — mirrors TickerManager.tickerEnabled/newsTickerEnabled (resets each
    // app launch, matching the existing HUD button convention).
    var enabled = false

    // Whether any pinned game is currently live — callers use this to back off their poll
    // interval when nothing is actually in play.
    var hasLiveGames = false
        private set

    var onGoal: ((GoalEvent) -> Unit)? = null

    private const val PREF          = "goal_flash_prefs"
    private const val KEY_DURATION  = "duration_seconds"
    private const val DEFAULT_DURATION_SEC = 5

    fun getDurationSeconds(context: Context): Int =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_DURATION, DEFAULT_DURATION_SEC)

    fun setDurationSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_DURATION, seconds).apply()
    }

    // Per-game de-dupe state, keyed by ESPN event id.
    private val seenGoalIds        = mutableMapOf<String, MutableSet<String>>()
    private val seenDisallowedSeqs = mutableMapOf<String, MutableSet<Int>>()
    // Games we've polled at least once — their first poll only baselines already-seen events
    // (so turning Goal Flash on mid-match doesn't retroactively flash every earlier goal).
    private val baselined = mutableSetOf<String>()
    // Last state seen per game ("pre"/"in"/"post") — used to catch the one final poll right
    // after a live game ends, then stop polling it for good.
    private val lastState = mutableMapOf<String, String>()
    private val done = mutableSetOf<String>()

    suspend fun poll(context: Context) = withContext(Dispatchers.IO) {
        if (!enabled) { hasLiveGames = false; return@withContext }
        val selected = TickerManager.getSelected(context).filter { it.id !in done }
        if (selected.isEmpty()) { hasLiveGames = false; return@withContext }
        pruneTo(selected.map { it.id }.toSet())

        // One cheap scoreboard call per distinct league covers every pinned game in it — this
        // is the only way to learn a game's state without hitting the heavier per-event endpoint.
        val statesById = mutableMapOf<String, String>()
        selected.groupBy { it.sportPath to it.leagueId }.forEach { (key, _) ->
            val (sportPath, leagueId) = key
            try {
                val json = TickerManager.espnGet("https://site.api.espn.com/apis/site/v2/sports/$sportPath/$leagueId/scoreboard")
                val events = JSONObject(json).optJSONArray("events") ?: return@forEach
                for (i in 0 until events.length()) {
                    val ev = events.getJSONObject(i)
                    val id = ev.optString("id")
                    val state = ev.optJSONArray("competitions")?.optJSONObject(0)
                        ?.optJSONObject("status")?.optJSONObject("type")?.optString("state") ?: continue
                    statesById[id] = state
                }
            } catch (_: Exception) {}
        }

        var anyLive = false
        for (game in selected) {
            val state = statesById[game.id] ?: continue
            val wasLive = lastState[game.id] == "in"
            lastState[game.id] = state
            if (state == "in") anyLive = true

            // Only the heavier per-game feed for a currently-live game, or the one poll right
            // after it finishes (to catch a stoppage-time goal/VAR call) — never for a game
            // that's merely scheduled.
            if (state != "in" && !(state == "post" && wasLive)) continue
            try {
                val json = TickerManager.espnGet(
                    "https://site.api.espn.com/apis/site/v2/sports/${game.sportPath}/${game.leagueId}/summary?event=${game.id}"
                )
                processGame(game, json)
            } catch (_: Exception) {}
            if (state == "post") done.add(game.id)
        }
        hasLiveGames = anyLive
    }

    /** Drop de-dupe/baseline/state tracking for games no longer pinned, so re-adding a game later starts fresh. */
    private fun pruneTo(selectedIds: Set<String>) {
        seenGoalIds.keys.retainAll(selectedIds)
        seenDisallowedSeqs.keys.retainAll(selectedIds)
        baselined.retainAll(selectedIds)
        lastState.keys.retainAll(selectedIds)
        done.retainAll(selectedIds)
    }

    private fun processGame(game: TickerManager.SelectedGame, json: String) {
        val root = JSONObject(json)
        val comp = root.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0) ?: return
        val competitors = comp.optJSONArray("competitors") ?: return

        var homeName = ""; var homeScore = ""
        var awayName = ""; var awayScore = ""
        for (i in 0 until competitors.length()) {
            val c = competitors.getJSONObject(i)
            val team = c.optJSONObject("team") ?: continue
            if (c.optString("homeAway") == "home") {
                homeName = team.optString("shortDisplayName").ifBlank { team.optString("displayName") }
                homeScore = c.optString("score")
            } else {
                awayName = team.optString("shortDisplayName").ifBlank { team.optString("displayName") }
                awayScore = c.optString("score")
            }
        }
        if (homeName.isBlank() || awayName.isBlank()) return
        val gameLabel  = "$homeName vs $awayName"
        val scoreLabel = "$homeName $homeScore – $awayScore $awayName"

        val firstPoll      = game.id !in baselined
        val goalSeen       = seenGoalIds.getOrPut(game.id) { mutableSetOf() }
        val disallowedSeen = seenDisallowedSeqs.getOrPut(game.id) { mutableSetOf() }

        root.optJSONArray("keyEvents")?.let { keyEvents ->
            for (i in 0 until keyEvents.length()) {
                val ev = keyEvents.getJSONObject(i)
                if (!ev.optBoolean("scoringPlay", false)) continue
                val id = ev.optString("id")
                if (id.isBlank() || !goalSeen.add(id)) continue
                if (firstPoll) continue

                val typeText = ev.optJSONObject("type")?.optString("text") ?: "Goal"
                val minute   = ev.optJSONObject("clock")?.optString("displayValue") ?: ""
                val scorer   = ev.optJSONArray("athletesInvolved")?.optJSONObject(0)
                    ?.optString("displayName")?.takeIf { it.isNotBlank() } ?: typeText

                val suffix = when (typeText) {
                    "Own Goal"           -> "  (OG)"
                    "Penalty - Scored"   -> "  (PEN)"
                    else                 -> ""
                }
                val detail = listOfNotNull(scorer, minute.takeIf { it.isNotBlank() })
                    .joinToString("  ") + suffix

                onGoal?.invoke(GoalEvent(gameLabel, scoreLabel, detail, disallowed = false))
            }
        }

        // Disallowed goals have no dedicated event type in ESPN's schema — the word
        // "disallowed" is the stable marker ESPN's commentary text uses whenever VAR
        // overturns a goal, so scan the natural-language commentary feed for it instead.
        root.optJSONArray("commentary")?.let { commentary ->
            for (i in 0 until commentary.length()) {
                val c = commentary.getJSONObject(i)
                val seq = c.optInt("sequence", -1)
                if (seq < 0 || !disallowedSeen.add(seq)) continue
                val text = c.optString("text")
                if (!text.contains("disallow", ignoreCase = true)) continue
                if (firstPoll) continue

                val minute = c.optJSONObject("time")?.optString("displayValue") ?: ""
                val detail = if (minute.isNotBlank()) "$minute — $text" else text
                onGoal?.invoke(GoalEvent(gameLabel, "", detail, disallowed = true))
            }
        }

        baselined.add(game.id)
    }
}
