package com.orbital.iptv.ui.sports

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.orbital.iptv.R
import com.orbital.iptv.data.model.LiveStream
import com.orbital.iptv.data.repository.XtreamRepository
import com.orbital.iptv.databinding.ActivitySportsBinding
import com.orbital.iptv.ui.player.PlayerActivity
import com.orbital.iptv.utils.PrefsManager
import com.orbital.iptv.utils.TickerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import com.orbital.iptv.utils.ThemeManager

class SportsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySportsBinding

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // (tab label, ESPN league id, display name, SofaScore tournament id, whether standings are group-based)
    private data class League(val tab: String, val espnId: String, val name: String, val sofaId: Int, val hasGroups: Boolean = false)
    private val leagues = listOf(
        League("PL",       "eng.1",          "PREMIER LEAGUE",    17),
        League("CHAMP",    "eng.2",          "CHAMPIONSHIP",      18),
        League("UCL",      "uefa.champions", "CHAMPIONS LEAGUE",  7),
        League("FA CUP",   "eng.fa",         "FA CUP",            21),
        League("LA LIGA",  "esp.1",          "LA LIGA",           8),
        League("SERIE A",  "ita.1",          "SERIE A",           23),
        League("BUNDESL",  "ger.1",          "BUNDESLIGA",        35),
        League("WC",       "fifa.world",     "WORLD CUP 2026",    16, hasGroups = true)
    )
    private var leagueIdx = 0
    private val date = Calendar.getInstance()
    private var showTable = false

    private lateinit var matchAdapter: MatchAdapter
    private val standingAdapter = StandingAdapter()
    private val leagueTabs = mutableListOf<TextView>()
    private val xtreamRepository = XtreamRepository()

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            fetchData()
            refreshHandler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivitySportsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.load(this)
        val p = ThemeManager.palette()
        binding.root.setBackgroundColor(p.bgPrimary)
        binding.layoutHeader?.setBackgroundColor(p.bgHeader)
        binding.viewAccent?.setBackgroundColor(p.accent)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { _, h ->
            binding.btnBack.setBackgroundColor(if (h) 0xFF2D6090.toInt() else 0xFF1E3D72.toInt())
        }

        matchAdapter = MatchAdapter(
            onToggle = { game ->
                val sg = TickerManager.SelectedGame(game.id, leagues[leagueIdx].espnId, game.homeTeam, game.awayTeam)
                TickerManager.toggle(this, sg)
                matchAdapter.selectedIds = TickerManager.getSelected(this).map { it.id }.toSet()
            },
            onLongPress = { game -> showTvChannels(game) }
        )

        setupLeagueTabs()
        setupDateNav()
        setupModeToggle()

        binding.rvContent.layoutManager = LinearLayoutManager(this)
        binding.rvContent.itemAnimator = null
        binding.rvContent.adapter = matchAdapter

        updateDateLabel()
        highlightLeagueTab(0)
        binding.tvLeagueName.text = leagues[0].name
        fetchData()
        refreshHandler.postDelayed(refreshRunnable, 60_000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshHandler.removeCallbacksAndMessages(null)
    }

    // ── TV channel lookup (long press) ───────────────────────────────────────

    private fun showTvChannels(event: MatchEvent) {
        val creds = PrefsManager.getCredentials(this) ?: run {
            Toast.makeText(this, "NOT LOGGED IN TO XTREAM", Toast.LENGTH_SHORT).show()
            return
        }
        val loadingDialog = AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("📺  FINDING TV CHANNELS")
            .setMessage("${event.homeTeam} vs ${event.awayTeam}\n\nSearching listings...")
            .setCancelable(true)
            .show()

        CoroutineScope(Dispatchers.Main).launch {
            var debugInfo = ""
            try {
                val (tvChannels, debug) = withContext(Dispatchers.IO) { fetchTvChannels(event) }
                debugInfo = debug
                val xtreamStreams = withContext(Dispatchers.IO) {
                    xtreamRepository.getLiveStreams(creds.serverUrl, creds.username, creds.password)
                        .getOrNull() ?: emptyList()
                }
                val matched = tvChannels.mapNotNull { tvCh ->
                    xtreamStreams.firstOrNull { channelsMatch(tvCh, it.name) }?.let { Pair(tvCh, it) }
                }
                loadingDialog.dismiss()
                showChannelPickerDialog(event, tvChannels, matched, creds.serverUrl, creds.username, creds.password, debugInfo)
            } catch (e: Exception) {
                loadingDialog.dismiss()
                showChannelPickerDialog(event, emptyList(), emptyList(), creds.serverUrl, creds.username, creds.password, e.message ?: "error")
            }
        }
    }

    // Returns (channels, debugInfo)
    private fun fetchTvChannels(event: MatchEvent): Pair<List<String>, String> {
        val homeWords = event.homeTeam.lowercase().split(Regex("""[\s\-]+""")).filter { it.length > 2 }
        val awayWords = event.awayTeam.lowercase().split(Regex("""[\s\-]+""")).filter { it.length > 2 }

        val cookieStore = mutableMapOf<String, List<okhttp3.Cookie>>()
        val tvHttp = OkHttpClient.Builder()
            .cookieJar(object : okhttp3.CookieJar {
                override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) { cookieStore[url.host] = cookies }
                override fun loadForRequest(url: okhttp3.HttpUrl) = cookieStore[url.host] ?: emptyList()
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val scheduleHtml = getForTvGuide("https://sport-tv-guide.live/live/football", tvHttp)

        val htmlLen = scheduleHtml.length
        val anyEventLinks = Regex("""event/live-football""").findAll(scheduleHtml).count()

        // Broad regex: handles href="/..." or href='...' or href=... with any quote style
        val eventPath = Regex("""href=["']?(/event/live-football-[^"'\s>]+)""", RegexOption.IGNORE_CASE)
            .findAll(scheduleHtml)
            .map { it.groupValues[1].removePrefix("/") }
            .firstOrNull { path ->
                teamMatchesPath(event.homeTeam, path) && teamMatchesPath(event.awayTeam, path)
            }

        val debug = "HTML:${htmlLen}b EventLinks:$anyEventLinks Path:${eventPath ?: "none"}"

        if (eventPath == null) return Pair(emptyList(), debug)

        val channelHtml = getForTvGuide("https://sport-tv-guide.live/widgetClick.php?u=$eventPath", tvHttp)
        val channels = parseChannelNames(channelHtml)
        return Pair(channels, "$debug Channels:${channels.size}")
    }

    private fun getForTvGuide(url: String, client: OkHttpClient = http): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-GB,en;q=0.9")
            .header("Referer", "https://sport-tv-guide.live/")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseChannelNames(html: String): List<String> {
        val channels = mutableListOf<String>()
        // Real channel options always have a country code like [UK], [IRL], [AUS], [USA]
        // Filter/nav options ("All", "Station", "Guide") never have brackets
        Regex("""<option[^>]*>([^<]*\[[A-Z]{2,5}\][^<]*)</option>""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                val name = m.groupValues[1].trim()
                if (name.length > 3) channels.add(name)
            }
        // Fallback: any line of text that contains [COUNTRY]
        if (channels.isEmpty()) {
            Regex("""([\w][\w\s+\.\-]{1,50}\s*\[[A-Z]{2,5}\])""")
                .findAll(html).forEach { m -> channels.add(m.groupValues[1].trim()) }
        }
        return channels.distinct()
    }

    // Match a team name (e.g. "PSG", "Arsenal", "Paris Saint Germain") against a URL slug
    // Handles: direct word match, slug match, and acronym expansion (PSG → paris-saint-germain)
    private fun teamMatchesPath(teamName: String, path: String): Boolean {
        val name = teamName.lowercase().trim()
        val p = path.lowercase()

        // Direct word match — most teams (e.g. "Arsenal" → "arsenal" in path)
        val words = name.split(Regex("""[\s\-]+""")).filter { it.length > 2 }
        if (words.isNotEmpty() && words.any { it in p }) return true

        // Full name as slug (e.g. "Paris SG" → "paris-sg")
        val slug = name.replace(Regex("""[^a-z0-9]+"""), "-").trim('-')
        if (slug.length > 2 && slug in p) return true

        // Acronym expansion: "PSG" → first letters of consecutive path words = p,s,g
        if (!name.contains(' ') && name.length in 2..5) {
            val pathWords = p.split(Regex("""[-/]""")).filter { it.isNotEmpty() }
            for (start in 0..(pathWords.size - name.length).coerceAtLeast(0)) {
                if (start + name.length <= pathWords.size) {
                    val initials = (0 until name.length)
                        .joinToString("") { pathWords[start + it].take(1) }
                    if (initials == name) return true
                }
            }
        }

        return false
    }

    private fun channelsMatch(tvChannel: String, xtreamName: String): Boolean {
        // Strip country code suffix before comparing: "TNT Sports 1 [UK]" → "TNT Sports 1"
        val tvStripped = tvChannel.replace(Regex("""\s*\[[A-Z]{2,5}\]\s*"""), "").trim()
        fun norm(s: String) = s.lowercase()
            .replace(Regex("""\b(hd|4k|fhd|sd|uhd)\b"""), "")
            .replace(Regex("""[^\w\s]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        val tv = norm(tvStripped)
        val xt = norm(xtreamName)
        return tv.isNotEmpty() && (tv == xt || xt.contains(tv) || tv.contains(xt))
    }

    private fun showChannelPickerDialog(
        event: MatchEvent,
        tvChannels: List<String>,
        matched: List<Pair<String, LiveStream>>,
        serverUrl: String, username: String, password: String,
        debugInfo: String = ""
    ) {
        val dp = resources.displayMetrics.density
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
        }
        scroll.addView(container)

        fun sectionHeader(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(0xFF00CCCC.toInt())
            textSize = 10f
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, (10 * dp).toInt(), 0, (4 * dp).toInt())
        }
        fun channelRow(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
            setPadding((6 * dp).toInt(), (2 * dp).toInt(), 0, (2 * dp).toInt())
        }
        fun divider() = View(this).apply {
            setBackgroundColor(0x33FFFFFF)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also {
                (it as LinearLayout.LayoutParams).setMargins(0, (10 * dp).toInt(), 0, 0)
            }
        }

        container.addView(sectionHeader("THIS MATCH IS SHOWN ON:"))
        if (tvChannels.isEmpty()) {
            container.addView(channelRow("  No listings found for this match"))
            if (debugInfo.isNotBlank()) container.addView(channelRow("  [debug: $debugInfo]"))
        } else tvChannels.forEach { ch -> container.addView(channelRow("  •  $ch")) }

        container.addView(divider())
        container.addView(sectionHeader("WATCH ON YOUR SERVER:"))
        if (matched.isEmpty()) {
            container.addView(channelRow("  None of these channels matched\n  on your Xtream server"))
        } else {
            matched.forEach { (_, stream) ->
                container.addView(TextView(this).apply {
                    text = "  ▶  ${stream.name}"
                    setTextColor(0xFFFFCC00.toInt())
                    textSize = 14f
                    typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
                    setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
                    isClickable = true; isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .also { (it as LinearLayout.LayoutParams).topMargin = (4 * dp).toInt() }
                    setBackgroundColor(0xFF0D1A2E.toInt())
                    setOnFocusChangeListener { _, h -> setBackgroundColor(if (h) 0xFF1E4A9E.toInt() else 0xFF0D1A2E.toInt()) }
                    setOnClickListener {
                        val streamUrl = xtreamRepository.buildStreamUrl(serverUrl, username, password, stream.streamId)
                        startActivity(Intent(this@SportsActivity, PlayerActivity::class.java).apply {
                            putExtra(PlayerActivity.EXTRA_STREAM_URL, streamUrl)
                            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, stream.name)
                            putExtra(PlayerActivity.EXTRA_STREAM_ID, stream.streamId)
                            putExtra(PlayerActivity.EXTRA_IS_LIVE, true)
                        })
                    }
                })
            }
        }

        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("${event.homeTeam} vs ${event.awayTeam}")
            .setView(scroll)
            .setNegativeButton("CLOSE", null)
            .show()
    }

    // ── League tabs ──────────────────────────────────────────────────────────

    private fun setupLeagueTabs() {
        val dp = resources.displayMetrics.density
        leagues.forEachIndexed { idx, league ->
            val tv = TextView(this).apply {
                text = league.tab
                textSize = 11f
                typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
                isClickable = true
                isFocusable = true
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                ).also { it.marginEnd = (4 * dp).toInt() }
                setOnClickListener { selectLeague(idx) }
                setOnFocusChangeListener { _, h ->
                    if (idx != leagueIdx) setBackgroundColor(if (h) 0xFF2D6090.toInt() else 0xFF0D1A2E.toInt())
                }
            }
            leagueTabs.add(tv)
            binding.leagueTabContainer.addView(tv)
        }
    }

    private fun selectLeague(idx: Int) {
        leagueIdx = idx
        highlightLeagueTab(idx)
        binding.tvLeagueName.text  = leagues[idx].name
        binding.tabStandings.text  = if (leagues[idx].hasGroups) "GROUPS" else "TABLE"
        fetchData()
    }

    private fun highlightLeagueTab(idx: Int) {
        leagueTabs.forEachIndexed { i, tv ->
            tv.setBackgroundColor(if (i == idx) 0xFF1E4A9E.toInt() else 0xFF0D1A2E.toInt())
        }
    }

    // ── Date navigation ──────────────────────────────────────────────────────

    private fun setupDateNav() {
        binding.btnDatePrev.setOnClickListener {
            date.add(Calendar.DAY_OF_YEAR, -1)
            updateDateLabel()
            if (!showTable) fetchData()
        }
        binding.btnDateNext.setOnClickListener {
            date.add(Calendar.DAY_OF_YEAR, 1)
            updateDateLabel()
            if (!showTable) fetchData()
        }
        binding.btnDatePrev.setOnFocusChangeListener { _, h ->
            binding.btnDatePrev.setTextColor(if (h) 0xFF00CCCC.toInt() else 0xFFFFFFFF.toInt())
        }
        binding.btnDateNext.setOnFocusChangeListener { _, h ->
            binding.btnDateNext.setTextColor(if (h) 0xFF00CCCC.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    private fun updateDateLabel() {
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, -1) }
        val tomorrow  = Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, +1) }
        binding.tvDate.text = when {
            isSameDay(date, today)     -> "TODAY"
            isSameDay(date, yesterday) -> "YESTERDAY"
            isSameDay(date, tomorrow)  -> "TOMORROW"
            else -> SimpleDateFormat("EEE d MMM", Locale.UK).format(date.time).uppercase()
        }
    }

    private fun isSameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    // ── Games / Table toggle ─────────────────────────────────────────────────

    private fun setupModeToggle() {
        binding.tabGames.setOnClickListener { setMode(false) }
        binding.tabStandings.setOnClickListener { setMode(true) }
        binding.tabGames.setOnFocusChangeListener { _, h ->
            if (showTable) binding.tabGames.setBackgroundColor(if (h) 0xFF2D6090.toInt() else 0xFF0D1A2E.toInt())
        }
        binding.tabStandings.setOnFocusChangeListener { _, h ->
            if (!showTable) binding.tabStandings.setBackgroundColor(if (h) 0xFF2D6090.toInt() else 0xFF0D1A2E.toInt())
        }
    }

    private fun setMode(table: Boolean) {
        showTable = table
        binding.tabGames.setBackgroundColor(if (!table) 0xFF1E4A9E.toInt() else 0xFF0D1A2E.toInt())
        binding.tabStandings.setBackgroundColor(if (table) 0xFF1E4A9E.toInt() else 0xFF0D1A2E.toInt())
        binding.dateNav.visibility = if (table) View.GONE else View.VISIBLE
        binding.standingsHeader.visibility = if (table) View.VISIBLE else View.GONE
        binding.rvContent.adapter = if (table) standingAdapter else matchAdapter
        fetchData()
    }

    // ── Data fetching ────────────────────────────────────────────────────────

    private fun fetchData() {
        if (showTable) fetchStandings() else fetchScoreboard()
    }

    private fun fetchScoreboard() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        val leagueId = leagues[leagueIdx].espnId
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(date.time)
        val url = "https://site.api.espn.com/apis/site/v2/sports/soccer/$leagueId/scoreboard?dates=$dateStr"

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val json = withContext(Dispatchers.IO) { get(url) }
                val events = parseScoreboard(json)
                binding.progressBar.visibility = View.GONE
                matchAdapter.selectedIds = TickerManager.getSelected(this@SportsActivity).map { it.id }.toSet()
                matchAdapter.submitList(events)
                if (events.isEmpty()) {
                    binding.tvEmpty.text = "NO FIXTURES FOR THIS DATE"
                    binding.tvEmpty.visibility = View.VISIBLE
                } else {
                    binding.tvEmpty.visibility = View.GONE
                    // Refresh every 30s while live games are on
                    if (events.any { it.statusState == "in" }) {
                        refreshHandler.removeCallbacks(refreshRunnable)
                        refreshHandler.postDelayed(refreshRunnable, 30_000L)
                    }
                }
            } catch (_: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.tvEmpty.text = "FAILED TO LOAD — CHECK CONNECTION"
                binding.tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun fetchStandings() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        val league = leagues[leagueIdx]

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    val seasonsJson = get("https://api.sofascore.com/api/v1/unique-tournament/${league.sofaId}/seasons")
                    val seasons = JSONObject(seasonsJson).optJSONArray("seasons")
                        ?: return@withContext emptyList()
                    val seasonId = seasons.getJSONObject(0).optInt("id", -1)
                    if (seasonId == -1) return@withContext emptyList()

                    val standingsJson = get(
                        "https://api.sofascore.com/api/v1/unique-tournament/${league.sofaId}/season/$seasonId/standings/total"
                    )
                    if (league.hasGroups) parseGroupStandings(standingsJson)
                    else parseSofascoreStandings(standingsJson)
                }
                binding.progressBar.visibility = View.GONE
                standingAdapter.submitList(entries)
                if (entries.isEmpty()) {
                    binding.tvEmpty.text = if (league.hasGroups) "NO GROUPS AVAILABLE YET" else "NO TABLE AVAILABLE FOR THIS COMPETITION"
                    binding.tvEmpty.visibility = View.VISIBLE
                } else {
                    binding.tvEmpty.visibility = View.GONE
                }
            } catch (_: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.tvEmpty.text = "FAILED TO LOAD — CHECK CONNECTION"
                binding.tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun parseGroupStandings(json: String): List<StandingEntry> {
        val out = mutableListOf<StandingEntry>()
        try {
            val standings = JSONObject(json).optJSONArray("standings") ?: return out
            for (g in 0 until standings.length()) {
                val group = standings.getJSONObject(g)
                val groupName = group.optString("name").ifEmpty { "Group ${('A' + g)}" }
                out.add(StandingEntry(0, "", "", 0, 0, 0, 0, "", 0, isGroupHeader = true, groupName = groupName))
                val rows = group.optJSONArray("rows") ?: continue
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val team = row.optJSONObject("team") ?: continue
                    val teamId = team.optInt("id", -1)
                    out.add(StandingEntry(
                        pos     = row.optInt("position", i + 1),
                        team    = team.optString("shortName").ifEmpty { team.optString("name") },
                        logoUrl = if (teamId > 0) "https://api.sofascore.app/api/v1/team/$teamId/image" else "",
                        played  = row.optInt("matches", 0),
                        won     = row.optInt("wins", 0),
                        drawn   = row.optInt("draws", 0),
                        lost    = row.optInt("losses", 0),
                        gd      = row.optString("scoreDiffFormatted", "0"),
                        points  = row.optInt("points", 0)
                    ))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        return http.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    // ── JSON parsing ─────────────────────────────────────────────────────────

    private fun parseScoreboard(json: String): List<MatchEvent> {
        val out = mutableListOf<MatchEvent>()
        try {
            val events = JSONObject(json).optJSONArray("events") ?: return out
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                val comp = event.optJSONArray("competitions")?.getJSONObject(0) ?: continue
                val competitors = comp.optJSONArray("competitors") ?: continue
                val status = comp.optJSONObject("status") ?: continue
                val statusType = status.optJSONObject("type") ?: continue
                val state = statusType.optString("state", "pre")

                val detail = if (state == "pre") {
                    kickoffTime(event.optString("date"))
                } else {
                    statusType.optString("shortDetail", "")
                }

                val statusDesc = statusType.optString("description", "")
                val note = run {
                    val notes = comp.optJSONArray("notes")
                    if (notes != null) {
                        (0 until notes.length())
                            .mapNotNull { notes.optJSONObject(it)?.optString("headline", "")?.takeIf { h -> h.isNotBlank() } }
                            .firstOrNull()
                    } else null
                } ?: when {
                    statusDesc.contains("penalt", ignoreCase = true) -> "AET (Pens)"
                    statusDesc.contains("aet", ignoreCase = true) || statusDesc.contains("extra time", ignoreCase = true) -> "AET"
                    else -> ""
                }

                var home: JSONObject? = null
                var away: JSONObject? = null
                for (j in 0 until competitors.length()) {
                    val c = competitors.getJSONObject(j)
                    if (c.optString("homeAway") == "home") home = c else away = c
                }
                if (home == null || away == null) continue

                val ht = home.optJSONObject("team") ?: continue
                val at = away.optJSONObject("team") ?: continue

                out.add(MatchEvent(
                    id = event.optString("id", "$i"),
                    homeTeam = ht.optString("shortDisplayName").ifEmpty { ht.optString("displayName") },
                    awayTeam = at.optString("shortDisplayName").ifEmpty { at.optString("displayName") },
                    homeLogoUrl = logoUrl(ht),
                    awayLogoUrl = logoUrl(at),
                    homeScore = home.optString("score", ""),
                    awayScore = away.optString("score", ""),
                    statusState = state,
                    statusDetail = detail,
                    note = note
                ))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun parseSofascoreStandings(json: String): List<StandingEntry> {
        val out = mutableListOf<StandingEntry>()
        try {
            val standings = JSONObject(json).optJSONArray("standings") ?: return out
            val rows = standings.getJSONObject(0).optJSONArray("rows") ?: return out
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val team = row.optJSONObject("team") ?: continue
                val teamId = team.optInt("id", -1)
                out.add(StandingEntry(
                    pos     = row.optInt("position", i + 1),
                    team    = team.optString("shortName").ifEmpty { team.optString("name") },
                    logoUrl = if (teamId > 0) "https://api.sofascore.app/api/v1/team/$teamId/image" else "",
                    played  = row.optInt("matches", 0),
                    won     = row.optInt("wins", 0),
                    drawn   = row.optInt("draws", 0),
                    lost    = row.optInt("losses", 0),
                    gd      = row.optString("scoreDiffFormatted", "0"),
                    points  = row.optInt("points", 0)
                ))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun logoUrl(team: JSONObject): String =
        team.optString("logo").takeIf { it.isNotEmpty() }
            ?: team.optJSONArray("logos")?.optJSONObject(0)?.optString("href")
            ?: ""

    private fun kickoffTime(iso: String): String = try {
        val utcFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).also {
            it.timeZone = TimeZone.getTimeZone("UTC")
        }
        val localFmt = SimpleDateFormat("HH:mm", Locale.UK).also {
            it.timeZone = TimeZone.getDefault()
        }
        localFmt.format(utcFmt.parse(iso)!!)
    } catch (_: Exception) { iso }
}
