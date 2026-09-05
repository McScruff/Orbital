package com.orbital.iptv.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Polls ESPN for goals in whatever games the user has pinned in Interactive › Sports Bar (the
 * same selection [TickerManager] uses for the scores ticker) and fires [onGoal] whenever a new
 * goal, a VAR-disallowed goal, kick-off, or full-time occurs — see [FlashType]. Kick-off/full-time
 * are detected from the same per-league scoreboard state ("pre"/"in"/"post") already being
 * tracked to decide which games need the heavier per-event call, so they add no extra traffic.
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

    enum class FlashType { GOAL, DISALLOWED, KICK_OFF, FULL_TIME }

    data class GoalEvent(
        val gameLabel: String,
        val scoreLabel: String,
        val detailLabel: String,
        val type: FlashType = FlashType.GOAL
    )

    // In-memory toggle — mirrors TickerManager.tickerEnabled/newsTickerEnabled (resets each
    // app launch, matching the existing HUD button convention).
    var enabled = false

    // Whether any pinned game is currently live — callers use this to back off their poll
    // interval when nothing is actually in play.
    var hasLiveGames = false
        private set

    var onGoal: ((GoalEvent) -> Unit)? = null

    const val DURATION_SECONDS = 5

    /**
     * Builds the sample [GoalEvent] for the ACTION_DEBUG_GOAL_FLASH broadcast (see PlayerActivity/
     * TvModeActivity) — a manual on-screen preview of any flash type without waiting for a real
     * game. `--es type goal|disallowed|kickoff|fulltime` selects the type (default "goal"); the
     * older `--ez disallowed true` flag is still honoured for the disallowed case.
     */
    fun debugSample(intent: android.content.Intent?): GoalEvent {
        val typeExtra = intent?.getStringExtra("type")
        val type = when {
            typeExtra == "kickoff"  -> FlashType.KICK_OFF
            typeExtra == "fulltime" -> FlashType.FULL_TIME
            typeExtra == "disallowed" || (intent?.getBooleanExtra("disallowed", false) == true) -> FlashType.DISALLOWED
            else -> FlashType.GOAL
        }
        return when (type) {
            FlashType.KICK_OFF  -> GoalEvent("Newcastle Utd vs Liverpool", "", "", type)
            FlashType.FULL_TIME -> GoalEvent("Newcastle Utd vs Liverpool", "Newcastle Utd 2 – 2 Liverpool", "", type)
            FlashType.DISALLOWED -> GoalEvent("Newcastle Utd vs Liverpool", "", "J. Willock  57'  —  offside", type)
            FlashType.GOAL -> GoalEvent("Newcastle Utd vs Liverpool", "Newcastle Utd 2 – 2 Liverpool", "J. Willock  57'", type)
        }
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
            val wasState = lastState[game.id]
            val wasLive = wasState == "in"
            // wasState == null means this is the first time this game's been observed at all —
            // excluding it (like [baselined] does for goals) stops Goal Flash from firing a
            // retroactive "KICK-OFF" for a game that was already well underway when it was turned on.
            val justKickedOff = state == "in" && wasState == "pre"
            val justFinished  = state == "post" && wasLive
            lastState[game.id] = state
            if (state == "in") anyLive = true

            // Only the heavier per-game feed for a currently-live game, or the one poll right
            // after it finishes (to catch a stoppage-time goal/VAR call) — never for a game
            // that's merely scheduled.
            if (state != "in" && !justFinished) continue
            try {
                val json = TickerManager.espnGet(
                    "https://site.api.espn.com/apis/site/v2/sports/${game.sportPath}/${game.leagueId}/summary?event=${game.id}"
                )
                processGame(game, json, justKickedOff, justFinished)
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

    private fun processGame(
        game: TickerManager.SelectedGame, json: String,
        justKickedOff: Boolean = false, justFinished: Boolean = false
    ) {
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

        // Score is always 0-0 at this instant, so it's omitted as uninformative (same reasoning
        // as disallowed goals below, which also skip the score line).
        if (justKickedOff) onGoal?.invoke(GoalEvent(gameLabel, "", "", FlashType.KICK_OFF))

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

                onGoal?.invoke(GoalEvent(gameLabel, scoreLabel, detail, FlashType.GOAL))
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
                onGoal?.invoke(GoalEvent(gameLabel, "", detail, FlashType.DISALLOWED))
            }
        }

        if (justFinished) onGoal?.invoke(GoalEvent(gameLabel, scoreLabel, "", FlashType.FULL_TIME))

        baselined.add(game.id)
    }
}
