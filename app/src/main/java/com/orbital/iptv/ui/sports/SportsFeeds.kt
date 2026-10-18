package com.orbital.iptv.ui.sports

import com.orbital.iptv.ui.sports.SportsCatalog.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

// ── Row models ────────────────────────────────────────────────────────────────

data class MatchEvent(
    val id: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeLogoUrl: String,
    val awayLogoUrl: String,
    val homeScore: String,
    val awayScore: String,
    /** ESPN state: "pre", "in" or "post". */
    val statusState: String,
    val statusDetail: String,
    val note: String = "",
    /** What the status line says once it's over: "FT" for football, the round and time for a fight. */
    val finalLabel: String = "FT",
    val homeWinner: Boolean = false,
    val awayWinner: Boolean = false,
    /** A fight: home/away are fighters and the scores hold their records. */
    val isFight: Boolean = false,
    val startMs: Long = 0L
)

data class StandingEntry(
    val pos: Int,
    val team: String,
    val logoUrl: String,
    val played: Int,
    val won: Int,
    /** The third results column, which differs by sport (draws, ties, OT losses, games behind). */
    val drawn: String,
    val lost: Int,
    val gd: String,
    /** Points, or win percentage for US sports. */
    val points: String,
    val isGroupHeader: Boolean = false,
    val groupName: String = ""
)

/** One F1 session with its full classification, shown when the session row is opened. */
data class F1Session(val name: String, val eventName: String, val classification: List<String>)

sealed class SportsRow {
    data class Header(val title: String, val subtitle: String = "") : SportsRow()
    data class Match(val event: MatchEvent) : SportsRow()
    data class Standing(val entry: StandingEntry) : SportsRow()

    /** General-purpose row: boxing fights, F1 sessions and F1 standings. */
    data class Item(
        val id: String,
        val lead: String = "",
        val imageUrl: String = "",
        val title: String,
        val subtitle: String = "",
        val trail: String = "",
        val live: Boolean = false,
        val done: Boolean = false,
        val session: F1Session? = null,
        /** The two sides the channel finder searches the TV guide for. */
        val searchA: String = "",
        val searchB: String = "",
        val startMs: Long = 0L,
        /** The sport-tv-guide section for the channel finder, when it isn't the open sport's (Live Now). */
        val listingSlug: String = ""
    ) : SportsRow()
}

/** An entry in an event-based sport's season calendar (a UFC card, an F1 weekend). */
data class CalendarEvent(val id: String, val label: String, val startMs: Long, val endMs: Long)

// ── Fetching and parsing ──────────────────────────────────────────────────────

object SportsFeeds {

    // ── ESPN transport ────────────────────────────────────────────────────────
    // ESPN's CDN (Akamai) can 403 requests that don't look like a browser, and blocks the
    // primary host outright on some networks. espnGet() tries both hosts over HTTP/1.1 and
    // HTTP/2, and remembers whichever route last worked.

    private const val ESPN_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 9a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val clientH1 = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
    private val clientH2 = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .build()

    private const val PRIMARY_HOST = "site.api.espn.com"
    private const val ALT_HOST = "site.web.api.espn.com"
    private val routes = listOf(
        PRIMARY_HOST to clientH1, ALT_HOST to clientH1, PRIMARY_HOST to clientH2, ALT_HOST to clientH2
    )
    @Volatile private var preferredRoute = 0

    fun espnGet(url: String): String {
        var lastError: Exception? = null
        for (idx in routes.indices.sortedBy { if (it == preferredRoute) -1 else it }) {
            val (host, client) = routes[idx]
            try {
                return fetchJson(client, url.replace(PRIMARY_HOST, host), espnHeaders = true)
                    .also { preferredRoute = idx }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("ESPN request failed")
    }

    private fun fetchJson(client: OkHttpClient, url: String, espnHeaders: Boolean): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", ESPN_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-GB,en;q=0.9")
            // No Accept-Encoding: OkHttp only decompresses gzip itself when it added the header.
            .apply {
                if (espnHeaders) {
                    header("Referer", "https://www.espn.com/")
                    header("Origin", "https://www.espn.com")
                    header("sec-ch-ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"")
                    header("sec-ch-ua-mobile", "?1")
                    header("sec-ch-ua-platform", "\"Android\"")
                    header("sec-fetch-dest", "empty")
                    header("sec-fetch-mode", "cors")
                    header("sec-fetch-site", "same-site")
                }
            }
            .build()
        return client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            if (!body.trimStart().startsWith("{")) throw IOException("unexpected reply")
            body
        }
    }

    private fun espnBase(c: Category) = "https://site.api.espn.com/apis/site/v2/sports/${c.espnSport}/${c.espnLeague}"

    // ── Time formatting ───────────────────────────────────────────────────────

    private fun isoToMs(iso: String): Long {
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss")) {
            try {
                val f = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                return f.parse(iso)?.time ?: continue
            } catch (_: Exception) {}
        }
        return 0L
    }

    private fun fmt(pattern: String, ms: Long) = SimpleDateFormat(pattern, Locale.UK).format(Date(ms))
    fun timeOf(ms: Long) = if (ms > 0) fmt("HH:mm", ms) else ""
    fun dayTimeOf(ms: Long) = if (ms > 0) fmt("EEE d MMM · HH:mm", ms) else ""
    private fun ymd(cal: Calendar) = SimpleDateFormat("yyyyMMdd", Locale.US).format(cal.time)
    private fun ymdUtc(ms: Long) = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    // ── Team sports (football, NFL) ───────────────────────────────────────────

    // Scoreboards are cached briefly so stepping back and forth, and the match-day search, don't
    // refetch days they've just seen. Days that are over are kept longer than today.
    private val scoreboardCache = mutableMapOf<String, Pair<Long, List<SportsRow>>>()

    suspend fun teamScoreboard(c: Category, day: Calendar, fresh: Boolean = false): List<SportsRow> {
        val key = "${c.espnLeague}:${ymd(day)}"
        val isPast = ymd(day) < ymd(Calendar.getInstance())
        val maxAge = if (isPast) 30 * 60_000L else 60_000L
        if (!fresh) scoreboardCache[key]?.let { (at, rows) -> if (System.currentTimeMillis() - at < maxAge) return rows }
        val rows = withContext(Dispatchers.IO) {
            parseTeamEvents(JSONObject(espnGet("${espnBase(c)}/scoreboard?dates=${ymd(day)}")))
        }
        scoreboardCache[key] = System.currentTimeMillis() to rows
        return rows
    }

    private fun parseTeamEvents(json: JSONObject): List<SportsRow.Match> {
        val out = mutableListOf<SportsRow.Match>()
        val events = json.optJSONArray("events") ?: return out
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val comp = event.optJSONArray("competitions")?.optJSONObject(0) ?: continue
            val competitors = comp.optJSONArray("competitors") ?: continue
            val statusType = comp.optJSONObject("status")?.optJSONObject("type") ?: continue
            val state = statusType.optString("state", "pre")
            val startMs = isoToMs(event.optString("date"))

            val statusDesc = statusType.optString("description", "")
            val note = firstNote(comp) ?: when {
                statusDesc.contains("penalt", true) -> "AET (Pens)"
                statusDesc.contains("aet", true) || statusDesc.contains("extra time", true) -> "AET"
                else -> ""
            }
            var home: JSONObject? = null
            var away: JSONObject? = null
            for (j in 0 until competitors.length()) {
                val cj = competitors.getJSONObject(j)
                if (cj.optString("homeAway") == "home") home = cj else away = cj
            }
            val ht = home?.optJSONObject("team") ?: continue
            val at = away?.optJSONObject("team") ?: continue
            out += SportsRow.Match(MatchEvent(
                id = event.optString("id", "$i"),
                homeTeam = ht.optString("shortDisplayName").ifEmpty { ht.optString("displayName") },
                awayTeam = at.optString("shortDisplayName").ifEmpty { at.optString("displayName") },
                homeLogoUrl = teamLogo(ht),
                awayLogoUrl = teamLogo(at),
                homeScore = home.optString("score", ""),
                awayScore = away.optString("score", ""),
                statusState = state,
                statusDetail = if (state == "pre") timeOf(startMs) else statusType.optString("shortDetail", ""),
                note = note,
                startMs = startMs
            ))
        }
        return out
    }

    // ── Match days (football) ─────────────────────────────────────────────────

    private val matchDayCache = mutableMapOf<String, List<String>>()

    /**
     * League match days (yyyyMMdd) from the scoreboard calendar, or empty for cup competitions,
     * whose calendar only lists stages ("League Phase", "Rd of 16"...).
     */
    private suspend fun matchDays(c: Category): List<String> {
        matchDayCache[c.espnLeague]?.let { return it }
        val days = withContext(Dispatchers.IO) {
            val cal = JSONObject(espnGet("${espnBase(c)}/scoreboard"))
                .optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("calendar") ?: JSONArray()
            (0 until cal.length()).mapNotNull { i ->
                (cal.opt(i) as? String)?.take(10)?.replace("-", "")?.takeIf { it.length == 8 }
            }.sorted()
        }
        matchDayCache[c.espnLeague] = days
        return days
    }

    /**
     * The nearest day with fixtures from [from] in direction [dir] (+1/-1), counting [from] itself
     * when [includeFrom]. Leagues answer from their calendar; cups are probed a week at a time,
     * up to ~9 weeks out. Null if nothing is found.
     */
    suspend fun findMatchDay(c: Category, from: Calendar, dir: Int, includeFrom: Boolean): Calendar? {
        val start = (from.clone() as Calendar).apply { if (!includeFrom) add(Calendar.DAY_OF_YEAR, dir) }
        val days = matchDays(c)
        if (days.isNotEmpty()) {
            val key = ymd(start)
            val hit = if (dir > 0) days.firstOrNull { it >= key } else days.lastOrNull { it <= key }
            return hit?.let { SimpleDateFormat("yyyyMMdd", Locale.US).parse(it) }
                ?.let { d -> Calendar.getInstance().apply { time = d } }
        }
        for (week in 0 until 9) {
            val batch = (0 until 7).map { n ->
                (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, dir * (week * 7 + n)) }
            }
            val found = coroutineScope {
                batch.map { d -> async { d to (try { teamScoreboard(c, d) } catch (_: Exception) { emptyList() }) } }.awaitAll()
            }.firstOrNull { it.second.isNotEmpty() }
            if (found != null) return found.first
        }
        return null
    }

    // ── NFL weeks ─────────────────────────────────────────────────────────────

    private val weekCache = mutableMapOf<String, List<CalendarEvent>>()

    /** Preseason, regular season and postseason weeks; the id is "seasontype:week". */
    suspend fun weeks(c: Category): List<CalendarEvent> {
        weekCache[c.espnLeague]?.let { return it }
        val out = withContext(Dispatchers.IO) {
            val cal = JSONObject(espnGet("${espnBase(c)}/scoreboard"))
                .optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("calendar") ?: JSONArray()
            (0 until cal.length()).flatMap { i ->
                val stage = cal.optJSONObject(i) ?: return@flatMap emptyList()
                val type = stage.optString("value")
                val entries = stage.optJSONArray("entries") ?: return@flatMap emptyList()
                (0 until entries.length()).mapNotNull { j ->
                    val e = entries.optJSONObject(j) ?: return@mapNotNull null
                    val label = e.optString("label")
                    CalendarEvent(
                        "$type:${e.optString("value")}",
                        if (type == "1" && !label.contains("Preseason", true)) "Preseason $label" else label,
                        isoToMs(e.optString("startDate")), isoToMs(e.optString("endDate"))
                    )
                }
            }.sortedBy { it.startMs }
        }
        if (out.isNotEmpty()) weekCache[c.espnLeague] = out
        return out
    }

    /** One NFL week's games, under a header per day. */
    suspend fun week(c: Category, ev: CalendarEvent): List<SportsRow> {
        val (type, week) = ev.id.split(":").let { it[0] to it.getOrElse(1) { "1" } }
        val games = withContext(Dispatchers.IO) {
            parseTeamEvents(JSONObject(espnGet("${espnBase(c)}/scoreboard?seasontype=$type&week=$week")))
        }.sortedBy { it.event.startMs }
        val out = mutableListOf<SportsRow>()
        games.groupBy { fmt("EEEE d MMMM", it.event.startMs) }.forEach { (dayName, rows) ->
            out += SportsRow.Header(dayName, "${rows.size} ${if (rows.size == 1) "game" else "games"}")
            out += rows
        }
        return out
    }

    private fun firstNote(comp: JSONObject): String? {
        val notes = comp.optJSONArray("notes") ?: return null
        return (0 until notes.length())
            .mapNotNull { notes.optJSONObject(it)?.optString("headline")?.takeIf { h -> h.isNotBlank() } }
            .firstOrNull()
    }

    private fun teamLogo(team: JSONObject): String =
        team.optString("logo").takeIf { it.isNotEmpty() }
            ?: team.optJSONArray("logos")?.optJSONObject(0)?.optString("href")
            ?: ""

    /**
     * ESPN standings: {"children":[{"name":..., "standings":{"entries":[...]}}]}. One child for a
     * single table; one per group/conference otherwise. Which stats fill the last columns comes
     * from the category's [SportsCatalog.TableCols]. ESPN doesn't always list teams in table
     * order (NBA comes alphabetically), so rows are sorted by rank or playoff seed when every
     * team has one, and otherwise kept in ESPN's order (which tie-breaks correctly).
     */
    suspend fun teamStandings(c: Category): List<SportsRow> = withContext(Dispatchers.IO) {
        val json = JSONObject(espnGet("https://site.api.espn.com/apis/v2/sports/${c.espnSport}/${c.espnLeague}/standings"))
        val cols = c.table
        val out = mutableListOf<SportsRow>()
        fun first(stats: JSONArray?, names: List<String>) = names.firstNotNullOfOrNull { stat(stats, it) }
        fun addEntries(entries: JSONArray) {
            val list = (0 until entries.length()).map { entries.getJSONObject(it) }
            fun num(e: JSONObject, names: List<String>) = first(e.optJSONArray("stats"), names)?.trim()?.removePrefix("+")?.toDoubleOrNull()
            val ranked = list.all { num(it, listOf("rank", "playoffSeed")) != null }
            val sorted = if (ranked) list.sortedBy { num(it, listOf("rank", "playoffSeed")) } else list
            sorted.forEachIndexed { i, entry ->
                val team = entry.optJSONObject("team") ?: return@forEachIndexed
                val stats = entry.optJSONArray("stats")
                val wins = first(stats, listOf("wins", "gamesWon"))?.toIntOrNull() ?: 0
                val losses = first(stats, listOf("losses", "gamesLost"))?.toIntOrNull() ?: 0
                val ties = first(stats, listOf("ties", "gamesDrawn"))?.toIntOrNull() ?: 0
                out += SportsRow.Standing(StandingEntry(
                    pos = i + 1,
                    team = team.optString("shortDisplayName").ifEmpty { team.optString("displayName") },
                    logoUrl = team.optJSONArray("logos")?.optJSONObject(0)?.optString("href") ?: "",
                    played = stat(stats, "gamesPlayed")?.toIntOrNull() ?: (wins + losses + ties),
                    won = wins,
                    drawn = first(stats, cols.drawnStats) ?: "0",
                    lost = losses,
                    gd = first(stats, listOf("pointDifferential", "pointsDifference")) ?: "0",
                    points = first(stats, cols.ptsStats) ?: "$wins"
                ))
            }
        }
        val children = json.optJSONArray("children") ?: return@withContext out
        if (children.length() > 1) {
            for (g in 0 until children.length()) {
                val group = children.getJSONObject(g)
                out += SportsRow.Standing(StandingEntry(0, "", "", 0, 0, "", 0, "", "",
                    isGroupHeader = true, groupName = group.optString("name").ifEmpty { "Group ${'A' + g}" }))
                group.optJSONObject("standings")?.optJSONArray("entries")?.let { addEntries(it) }
            }
        } else {
            children.optJSONObject(0)?.optJSONObject("standings")?.optJSONArray("entries")?.let { addEntries(it) }
        }
        out
    }

    private fun stat(stats: JSONArray?, name: String): String? {
        if (stats == null) return null
        for (i in 0 until stats.length()) {
            val s = stats.optJSONObject(i) ?: continue
            if (s.optString("name") == name) return s.optString("displayValue")
        }
        return null
    }

    // ── Event-based sports (UFC, F1) ──────────────────────────────────────────

    private val calendars = mutableMapOf<String, List<CalendarEvent>>()

    /** The season's events from the scoreboard's league calendar. Cached for the app session. */
    suspend fun calendar(c: Category): List<CalendarEvent> {
        calendars[c.espnLeague]?.let { return it }
        return withContext(Dispatchers.IO) {
            val json = JSONObject(espnGet("${espnBase(c)}/scoreboard"))
            val cal = json.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("calendar") ?: JSONArray()
            val out = (0 until cal.length()).mapNotNull { i ->
                val e = cal.optJSONObject(i) ?: return@mapNotNull null
                val id = Regex("/events/(\\d+)").find(e.optJSONObject("event")?.optString("\$ref").orEmpty())
                    ?.groupValues?.get(1) ?: return@mapNotNull null
                CalendarEvent(id, e.optString("label"), isoToMs(e.optString("startDate")), isoToMs(e.optString("endDate")))
            }.sortedBy { it.startMs }
            if (out.isNotEmpty()) calendars[c.espnLeague] = out
            out
        }
    }

    /**
     * Index of the next event not yet finished, or the last one once the season is over. [graceMs]
     * keeps an event current after its listed end (golf lists the final day's midnight as its end).
     */
    fun currentIndex(cal: List<CalendarEvent>, graceMs: Long = 6 * 3_600_000L): Int {
        val now = System.currentTimeMillis()
        val next = cal.indexOfFirst { it.endMs + graceMs >= now }
        return if (next >= 0) next else cal.lastIndex
    }

    private suspend fun eventJson(c: Category, ev: CalendarEvent): JSONObject? = withContext(Dispatchers.IO) {
        // A range around the event's dates, then pick it out by id: ESPN buckets dates in US time.
        val from = ymdUtc(ev.startMs - 86_400_000L)
        val to = ymdUtc(maxOf(ev.endMs, ev.startMs) + 86_400_000L)
        val events = JSONObject(espnGet("${espnBase(c)}/scoreboard?dates=$from-$to")).optJSONArray("events")
            ?: return@withContext null
        (0 until events.length()).map { events.getJSONObject(it) }.let { list ->
            list.firstOrNull { it.optString("id") == ev.id } ?: list.firstOrNull()
        }
    }

    /** A UFC card, main event first, split into main card and prelims by start time. */
    suspend fun ufcCard(c: Category, ev: CalendarEvent): List<SportsRow> {
        val event = eventJson(c, ev) ?: return emptyList()
        val comps = event.optJSONArray("competitions") ?: return emptyList()
        val bouts = (0 until comps.length()).mapNotNull { i ->
            val comp = comps.getJSONObject(i)
            val fighters = comp.optJSONArray("competitors") ?: return@mapNotNull null
            if (fighters.length() < 2) return@mapNotNull null
            val a = fighters.getJSONObject(0)
            val b = fighters.getJSONObject(1)
            val status = comp.optJSONObject("status")
            val type = status?.optJSONObject("type")
            val state = type?.optString("state", "pre") ?: "pre"
            val startMs = isoToMs(comp.optString("date"))
            val round = status?.optInt("period", 0) ?: 0
            val clock = status?.optString("displayClock").orEmpty()
            MatchEvent(
                id = comp.optString("id", "$i"),
                homeTeam = fighterName(a), awayTeam = fighterName(b),
                homeLogoUrl = a.optJSONObject("athlete")?.optJSONObject("flag")?.optString("href").orEmpty(),
                awayLogoUrl = b.optJSONObject("athlete")?.optJSONObject("flag")?.optString("href").orEmpty(),
                homeScore = record(a), awayScore = record(b),
                statusState = state,
                statusDetail = if (state == "pre") timeOf(startMs) else type?.optString("shortDetail").orEmpty(),
                note = comp.optJSONObject("type")?.optString("abbreviation").orEmpty(),
                finalLabel = if (round > 0) "R$round $clock" else "Final",
                homeWinner = a.optBoolean("winner"), awayWinner = b.optBoolean("winner"),
                isFight = true,
                startMs = startMs
            )
        }
        // ESPN lists the card in running order; the main card starts last.
        val slots = bouts.map { it.startMs }.distinct().sortedDescending()
        val out = mutableListOf<SportsRow>()
        slots.forEachIndexed { i, slot ->
            val group = bouts.filter { it.startMs == slot }.reversed()
            val title = when {
                slots.size == 1 -> "Fight card"
                i == 0 -> "Main card"
                i == 1 -> "Prelims"
                else -> "Early prelims"
            }
            out += SportsRow.Header(title, "${dayTimeOf(slot)}  ·  ${group.size} bouts")
            group.forEach { out += SportsRow.Match(it) }
        }
        return out
    }

    private fun fighterName(c: JSONObject): String {
        val a = c.optJSONObject("athlete") ?: return "TBA"
        return a.optString("displayName").ifEmpty { a.optString("fullName", "TBA") }
    }

    private fun record(c: JSONObject): String =
        c.optJSONArray("records")?.optJSONObject(0)?.optString("summary").orEmpty()

    /** An F1 weekend: one row per session, race first. */
    suspend fun f1Weekend(c: Category, ev: CalendarEvent): List<SportsRow> {
        val event = eventJson(c, ev) ?: return emptyList()
        val eventName = event.optString("name", ev.label)
        val circuit = event.optJSONObject("circuit")
        val place = listOfNotNull(
            circuit?.optString("fullName")?.takeIf { it.isNotBlank() },
            circuit?.optJSONObject("address")?.optString("country")?.takeIf { it.isNotBlank() }
        ).joinToString(", ")
        val country = circuit?.optJSONObject("address")?.optString("country").orEmpty()
        val comps = event.optJSONArray("competitions") ?: return emptyList()
        val sessions = (0 until comps.length()).map { i ->
            val comp = comps.getJSONObject(i)
            val abbr = comp.optJSONObject("type")?.optString("abbreviation").orEmpty()
            val name = sessionName(abbr)
            val type = comp.optJSONObject("status")?.optJSONObject("type")
            val state = type?.optString("state", "pre") ?: "pre"
            val startMs = isoToMs(comp.optString("date"))
            val drivers = comp.optJSONArray("competitors") ?: JSONArray()
            val order = (0 until drivers.length()).map { drivers.getJSONObject(it) }
                .sortedBy { it.optInt("order", 999) }
                .map { it.optJSONObject("athlete")?.optString("displayName").orEmpty() }
                .filter { it.isNotBlank() }
            val podium = order.take(3).mapIndexed { p, n -> "${p + 1}. ${n.substringAfterLast(' ')}" }.joinToString("   ")
            SportsRow.Item(
                id = comp.optString("id", "$i"),
                lead = sessionTag(abbr),
                title = name,
                subtitle = if (state == "pre" || order.isEmpty()) fmt("EEEE d MMM", startMs) else podium,
                trail = when (state) {
                    "in" -> "LIVE"
                    "post" -> "Final"
                    else -> timeOf(startMs)
                },
                live = state == "in",
                done = state == "post",
                session = if (order.isNotEmpty()) F1Session(name, eventName,
                    order.mapIndexed { p, n -> "${p + 1}.  $n" }) else null,
                searchA = country.ifEmpty { eventName },
                searchB = "Formula F1 Grand Prix",
                startMs = startMs
            )
        }.sortedByDescending { it.startMs }
        return listOf(SportsRow.Header(eventName, place)) + sessions
    }

    private fun sessionTag(abbr: String): String = when (abbr.lowercase()) {
        "qual" -> "Q"
        "race" -> "R"
        "sprint", "spr" -> "S"
        "sq", "sprint qual", "sprintqual" -> "SQ"
        else -> abbr.uppercase().take(3)
    }

    private fun sessionName(abbr: String): String = when (abbr.lowercase()) {
        "fp1" -> "Practice 1"
        "fp2" -> "Practice 2"
        "fp3" -> "Practice 3"
        "qual" -> "Qualifying"
        "race" -> "Race"
        "sprint", "spr" -> "Sprint"
        "sq", "sprint qual", "sprintqual" -> "Sprint Qualifying"
        else -> abbr.ifEmpty { "Session" }
    }

    suspend fun f1Standings(c: Category, drivers: Boolean): List<SportsRow> = withContext(Dispatchers.IO) {
        val json = JSONObject(espnGet("https://site.api.espn.com/apis/v2/sports/${c.espnSport}/${c.espnLeague}/standings"))
        val children = json.optJSONArray("children") ?: return@withContext emptyList()
        val table = (0 until children.length()).map { children.getJSONObject(it) }
            .firstOrNull { it.optString("name").contains(if (drivers) "Driver" else "Constructor", true) }
            ?: return@withContext emptyList()
        val entries = table.optJSONObject("standings")?.optJSONArray("entries") ?: return@withContext emptyList()
        (0 until entries.length()).map { i ->
            val e = entries.getJSONObject(i)
            val who = e.optJSONObject(if (drivers) "athlete" else "team") ?: JSONObject()
            val stats = e.optJSONArray("stats")
            SportsRow.Item(
                id = who.optString("id", "$i"),
                lead = stat(stats, "rank") ?: "${i + 1}",
                imageUrl = who.optJSONObject("flag")?.optString("href").orEmpty(),
                title = who.optString("displayName"),
                subtitle = if (drivers) who.optJSONObject("flag")?.optString("alt").orEmpty() else "",
                trail = "${(stat(stats, "championshipPts") ?: stat(stats, "points") ?: "0").trim()} pts"
            )
        }
    }

    // ── Tennis ────────────────────────────────────────────────────────────────
    // A dated scoreboard lists the tournaments running that day, each with every match of the
    // tournament; singles matches are picked out for the local [day], under a header per event.

    suspend fun tennisDay(c: Category, day: Calendar, fresh: Boolean = false): List<SportsRow> {
        val key = "tennis:${c.espnLeague}:${ymd(day)}"
        val maxAge = if (ymd(day) < ymd(Calendar.getInstance())) 30 * 60_000L else 60_000L
        if (!fresh) scoreboardCache[key]?.let { (at, rows) -> if (System.currentTimeMillis() - at < maxAge) return rows }
        val rows = withContext(Dispatchers.IO) {
            val events = JSONObject(espnGet("${espnBase(c)}/scoreboard?dates=${ymd(day)}")).optJSONArray("events") ?: JSONArray()
            val out = mutableListOf<SportsRow>()
            for (i in 0 until events.length()) {
                val ev = events.getJSONObject(i)
                val matches = mutableListOf<MatchEvent>()
                val groupings = ev.optJSONArray("groupings") ?: continue
                for (g in 0 until groupings.length()) {
                    val grouping = groupings.getJSONObject(g)
                    if (!grouping.optJSONObject("grouping")?.optString("slug").orEmpty().contains("singles")) continue
                    val comps = grouping.optJSONArray("competitions") ?: continue
                    for (j in 0 until comps.length()) {
                        tennisMatch(comps.getJSONObject(j))?.takeIf { sameLocalDay(it.startMs, day) }?.let { matches += it }
                    }
                }
                if (matches.isEmpty()) continue
                out += SportsRow.Header(ev.optString("name"), "${matches.size} ${if (matches.size == 1) "match" else "matches"}")
                matches.sortedWith(compareBy({ it.statusState != "in" }, { it.startMs })).forEach { out += SportsRow.Match(it) }
            }
            out
        }
        scoreboardCache[key] = System.currentTimeMillis() to rows
        return rows
    }

    /** A singles match, drawn like a fight: each player's set scores on a second line. */
    private fun tennisMatch(comp: JSONObject): MatchEvent? {
        val players = comp.optJSONArray("competitors") ?: return null
        if (players.length() < 2) return null
        val a = players.getJSONObject(0)
        val b = players.getJSONObject(1)
        fun name(p: JSONObject) = p.optJSONObject("athlete")?.optString("displayName")?.takeIf { it.isNotBlank() }
        val nameA = name(a) ?: return null
        val nameB = name(b) ?: return null
        fun sets(p: JSONObject): String {
            val ls = p.optJSONArray("linescores") ?: return ""
            return (0 until ls.length()).joinToString("  ") { ls.optJSONObject(it)?.optDouble("value")?.toInt()?.toString().orEmpty() }
        }
        val type = comp.optJSONObject("status")?.optJSONObject("type")
        val state = type?.optString("state", "pre") ?: "pre"
        val startMs = isoToMs(comp.optString("date"))
        return MatchEvent(
            id = comp.optString("id"),
            homeTeam = nameA, awayTeam = nameB,
            homeLogoUrl = a.optJSONObject("athlete")?.optJSONObject("flag")?.optString("href").orEmpty(),
            awayLogoUrl = b.optJSONObject("athlete")?.optJSONObject("flag")?.optString("href").orEmpty(),
            homeScore = sets(a), awayScore = sets(b),
            statusState = state,
            statusDetail = if (state == "pre") (if (comp.optBoolean("timeValid", true)) timeOf(startMs) else "TBC")
                else type?.optString("shortDetail").orEmpty(),
            note = comp.optJSONObject("round")?.optString("displayName").orEmpty(),
            finalLabel = type?.optString("shortDetail")?.takeIf { it.isNotBlank() && !it.equals("Final", true) } ?: "Final",
            homeWinner = a.optBoolean("winner"), awayWinner = b.optBoolean("winner"),
            isFight = true,
            startMs = startMs
        )
    }

    private fun sameLocalDay(ms: Long, day: Calendar): Boolean {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.YEAR) == day.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)
    }

    // ── Golf ──────────────────────────────────────────────────────────────────

    /** Golf's end date is the final day's midnight (US time), so keep it current through that day. */
    const val GOLF_GRACE_MS = 30 * 3_600_000L

    /** A tournament's leaderboard: position (with ties), player, round scores, total to par. */
    suspend fun golfLeaderboard(c: Category, ev: CalendarEvent): List<SportsRow> {
        val event = eventJson(c, ev) ?: return emptyList()
        val name = event.optString("name", ev.label)
        val status = event.optJSONObject("status")?.optJSONObject("type")
        val dates = "${fmt("d MMM", ev.startMs)} – ${fmt("d MMM", maxOf(ev.endMs - 3_600_000L, ev.startMs))}"
        val (a, b) = golfSearch(name)
        val players = event.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("competitors") ?: JSONArray()
        val header = SportsRow.Header(name, listOf(dates, status?.optString("detail").orEmpty())
            .filter { it.isNotBlank() && it != "Scheduled" }.joinToString("  ·  "))
        if (players.length() == 0) {
            return listOf(header, SportsRow.Item(
                id = "golf-${ev.id}", title = "The field isn't out yet",
                subtitle = "Starts ${fmt("EEEE d MMMM", ev.startMs)}. Select to find channels showing it.",
                searchA = a, searchB = b, startMs = ev.startMs
            ))
        }
        val list = (0 until players.length()).map { players.getJSONObject(it) }.sortedBy { it.optInt("order", 999) }
        val scores = list.map { it.optString("score") }
        val live = status?.optString("state") == "in"
        return listOf(header) + list.mapIndexed { i, p ->
            val score = scores[i]
            // Players level on score share a position: "T3".
            val firstAt = scores.indexOf(score)
            val tied = scores.count { it == score } > 1
            val rounds = p.optJSONArray("linescores")?.let { ls ->
                (0 until ls.length()).mapNotNull { r -> ls.optJSONObject(r)?.optString("displayValue")?.takeIf { it.isNotBlank() }?.let { "R${r + 1} $it" } }
            }.orEmpty().joinToString("  ·  ")
            SportsRow.Item(
                id = p.optString("id", "$i"),
                lead = if (score.isBlank()) "" else (if (tied) "T" else "") + (firstAt + 1),
                imageUrl = p.optJSONObject("athlete")?.optJSONObject("flag")?.optString("href").orEmpty(),
                title = p.optJSONObject("athlete")?.optString("displayName").orEmpty(),
                subtitle = rounds,
                trail = score,
                live = live && i < 3,
                searchA = a, searchB = b, startMs = System.currentTimeMillis()
            )
        }
    }

    /** What the channel finder looks for: the tournament's distinctive word, and "golf". */
    private fun golfSearch(name: String): Pair<String, String> {
        val common = setOf("the", "championship", "open", "classic", "invitational", "tour", "golf", "presented",
            "by", "of", "at", "in", "and", "cup", "championships", "tournament", "pga", "lpga", "liv")
        val word = name.split(Regex("[^A-Za-z0-9]+")).firstOrNull { it.length > 2 && it.lowercase() !in common }
        return (word ?: name) to "golf"
    }

    // ── TV-listing sports (cricket, darts, snooker) ───────────────────────────

    /** Everything coming up on TV for [s], under a header per day, from sport-tv-guide.live. */
    suspend fun listings(s: SportsCatalog.Sport): List<SportsRow> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val all = SportTvGuide.listings(s.listingSlug) ?: throw IOException("sport-tv-guide.live unavailable")
        val items = all.filter { it.startMs > now - 8 * 3_600_000L }.sortedBy { it.startMs }
        val out = mutableListOf<SportsRow>()
        items.groupBy { fmt("EEEE d MMMM", it.startMs) }.forEach { (dayName, rows) ->
            out += SportsRow.Header(dayName, "${rows.size} on TV")
            rows.forEachIndexed { i, l ->
                val (a, b) = SportTvGuide.sides(l.event) ?: (s.label to l.event)
                out += SportsRow.Item(
                    id = "${l.startMs}-$i-${l.event}",
                    title = l.event,
                    subtitle = listOf(l.competition, l.stations.joinToString(", ")).filter { it.isNotBlank() }.joinToString("  ·  "),
                    trail = if (l.startMs <= now) "Started" else timeOf(l.startMs),
                    searchA = a, searchB = b, startMs = l.startMs
                )
            }
        }
        out
    }

    // ── Boxing (TheSportsDB, free key) ────────────────────────────────────────
    // ESPN has no boxing feed. TheSportsDB's free key only lists the next/last single fight per
    // league, but its per-day lookup works, so a week is seven parallel day requests.

    private const val TSDB = "https://www.thesportsdb.com/api/v1/json/3"
    private const val BOXING_LEAGUE = 4445
    private val boxingDays = mutableMapOf<String, List<SportsRow.Item>>()

    suspend fun boxingWeek(weekStart: Calendar): List<SportsRow> = coroutineScope {
        val days = (0 until 7).map { (weekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, it) } }
        val results = days.map { day -> async(Dispatchers.IO) { day to boxingDay(day) } }.awaitAll()
        if (results.all { it.second == null }) throw IOException("TheSportsDB unavailable")
        val out = mutableListOf<SportsRow>()
        for ((day, fights) in results) {
            if (fights.isNullOrEmpty()) continue
            out += SportsRow.Header(SimpleDateFormat("EEEE d MMMM", Locale.UK).format(day.time))
            out += fights
        }
        out
    }

    /** Null when the request failed (so a fully failed week can say so); empty for no fights. */
    private fun boxingDay(day: Calendar): List<SportsRow.Item>? {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(day.time)
        val isPast = day.timeInMillis < System.currentTimeMillis() - 86_400_000L
        if (isPast) boxingDays[date]?.let { return it }
        val json = try {
            JSONObject(fetchJson(clientH2, "$TSDB/eventsday.php?d=$date&l=$BOXING_LEAGUE", espnHeaders = false))
        } catch (_: Exception) { return null }
        val events = json.optJSONArray("events") ?: JSONArray()
        val fights = (0 until events.length()).map { i ->
            val e = events.getJSONObject(i)
            val title = e.optString("strEvent")
            val startMs = e.optString("strTimestamp").takeIf { it.isNotBlank() && it != "null" }?.let { isoToMs(it) }
                ?: isoToMs("${e.optString("dateEvent")}T${e.optString("strTime").take(8)}")
            val venue = listOf("strVenue", "strCity", "strCountry")
                .map { e.optString(it) }.filter { it.isNotBlank() && it != "null" }.joinToString(", ")
            val result = e.optString("strResult").takeIf { it.isNotBlank() && it != "null" }
            // TheSportsDB gives 00:00:00 when the fight time isn't known yet.
            val timeKnown = e.optString("strTime").let { it.isNotBlank() && it != "null" && !it.startsWith("00:00") }
            val (a, b) = splitFighters(title)
            SportsRow.Item(
                id = e.optString("idEvent", "$date-$i"),
                title = title,
                subtitle = result?.lineSequence()?.firstOrNull()?.take(140) ?: venue,
                trail = when {
                    result != null -> "Result"
                    timeKnown -> timeOf(startMs)
                    else -> "Time TBC"
                },
                done = result != null,
                searchA = a, searchB = b,
                startMs = startMs
            )
        }.sortedBy { it.startMs }
        if (isPast) boxingDays[date] = fights
        return fights
    }

    /** "Prime Video Boxing 16 Inoue vs Nasukawa II" → ("Inoue", "Nasukawa"), for the channel finder. */
    private fun splitFighters(title: String): Pair<String, String> {
        val parts = title.split(Regex("\\s+vs\\.?\\s+", RegexOption.IGNORE_CASE))
        if (parts.size < 2) return title to ""
        val a = parts[0].trim().split(' ').last()
        val b = parts[1].trim().split(' ').filter { !it.matches(Regex("[IVX]+|\\d+")) }.lastOrNull() ?: parts[1]
        return a to b
    }
}
