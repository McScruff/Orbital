package com.orbital.iptv.ui.sports

import android.content.Context
import com.orbital.iptv.data.model.LiveStream
import com.orbital.iptv.data.model.getDecodedDescription
import com.orbital.iptv.data.model.getDecodedTitle
import com.orbital.iptv.utils.EpgCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Finds where an event is showing on the user's own IPTV service. An event is two "sides" that
 * must both be mentioned: two teams, two fighters, or a Grand Prix and its country. Sources:
 *  1. programme titles in the cached TV guide (EPG) for every channel,
 *  2. channel names that mention both sides (providers often name event channels after the match),
 *  3. the sport-tv-guide.live listing, matched back onto the user's channels.
 */
object MatchChannelFinder {

    data class Hit(val stream: LiveStream, val detail: String, val startMs: Long = 0L)

    data class Result(
        val epgHits: List<Hit> = emptyList(),
        val nameHits: List<Hit> = emptyList(),
        val listedNames: List<String> = emptyList(),
        val listedPlayable: List<Hit> = emptyList(),
        /** False for the quick search (sport-tv-guide's channels only): the guide and names weren't searched. */
        val deep: Boolean = true
    ) {
        /** Every playable channel, in the order shown (sport-tv-guide's first) — this is the zap list. */
        val allPlayable: List<Hit> get() = (listedPlayable + epgHits + nameHits).distinctBy { it.stream.streamId }
    }

    private val timeFmt = SimpleDateFormat("EEE HH:mm", Locale.UK)
    private val endFmt = SimpleDateFormat("HH:mm", Locale.UK)

    // Words that don't identify a side on their own ("City", "United", "FC"...).
    private val generic = setOf(
        "fc", "afc", "cf", "sc", "ac", "as", "the", "of", "and", "de", "city", "united", "utd", "town",
        "county", "rovers", "albion", "hotspur", "wanderers", "athletic", "real", "club", "v", "vs"
    )
    private val alias = mapOf(
        "man" to "manchester", "spurs" to "tottenham", "wolves" to "wolverhampton",
        "nottm" to "nottingham", "notts" to "nottingham", "brum" to "birmingham",
        "psg" to "paris", "intl" to "international", "gp" to "prix"
    )

    // Channel names cleaned up once per channel list (tens of thousands of names): doing it per
    // search, per listed station, was what made finding channels slow on TV boxes.
    private class Prepared(val streams: List<LiveStream>, val norm: Array<String>, val words: Array<Set<String>>)
    @Volatile private var prepared: Prepared? = null

    /** Prepares [streams] for matching; call off the main thread when the list loads. */
    fun prepare(streams: List<LiveStream>) {
        if (prepared?.streams === streams) return
        prepared = Prepared(
            streams,
            Array(streams.size) { normChannel(streams[it].name) },
            Array(streams.size) { words(streams[it].name).toSet() }
        )
    }

    private fun preparedFor(streams: List<LiveStream>): Prepared {
        prepare(streams)
        return prepared!!
    }

    private val qualityTag = Regex("""\b(hd|4k|fhd|sd|uhd|hevc|h265|\d{2}fps)\b""")
    private val nonWord = Regex("""[^\w\s]""")
    private val spaces = Regex("""\s+""")
    private val nonAlnum = Regex("[^a-z0-9 ]")

    private val plusHours = Regex("""\+\s*(\d+)""")

    /**
     * "UK: Sky Sports+ FHD" → "uk sky sports plus". A "+" counts as a word: it names a different
     * channel. "+1" (the hour-later channel) becomes its own word, so "Discovery+1" isn't "Discovery+".
     */
    private fun normChannel(name: String) = name.lowercase()
        .replace(plusHours, " plus$1hr ")
        .replace("+", " plus ")
        .replace(qualityTag, " ")
        .replace(nonWord, " ")
        .replace(spaces, " ").trim()

    private fun words(s: String): List<String> =
        s.lowercase().replace('&', ' ').replace(nonAlnum, " ")
            .split(' ').filter { it.isNotBlank() }.map { alias[it] ?: it }

    private class SideKey(val tokens: Set<String>, val mustAlso: Set<String>)

    private fun keyFor(side: String): SideKey {
        val all = words(side)
        val distinct = all.filter { it !in generic }.toSet()
        val tokens = if (distinct.isEmpty()) all.toSet() else distinct
        // "Manchester" alone is ambiguous between United and City — keep the second word.
        val must = if ("manchester" in tokens) all.filter { it == "united" || it == "utd" || it == "city" }
            .map { if (it == "utd") "united" else it }.toSet() else emptySet()
        return SideKey(tokens, must)
    }

    private fun mentions(textWords: Set<String>, key: SideKey): Boolean =
        key.tokens.any { it in textWords } && key.mustAlso.all { it in textWords || (it == "united" && "utd" in textWords) }

    private fun bothIn(text: String, a: SideKey, b: SideKey): Boolean {
        val w = words(text).toSet()
        return mentions(w, a) && mentions(w, b)
    }

    /** Searches listings from 4h before to 28h after [dayMs] (any moment on the event's day). */
    suspend fun find(
        context: Context,
        sideA: String,
        sideB: String,
        dayMs: Long,
        streams: List<LiveStream>,
        listingSlug: String,
        deep: Boolean = true
    ): Result = coroutineScope {
        val a = keyFor(sideA)
        val b = keyFor(sideB)

        val dayStart = java.util.Calendar.getInstance().apply {
            timeInMillis = dayMs
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val from = dayStart - 4 * 3_600_000L
        val to = dayStart + 28 * 3_600_000L
        val now = System.currentTimeMillis()

        // 1 — programme listings across the whole cached guide
        val epgJob = async(Dispatchers.IO) {
            if (!deep) return@async emptyList<Hit>()
            streams.chunked(96).flatMap { chunk ->
                chunk.map { s ->
                    async {
                        val listings = EpgCache.get(context, s.streamId) ?: return@async null
                        for (l in listings) {
                            val startMs = (l.startTimestamp?.toLongOrNull() ?: continue) * 1000L
                            if (startMs < from || startMs > to) continue
                            val title = try { l.getDecodedTitle() } catch (_: Exception) { continue }
                            val matched = bothIn(title, a, b) ||
                                (title.contains("live", true) && bothIn(try { l.getDecodedDescription() } catch (_: Exception) { "" }, a, b))
                            if (!matched) continue
                            val endMs = (l.stopTimestamp?.toLongOrNull() ?: 0L) * 1000L
                            val range = if (endMs > startMs) "${timeFmt.format(Date(startMs))}–${endFmt.format(Date(endMs))}" else timeFmt.format(Date(startMs))
                            return@async Hit(s, "$range  ·  $title", startMs)
                        }
                        null
                    }
                }.awaitAll().filterNotNull()
            }.sortedBy { kotlin.math.abs(it.startMs - now) }
        }

        // 2 — channels named after the event
        val nameJob = async(Dispatchers.Default) {
            if (!deep) emptyList() else preparedFor(streams).let { pr ->
                streams.indices.filter { mentions(pr.words[it], a) && mentions(pr.words[it], b) }.map { streams[it] }
            }.map { Hit(it, "Channel named after this event") }
        }

        // 3 — listings site (best effort)
        val listedJob = async(Dispatchers.IO) { fetchListedStations(listingSlug, a, b) }

        val epg = epgJob.await()
        val names = nameJob.await().filter { n -> epg.none { it.stream.streamId == n.stream.streamId } }
        val listed = try { listedJob.await() } catch (_: Exception) { emptyList() }
        // Up to two of the user's channels per listed station (an HD and an FHD feed, say), best match first.
        val pr = preparedFor(streams)
        val listedPlayable = listed.flatMap { tv ->
            val t = normChannel(tv)
            if (t.isEmpty()) return@flatMap emptyList()
            val padded = " $t "
            streams.indices.mapNotNull { i ->
                val xt = pr.norm[i]
                // 2: the same name; 1: the user's name contains the station's whole name ("UK: Sky
                // Sports Tennis HD" for "Sky Sports Tennis"). Never the other way round.
                val score = when {
                    xt == t -> 2
                    " $xt ".contains(padded) -> 1
                    else -> 0
                }
                if (score > 0) streams[i] to score else null
            }
                .sortedWith(compareByDescending<Pair<LiveStream, Int>> { it.second }.thenBy { it.first.name.length })
                .take(2)
                .map { Hit(it.first, "Listed on $tv") }
        }.distinctBy { it.stream.streamId }
        Result(epg, names, listed, listedPlayable, deep)
    }

    // The site's listing page shows the primary broadcaster(s) per event as station icons.
    private fun fetchListedStations(slug: String, a: SideKey, b: SideKey): List<String> =
        SportTvGuide.listings(slug).orEmpty()
            .filter { bothIn(it.event, a, b) || bothIn("${it.event} ${it.competition}", a, b) }
            .flatMap { it.stations }
            .distinct()

}
