package com.orbital.iptv.ui.sports

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Reads a sport's page on sport-tv-guide.live (e.g. /live/darts): what's on TV over the coming
 * days and on which channels. Times on the page are UTC, under a date separator per day
 * ("22 Oct"). Events are either links (<a class="article...">) or plain blocks
 * (<div class="article...">). It isn't a published API, so a layout change shows up as an empty
 * list rather than an error.
 */
object SportTvGuide {

    data class Listing(val startMs: Long, val event: String, val competition: String, val stations: List<String>)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val TTL_MS = 15 * 60_000L
    private val cache = mutableMapOf<String, Pair<Long, List<Listing>>>()

    private val token = Regex("""<span class="date">\s*(\d{1,2})\s+([A-Za-z]{3})\s*</span>|<(?:a|div) class="article[^"]*"""")
    private val time = Regex("""<b>\s*(\d{1,2}):(\d{2})\s*</b>""")
    private val mobileBlock = Regex("""<div class="col-xs-8 mobile-small[^"]*">(.*?)</div>\s*</div>""", RegexOption.DOT_MATCHES_ALL)
    private val line = Regex("""<div class="text-nowrap">(?:<b>)?([^<]*)""")
    private val station = Regex("""tvm-station-[^"]*"\s+title="([^"]+)"""")
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** Listings for [slug], cached for 15 minutes; null if the page couldn't be fetched. */
    fun listings(slug: String): List<Listing>? {
        synchronized(cache) {
            cache[slug]?.let { (at, rows) -> if (System.currentTimeMillis() - at < TTL_MS) return rows }
        }
        val html = try {
            http.newCall(
                Request.Builder().url("https://sport-tv-guide.live/live/$slug")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Accept-Language", "en-GB,en;q=0.9")
                    .header("Referer", "https://sport-tv-guide.live/")
                    .build()
            ).execute().use { if (it.isSuccessful) it.body?.string() else null }
        } catch (_: Exception) { null } ?: return null
        val rows = parse(html)
        synchronized(cache) { cache[slug] = System.currentTimeMillis() to rows }
        return rows
    }

    fun parse(html: String): List<Listing> {
        val out = mutableListOf<Listing>()
        val utc = TimeZone.getTimeZone("UTC")
        val now = Calendar.getInstance(utc)
        // Before the first date separator, events are today's (UTC).
        var day = Calendar.getInstance(utc).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val marks = token.findAll(html).toList()
        marks.forEachIndexed { i, m ->
            if (m.groupValues[1].isNotEmpty()) {
                val month = months.indexOf(m.groupValues[2].lowercase(Locale.UK))
                if (month < 0) return@forEachIndexed
                day = Calendar.getInstance(utc).apply {
                    set(Calendar.YEAR, now.get(Calendar.YEAR))
                    // A January listing seen in December belongs to next year.
                    if (month < now.get(Calendar.MONTH) - 6) add(Calendar.YEAR, 1)
                    set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, m.groupValues[1].toInt())
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                return@forEachIndexed
            }
            val end = marks.getOrNull(i + 1)?.range?.first ?: html.length
            val block = html.substring(m.range.first, end)
            val t = time.find(block) ?: return@forEachIndexed
            val text = (mobileBlock.find(block)?.groupValues?.get(1) ?: block)
            val lines = line.findAll(text).map { unescape(it.groupValues[1]).trim() }.filter { it.isNotEmpty() }.toList()
            val event = lines.firstOrNull()?.replaceFirst(Regex("^Live\\s+", RegexOption.IGNORE_CASE), "") ?: return@forEachIndexed
            val competition = lines.getOrNull(1)?.takeIf { it.length <= 60 && it != event }.orEmpty()
            val stations = station.findAll(block)
                .map { unescape(it.groupValues[1]).replace(Regex("""\s*\[[^\]]*\]\s*"""), "").trim() }
                .filter { it.isNotEmpty() }.distinct().toList()
            val start = (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, t.groupValues[1].toInt()); set(Calendar.MINUTE, t.groupValues[2].toInt())
            }.timeInMillis
            out += Listing(start, event, competition, stations)
        }
        return out
    }

    /** "Gian van Veen - Brendan Dolan" → both sides; null for "European Darts Championship". */
    fun sides(event: String): Pair<String, String>? {
        val parts = event.split(Regex("""\s+(?:-|v|vs\.?|–)\s+""", RegexOption.IGNORE_CASE))
        if (parts.size != 2) return null
        val (a, b) = parts.map { it.trim() }
        // Only names, not "Championship - Dortmund".
        return if (a.split(' ').size <= 4 && b.split(' ').size <= 4) a to b else null
    }

    private fun unescape(s: String) = s
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&#039;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")
}
