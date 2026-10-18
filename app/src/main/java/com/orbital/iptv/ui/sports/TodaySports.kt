package com.orbital.iptv.ui.sports

import android.content.Context
import com.orbital.iptv.ui.sports.SportsCatalog.Kind
import com.orbital.iptv.utils.PrefsManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Calendar

/**
 * Today's events across the sports chosen in the Sports Bar, for the home screen card: live
 * first, then what's coming up, then today's results. Each sport contributes its first few
 * competitions and at most [PER_SPORT] events, so one busy sport doesn't fill the card.
 */
object TodaySports {

    data class Item(
        val sportId: String,
        val sportLabel: String,
        val title: String,
        val meta: String,
        val detail: String,
        val live: Boolean,
        val done: Boolean,
        val startMs: Long,
        val logoUrl: String,
        val searchA: String,
        val searchB: String,
        val listingSlug: String = ""
    )

    private const val PER_SPORT = 4
    private const val MAX_ITEMS = 12
    private const val COMPETITIONS_PER_SPORT = 4

    suspend fun load(context: Context, perSport: Int = PER_SPORT, max: Int = MAX_ITEMS): List<Item> = coroutineScope {
        val hidden = PrefsManager.getHiddenSports(context)
        val sports = SportsCatalog.sports.filter { it.id !in hidden && it.kind != Kind.BOXING && it.kind != Kind.LIVE_NOW }
        val today = Calendar.getInstance()
        val now = System.currentTimeMillis()
        sports.map { s -> async { try { forSport(s, today, now).map { it.copy(listingSlug = s.listingSlug) } } catch (_: Exception) { emptyList() } } }
            .awaitAll()
            .flatMap { it.sortedWith(order).take(perSport) }
            .sortedWith(order)
            .take(max)
    }

    /**
     * The Sports Bar's Live Now view: every live event under a header per sport, then what starts
     * in the next [SOON_MS].
     */
    suspend fun liveNowRows(context: Context): List<SportsRow> {
        val now = System.currentTimeMillis()
        val all = load(context, perSport = 200, max = 1000)
        val live = all.filter { it.live }
        val soon = all.filter { !it.live && !it.done && it.startMs in now..(now + SOON_MS) }.sortedBy { it.startMs }
        fun row(e: Item) = SportsRow.Item(
            id = "${e.sportId}:${e.startMs}:${e.title}",
            imageUrl = e.logoUrl,
            title = e.title,
            subtitle = listOf(e.meta, e.detail.lines().filter { it.isNotBlank() }.joinToString("  /  ")).filter { it.isNotBlank() }.joinToString("  ·  "),
            trail = if (e.live) "LIVE" else SportsFeeds.timeOf(e.startMs),
            live = e.live,
            searchA = e.searchA, searchB = e.searchB, startMs = e.startMs, listingSlug = e.listingSlug
        )
        val out = mutableListOf<SportsRow>()
        live.groupBy { it.sportLabel }.forEach { (sport, rows) ->
            out += SportsRow.Header(sport, "${rows.size} live")
            rows.forEach { out += row(it) }
        }
        if (soon.isNotEmpty()) {
            out += SportsRow.Header("Starting soon", "next ${SOON_MS / 3_600_000L} hours")
            soon.forEach { out += row(it) }
        }
        return out
    }

    private const val SOON_MS = 3 * 3_600_000L

    private val order = compareBy<Item>({ !it.live }, { it.done }, { if (it.done) -it.startMs else it.startMs })

    private suspend fun forSport(s: SportsCatalog.Sport, today: Calendar, now: Long): List<Item> = coroutineScope {
        when (s.kind) {
            Kind.TEAM, Kind.TENNIS -> s.categories.take(COMPETITIONS_PER_SPORT).map { c ->
                async {
                    val rows = try {
                        if (s.kind == Kind.TENNIS) SportsFeeds.tennisDay(c, today) else SportsFeeds.teamScoreboard(c, today)
                    } catch (_: Exception) { emptyList() }
                    rows.filterIsInstance<SportsRow.Match>().map { matchItem(s, c, it.event) }
                }
            }.awaitAll().flatten()
            Kind.LISTINGS -> SportsFeeds.listings(s).filterIsInstance<SportsRow.Item>()
                .filter { sameDay(it.startMs, today) }
                .map { r ->
                    Item(s.id, s.label, r.title, listOf(r.subtitle.substringBefore("  ·  "), if (r.startMs <= now) "On now" else SportsFeeds.timeOf(r.startMs))
                        .filter { it.isNotBlank() }.joinToString("  ·  "),
                        detail = r.subtitle.substringAfter("  ·  ", "").let { if (it.isBlank()) "" else "On $it" },
                        live = r.startMs <= now, done = false, startMs = r.startMs, logoUrl = "",
                        searchA = r.searchA, searchB = r.searchB)
                }
            Kind.UFC, Kind.F1, Kind.GOLF -> s.categories.take(2).mapNotNull { c ->
                // UFC and F1 list real end times; golf's "end" is the final day's midnight.
                fun endOf(e: CalendarEvent) = e.endMs + if (s.kind == Kind.GOLF) SportsFeeds.GOLF_GRACE_MS else 0L
                val ev = SportsFeeds.calendar(c).firstOrNull { e -> sameDayRange(e.startMs, endOf(e), today) }
                    ?: return@mapNotNull null
                val started = now >= ev.startMs
                val over = now > endOf(ev)
                Item(s.id, s.label, ev.label,
                    meta = listOf(c.name, when { over -> "Finished"; started -> "On now"; else -> SportsFeeds.timeOf(ev.startMs) }).joinToString("  ·  "),
                    detail = "", live = started && !over, done = over, startMs = ev.startMs, logoUrl = "",
                    searchA = if (s.kind == Kind.F1) ev.label.replace("Grand Prix", "").trim() else ev.label,
                    searchB = when (s.kind) { Kind.F1 -> "Formula F1 Grand Prix"; Kind.GOLF -> "golf"; else -> "UFC" })
            }
            Kind.BOXING, Kind.LIVE_NOW -> emptyList()
        }
    }

    private fun matchItem(s: SportsCatalog.Sport, c: SportsCatalog.Category, e: MatchEvent): Item {
        val live = e.statusState == "in"
        val done = e.statusState == "post"
        val scored = (live || done) && !e.isFight
        val title = if (scored) "${e.homeTeam}  ${e.homeScore} – ${e.awayScore}  ${e.awayTeam}" else "${e.homeTeam} v ${e.awayTeam}"
        val status = when {
            live -> "LIVE ${e.statusDetail}".trim()
            done -> e.finalLabel
            else -> e.statusDetail
        }
        val detail = if (e.isFight && (live || done)) "${e.homeTeam}  ${e.homeScore}\n${e.awayTeam}  ${e.awayScore}" else e.note
        return Item(s.id, s.label, title, listOf(c.name, status).filter { it.isNotBlank() }.joinToString("  ·  "),
            detail = detail, live = live, done = done, startMs = e.startMs,
            logoUrl = e.homeLogoUrl, searchA = e.homeTeam, searchB = e.awayTeam)
    }

    private fun sameDay(ms: Long, day: Calendar): Boolean {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.YEAR) == day.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)
    }

    /** True if today falls anywhere between [startMs] and [endMs]. */
    private fun sameDayRange(startMs: Long, endMs: Long, day: Calendar): Boolean {
        val dayStart = (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return startMs < dayStart + 86_400_000L && endMs >= dayStart
    }
}
