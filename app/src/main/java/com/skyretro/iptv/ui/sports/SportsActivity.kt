package com.skyretro.iptv.ui.sports

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.skyretro.iptv.databinding.ActivitySportsBinding
import com.skyretro.iptv.utils.TickerManager
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

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { _, h ->
            binding.btnBack.setBackgroundColor(if (h) 0xFF2D6090.toInt() else 0xFF1E3D72.toInt())
        }

        matchAdapter = MatchAdapter { game ->
            val sg = TickerManager.SelectedGame(game.id, leagues[leagueIdx].espnId, game.homeTeam, game.awayTeam)
            TickerManager.toggle(this, sg)
            matchAdapter.selectedIds = TickerManager.getSelected(this).map { it.id }.toSet()
        }

        setupLeagueTabs()
        setupDateNav()
        setupModeToggle()

        binding.rvContent.layoutManager = LinearLayoutManager(this)
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
                    statusDetail = detail
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
