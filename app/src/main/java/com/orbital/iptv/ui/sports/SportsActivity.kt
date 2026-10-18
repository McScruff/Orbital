package com.orbital.iptv.ui.sports

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.orbital.iptv.data.repository.XtreamRepository
import com.orbital.iptv.databinding.ActivitySportsBinding
import com.orbital.iptv.ui.nebula.NebulaLiveActivity
import com.orbital.iptv.ui.nebula.NebulaRail
import com.orbital.iptv.ui.nebula.NebulaStyle
import com.orbital.iptv.ui.player.PlayerActivity
import com.orbital.iptv.ui.sports.SportsCatalog.Kind
import com.orbital.iptv.ui.sports.SportsCatalog.Nav
import com.orbital.iptv.ui.sports.SportsCatalog.ViewMode
import com.orbital.iptv.utils.ChannelQueue
import com.orbital.iptv.utils.PrefsManager
import com.orbital.iptv.utils.ThemeManager
import com.orbital.iptv.utils.TickerManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Sports Bar: pick a sport, then (for sports that have them) a sub-category such as a football
 * league, then a view (fixtures, table, standings...). Schedules step with ◀ ▶ by day, by event
 * (a UFC card, an F1 weekend) or by week (boxing).
 *
 * Selecting a team game pins it to the player's scores ticker and Goal Flash (a tick marks it); a
 * long press finds the channels showing it. Anything else finds its channels when selected, except
 * an F1 session, which opens its full classification. The ⚙ chip after the sports chooses which
 * sports are shown.
 *
 * This screen always renders in the NEBULA visual style, regardless of the app's selected theme —
 * the 14-sport engine's UI isn't duplicated per theme, the way simpler screens are.
 */
class SportsActivity : AppCompatActivity() {

    companion object {
        /** Opens on this sport (a SportsCatalog id). */
        const val EXTRA_SPORT_ID = "sport_id"
        /** With these, runs the channel finder for an event straight away (from the home card). */
        const val EXTRA_FIND_A = "find_a"
        const val EXTRA_FIND_B = "find_b"
        const val EXTRA_FIND_START = "find_start"
        const val EXTRA_FIND_TITLE = "find_title"
    }

    private lateinit var b: ActivitySportsBinding
    private lateinit var adapter: SportsAdapter
    private val xtream = XtreamRepository()

    private var sports = SportsCatalog.sports
    private var sportIdx = 0
    // Remembered per sport, so hopping between sports keeps each one where it was.
    private val categoryIdx = mutableMapOf<Int, Int>()
    private val viewIdx = mutableMapOf<Int, Int>()
    // Per sport and competition ("golf:pga"): each golf tour has its own calendar.
    private val eventIdx = mutableMapOf<String, Int>()
    private val calendars = mutableMapOf<String, List<CalendarEvent>>()
    private val calKey get() = "${sport.id}:${category.espnLeague}"

    private val day: Calendar = Calendar.getInstance()
    // Football jumps to the nearest match day when a league is opened, rather than an empty today.
    private var resolveMatchDay = true
    private val weekStart: Calendar = startOfWeek(Calendar.getInstance())

    private val sportTabs = mutableListOf<TextView>()
    private val categoryTabs = mutableListOf<TextView>()
    private val viewTabs = mutableListOf<TextView>()

    private var loadGen = 0
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { load(quiet = true) }

    private val sport get() = sports[sportIdx]
    private val category get() = sport.categories[categoryIdx[sportIdx] ?: 0]
    private val view get() = sport.views[viewIdx[sportIdx] ?: 0]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        b = ActivitySportsBinding.inflate(layoutInflater)
        setContentView(b.root)
        NebulaRail.bind(
            this, b.rail, NebulaRail.Item.SPORTS,
            onHome = { finish() },
            onLive = { startActivity(Intent(this, NebulaLiveActivity::class.java)) }
        )
        b.btnBack.setOnClickListener { NebulaRail.goHome(this) }

        adapter = SportsAdapter(onClick = ::onRowClick, onLongPress = ::onRowLongPress)
        b.rvContent.layoutManager = com.orbital.iptv.ui.nebula.DpadLinearLayoutManager(this)
        b.rvContent.itemAnimator = null
        b.rvContent.adapter = adapter
        refreshPins()
        // Up from the first list row goes back to the view pills, not the nearest pill above it.
        b.rvContent.addOnChildAttachStateChangeListener(object : androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                view.setOnKeyListener { v, keyCode, event ->
                    if (event.action != android.view.KeyEvent.ACTION_DOWN || keyCode != android.view.KeyEvent.KEYCODE_DPAD_UP) return@setOnKeyListener false
                    val pos = b.rvContent.getChildAdapterPosition(v)
                    val first = adapter.currentList.indexOfFirst { it !is SportsRow.Header && !(it is SportsRow.Standing && it.entry.isGroupHeader) }
                    if (pos != first) return@setOnKeyListener false
                    b.rvContent.scrollToPosition(0)
                    focusViewRow() || focusCategoryRow() || sportTabs[sportIdx].requestFocus()
                }
            }
            override fun onChildViewDetachedFromWindow(view: View) {}
        })

        listOf(b.btnPrev, b.btnNext).forEach { btn ->
            btn.background = NebulaStyle.chipBg(resources.displayMetrics.density, false, false)
            btn.setOnFocusChangeListener { _, has ->
                btn.background = NebulaStyle.chipBg(resources.displayMetrics.density, false, has)
            }
        }
        b.btnPrev.setOnClickListener { step(-1) }
        b.btnNext.setOnClickListener { step(+1) }

        listOf(b.btnPrev, b.btnNext).forEach { wireRowKeys(it, Row.VIEW) }
        buildSportTabs(keepId = intent.getStringExtra(EXTRA_SPORT_ID))
        SportsChannels.preload(this)
        handleFind(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_SPORT_ID)?.let { id ->
            sports.indexOfFirst { it.id == id }.takeIf { it >= 0 && it != sportIdx }?.let { selectSport(it) }
        }
        handleFind(intent)
    }

    private fun handleFind(intent: Intent) {
        val a = intent.getStringExtra(EXTRA_FIND_A) ?: return
        val title = intent.getStringExtra(EXTRA_FIND_TITLE) ?: a
        intent.removeExtra(EXTRA_FIND_A)
        b.root.post { findChannels(a, intent.getStringExtra(EXTRA_FIND_B).orEmpty(), intent.getLongExtra(EXTRA_FIND_START, 0L), title) }
    }

    /** The sport chips (only the sports chosen in settings), then the ⚙ settings chip. */
    private fun buildSportTabs(keepId: String?) {
        val hidden = PrefsManager.getHiddenSports(this)
        sports = SportsCatalog.sports.filter { it.id !in hidden }.ifEmpty { SportsCatalog.sports }
        categoryIdx.clear(); viewIdx.clear()
        sportTabs.clear()
        b.sportTabContainer.removeAllViews()
        sports.forEachIndexed { i, s ->
            sportTabs += chip(s.label, large = true) { selectSport(i) }
                .also { b.sportTabContainer.addView(it); wireRowKeys(it, Row.SPORT) }
        }
        chip("⚙", large = true) { chooseSports() }.also {
            it.contentDescription = "Choose sports"
            b.sportTabContainer.addView(it); wireRowKeys(it, Row.SPORT)
            NebulaStyle.chip(it, false)
            it.setOnFocusChangeListener { v, _ -> NebulaStyle.chip(v as TextView, false) }
        }
        selectSport(sports.indexOfFirst { it.id == keepId }.coerceAtLeast(0))
    }

    /** Settings: tick the sports to show. At least one stays on. */
    private fun chooseSports() {
        val all = SportsCatalog.sports
        val hidden = PrefsManager.getHiddenSports(this)
        val checked = BooleanArray(all.size) { all[it].id !in hidden }
        AlertDialog.Builder(this, ThemeManager.dialogStyle())
            .setTitle("Sports to show")
            .setMultiChoiceItems(all.map { it.label }.toTypedArray(), checked) { _, which, on -> checked[which] = on }
            .setPositiveButton("Save") { _, _ ->
                if (checked.none { it }) {
                    Toast.makeText(this, "Pick at least one sport", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                PrefsManager.setHiddenSports(this, all.filterIndexed { i, _ -> !checked[i] }.map { it.id }.toSet())
                buildSportTabs(keepId = sport.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshHandler.removeCallbacksAndMessages(null)
    }

    // ── Pills ─────────────────────────────────────────────────────────────────

    private fun chip(label: String, large: Boolean = false, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = if (large) 14f else 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(((if (large) 20 else 14) * d).toInt(), 0, ((if (large) 20 else 14) * d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ((if (large) 40 else 34) * d).toInt())
                .also { it.marginEnd = (8 * d).toInt() }
            setOnClickListener { onClick() }
            setOnFocusChangeListener { _, _ -> restyleTabs() }
        }
    }

    private fun restyleTabs() {
        sportTabs.forEachIndexed { i, t -> NebulaStyle.chip(t, i == sportIdx) }
        categoryTabs.forEachIndexed { i, t -> NebulaStyle.chip(t, i == (categoryIdx[sportIdx] ?: 0)) }
        viewTabs.forEachIndexed { i, t -> NebulaStyle.chip(t, i == (viewIdx[sportIdx] ?: 0)) }
    }

    // ── D-pad between rows ────────────────────────────────────────────────────
    // Android's own focus search picks the geometrically nearest view, which from the leftmost
    // pill is often the rail. Up/Down instead move row to row: sport → league → view/◀ ▶ → list.

    private enum class Row { SPORT, CATEGORY, VIEW }

    private fun wireRowKeys(v: View, row: Row) {
        v.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> when (row) {
                    Row.SPORT -> focusCategoryRow() || focusViewRow() || focusFirstListRow()
                    Row.CATEGORY -> focusViewRow() || focusFirstListRow()
                    Row.VIEW -> focusFirstListRow()
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> when (row) {
                    Row.SPORT -> false
                    Row.CATEGORY -> sportTabs[sportIdx].requestFocus()
                    Row.VIEW -> focusCategoryRow() || sportTabs[sportIdx].requestFocus()
                }
                else -> false
            }
        }
    }

    private fun focusCategoryRow(): Boolean =
        b.categoryScroll.visibility == View.VISIBLE &&
            categoryTabs.getOrNull(categoryIdx[sportIdx] ?: 0)?.requestFocus() == true

    private fun focusViewRow(): Boolean =
        viewTabs.getOrNull(viewIdx[sportIdx] ?: 0)?.requestFocus() == true ||
            (b.nav.visibility == View.VISIBLE && b.btnNext.requestFocus())

    private fun focusFirstListRow(): Boolean {
        val lm = b.rvContent.layoutManager ?: return false
        for (i in 0 until lm.childCount) {
            val child = lm.getChildAt(i) ?: continue
            if (child.isFocusable && child.requestFocus()) return true
        }
        return false
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    private fun selectSport(i: Int) {
        sportIdx = i
        resolveMatchDay = true
        categoryTabs.clear()
        b.categoryTabContainer.removeAllViews()
        if (sport.categories.size > 1) {
            sport.categories.forEachIndexed { ci, c ->
                categoryTabs += chip(c.tab) { selectCategory(ci) }
                    .also { b.categoryTabContainer.addView(it); wireRowKeys(it, Row.CATEGORY) }
            }
            b.categoryScroll.visibility = View.VISIBLE
        } else {
            b.categoryScroll.visibility = View.GONE
        }
        viewTabs.clear()
        b.viewTabContainer.removeAllViews()
        if (sport.views.size > 1) {
            sport.views.forEachIndexed { vi, v ->
                viewTabs += chip(v.label) { selectView(vi) }
                    .also { b.viewTabContainer.addView(it); wireRowKeys(it, Row.VIEW) }
            }
        }
        restyleTabs()
        load()
    }

    private fun selectCategory(i: Int) {
        categoryIdx[sportIdx] = i
        resolveMatchDay = true
        restyleTabs()
        load()
    }

    private fun selectView(i: Int) {
        viewIdx[sportIdx] = i
        restyleTabs()
        load()
    }

    private fun step(delta: Int) {
        when (sport.nav) {
            Nav.MATCH_DAY -> {
                if (!view.isSchedule) return
                val cat = category
                b.progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val next = try { SportsFeeds.findMatchDay(cat, day, delta, includeFrom = false) } catch (_: Exception) { null }
                    b.progressBar.visibility = View.GONE
                    if (next == null) {
                        Toast.makeText(this@SportsActivity,
                            if (delta > 0) "No later fixtures found" else "No earlier fixtures found", Toast.LENGTH_SHORT).show()
                    } else {
                        day.timeInMillis = next.timeInMillis
                        load()
                    }
                }
                return
            }
            Nav.WEEK -> weekStart.add(Calendar.DAY_OF_YEAR, 7 * delta)
            Nav.EVENT -> {
                val cal = calendars[calKey] ?: return
                eventIdx[calKey] = ((eventIdx[calKey] ?: currentIndex(cal)) + delta).coerceIn(0, cal.lastIndex)
            }
            Nav.NONE -> return
        }
        load()
    }

    // ── Loading ───────────────────────────────────────────────────────────────

    private var quietLoad = false

    private fun load(quiet: Boolean = false) {
        refreshHandler.removeCallbacks(refreshRunnable)
        quietLoad = quiet
        val gen = ++loadGen
        val sportNow = sport
        val cat = category
        val mode = view

        b.nav.visibility = if (mode.isSchedule && sportNow.nav != Nav.NONE) View.VISIBLE else View.INVISIBLE
        b.standingsHeader.visibility = if (sportNow.kind == Kind.TEAM && !mode.isSchedule) View.VISIBLE else View.GONE
        b.headerDrawn.text = cat.table.drawnLabel
        b.headerGd.text = cat.table.gdLabel
        b.headerPts.text = cat.table.ptsLabel
        b.tvSubtitle.text = if (sportNow.categories.size > 1) cat.name else ""
        updateNavLabel()
        if (!quiet) {
            b.progressBar.visibility = View.VISIBLE
            b.tvEmpty.visibility = View.GONE
            adapter.submitList(emptyList())
        }

        lifecycleScope.launch {
            val rows = try {
                fetch(sportNow, cat, mode)
            } catch (e: Exception) {
                if (gen != loadGen) return@launch
                b.progressBar.visibility = View.GONE
                if (!quiet) showEmpty("Couldn't load ${sportNow.label} (${e.message ?: "check your connection"}).")
                return@launch
            }
            if (gen != loadGen) return@launch
            b.progressBar.visibility = View.GONE
            updateNavLabel()
            adapter.submitList(rows)
            if (rows.isEmpty()) showEmpty(emptyMessage(sportNow, mode)) else b.tvEmpty.visibility = View.GONE
            if (!quiet) b.rvContent.scrollToPosition(0)
            // Keep live scores moving: every 30s while something's live, otherwise every 2 min.
            if (mode.isSchedule) {
                val live = rows.any { (it is SportsRow.Match && it.event.statusState == "in") || (it is SportsRow.Item && it.live) }
                refreshHandler.postDelayed(refreshRunnable, if (live) 30_000L else 120_000L)
            }
        }
    }

    private suspend fun fetch(s: SportsCatalog.Sport, cat: SportsCatalog.Category, mode: ViewMode): List<SportsRow> =
        when (s.kind) {
            Kind.TEAM -> when {
                !mode.isSchedule -> SportsFeeds.teamStandings(cat)
                s.nav == Nav.EVENT -> currentEvent(cat, weeks = true)?.let { SportsFeeds.week(cat, it) } ?: emptyList()
                else -> {
                    resolveDay(cat)
                    SportsFeeds.teamScoreboard(cat, day, fresh = quietLoad)
                }
            }
            Kind.TENNIS -> {
                resolveDay(cat)
                SportsFeeds.tennisDay(cat, day, fresh = quietLoad)
            }
            Kind.GOLF -> currentEvent(cat)?.let { SportsFeeds.golfLeaderboard(cat, it) } ?: emptyList()
            Kind.LISTINGS -> SportsFeeds.listings(s)
            Kind.LIVE_NOW -> TodaySports.liveNowRows(this)
            Kind.BOXING -> SportsFeeds.boxingWeek(weekStart)
            Kind.UFC -> currentEvent(cat)?.let { SportsFeeds.ufcCard(cat, it) } ?: emptyList()
            Kind.F1 -> when (mode) {
                ViewMode.DRIVERS -> SportsFeeds.f1Standings(cat, drivers = true)
                ViewMode.TEAMS -> SportsFeeds.f1Standings(cat, drivers = false)
                else -> currentEvent(cat)?.let { SportsFeeds.f1Weekend(cat, it) } ?: emptyList()
            }
        }

    /** On first opening a competition, jumps to its nearest day with fixtures (today if any). */
    private suspend fun resolveDay(cat: SportsCatalog.Category) {
        if (!resolveMatchDay) return
        resolveMatchDay = false
        SportsFeeds.findMatchDay(cat, Calendar.getInstance(), +1, includeFrom = true)
            ?.let { day.timeInMillis = it.timeInMillis }
            ?: run { day.timeInMillis = System.currentTimeMillis() }
    }

    private fun currentIndex(cal: List<CalendarEvent>) =
        if (sport.kind == Kind.GOLF) SportsFeeds.currentIndex(cal, SportsFeeds.GOLF_GRACE_MS) else SportsFeeds.currentIndex(cal)

    /** Loads the season calendar once, and starts on the next (or live) event. */
    private suspend fun currentEvent(cat: SportsCatalog.Category, weeks: Boolean = false): CalendarEvent? {
        val key = calKey
        val cal = calendars[key]
            ?: (if (weeks) SportsFeeds.weeks(cat) else SportsFeeds.calendar(cat)).also { calendars[key] = it }
        if (cal.isEmpty()) return null
        val idx = eventIdx.getOrPut(key) { currentIndex(cal) }
        return cal[idx]
    }

    private fun updateNavLabel() {
        b.tvNavLabel.text = when (sport.nav) {
            Nav.MATCH_DAY -> dayLabel(day)
            Nav.WEEK -> weekLabel()
            Nav.NONE -> ""
            Nav.EVENT -> {
                val cal = calendars[calKey]
                if (cal.isNullOrEmpty()) "Loading..." else {
                    val ev = cal[eventIdx[calKey] ?: currentIndex(cal)]
                    // Date first: long event names get cut off at the end, not the date.
                    "${SimpleDateFormat("d MMM", Locale.UK).format(ev.startMs)}  ·  ${ev.label}"
                }
            }
        }
        val cal = calendars[calKey]
        val idx = eventIdx[calKey]
        b.btnPrev.isEnabled = !(sport.nav == Nav.EVENT && idx == 0)
        b.btnNext.isEnabled = !(sport.nav == Nav.EVENT && cal != null && idx == cal.lastIndex)
        b.btnPrev.alpha = if (b.btnPrev.isEnabled) 1f else 0.35f
        b.btnNext.alpha = if (b.btnNext.isEnabled) 1f else 0.35f
    }

    private fun dayLabel(c: Calendar): String {
        val today = Calendar.getInstance()
        fun offset(n: Int) = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, n) }
        return when {
            sameDay(c, today) -> "Today"
            sameDay(c, offset(-1)) -> "Yesterday"
            sameDay(c, offset(1)) -> "Tomorrow"
            else -> SimpleDateFormat("EEE d MMM", Locale.UK).format(c.time)
        }
    }

    private fun weekLabel(): String {
        if (sameDay(weekStart, startOfWeek(Calendar.getInstance()))) return "This week"
        val end = (weekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 6) }
        val f = SimpleDateFormat("d MMM", Locale.UK)
        return "${f.format(weekStart.time)} – ${f.format(end.time)}"
    }

    private fun sameDay(a: Calendar, c: Calendar) =
        a.get(Calendar.YEAR) == c.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)

    private fun startOfWeek(c: Calendar): Calendar = (c.clone() as Calendar).apply {
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        if (after(c)) add(Calendar.DAY_OF_YEAR, -7)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    private fun emptyMessage(s: SportsCatalog.Sport, mode: ViewMode) = when {
        s.kind == Kind.BOXING -> "No fights listed this week. Try the next week with ▶."
        s.kind == Kind.LISTINGS -> "No ${s.label.lowercase()} listed on TV in the next few days."
        s.kind == Kind.LIVE_NOW -> "Nothing live right now, and nothing starting in the next few hours."
        s.kind == Kind.TENNIS -> "No singles matches on this day. Try ◀ ▶ for other days."
        s.kind == Kind.GOLF -> "No leaderboard for this tournament yet."
        s.kind == Kind.TEAM && mode.isSchedule && s.nav == Nav.EVENT -> "No games listed for this week."
        s.kind == Kind.TEAM && mode.isSchedule -> "No fixtures found. Try ◀ for recent results."
        s.kind == Kind.TEAM -> "No table available for this competition yet."
        mode.isSchedule -> "Nothing listed for this event yet."
        else -> "No standings available yet."
    }

    private fun showEmpty(msg: String) {
        b.tvEmpty.text = msg
        b.tvEmpty.visibility = View.VISIBLE
    }

    // ── Row actions ───────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        // Pins can be cleared elsewhere (finished games are unpinned by the ticker poll).
        if (::adapter.isInitialized) refreshPins()
    }

    private fun refreshPins() {
        adapter.pinnedIds = TickerManager.getSelected(this).map { it.id }.toSet()
    }

    /** Pins or unpins a football/NFL game for the scores ticker and Goal Flash. */
    private fun togglePin(e: MatchEvent) {
        val cat = category
        val wasPinned = TickerManager.isSelected(this, e.id)
        TickerManager.toggle(this, TickerManager.SelectedGame(e.id, cat.espnLeague, e.homeTeam, e.awayTeam, cat.espnSport))
        refreshPins()
        Toast.makeText(this,
            if (wasPinned) "Unpinned ${eventTitle(e)}"
            else "Pinned ${eventTitle(e)}. Turn on SCORES or GOAL FLASH in the player to follow it.",
            Toast.LENGTH_SHORT).show()
    }

    private fun onRowClick(row: SportsRow) {
        when (row) {
            is SportsRow.Match ->
                if (row.event.isFight) findChannels(row.event.homeTeam, row.event.awayTeam, row.event.startMs, eventTitle(row.event))
                else togglePin(row.event)
            is SportsRow.Item -> when {
                row.session != null -> showClassification(row)
                row.searchA.isNotEmpty() -> findChannels(row.searchA, row.searchB, row.startMs, row.title, row.listingSlug)
            }
            else -> {}
        }
    }

    private fun onRowLongPress(row: SportsRow) {
        when (row) {
            is SportsRow.Match -> findChannels(row.event.homeTeam, row.event.awayTeam, row.event.startMs, eventTitle(row.event))
            is SportsRow.Item -> if (row.searchA.isNotEmpty()) findChannels(row.searchA, row.searchB, row.startMs, row.title, row.listingSlug)
            else -> {}
        }
    }

    private fun eventTitle(e: MatchEvent) = "${e.homeTeam} vs ${e.awayTeam}"

    private fun showClassification(row: SportsRow.Item) {
        val s = row.session ?: return
        AlertDialog.Builder(this, ThemeManager.dialogStyle())
            .setTitle("${s.eventName}: ${s.name}")
            .setItems(s.classification.toTypedArray(), null)
            .setPositiveButton("Find channels") { _, _ -> findChannels(row.searchA, row.searchB, row.startMs, s.eventName) }
            .setNegativeButton("Close", null)
            .show()
    }

    // ── Channel finder ────────────────────────────────────────────────────────

    /**
     * Finds where an event is showing. First a quick look at the channels sport-tv-guide.live lists
     * for it (matched to the user's channels); the dialog's "Search more channels" then runs the
     * full search of the TV guide and channel names. With nothing listed it goes straight to that.
     */
    private fun findChannels(sideA: String, sideB: String, startMs: Long, title: String, slug: String = "", deep: Boolean = false) {
        val listingSlug = slug.ifEmpty { sport.listingSlug }
        val creds = PrefsManager.getCredentials(this) ?: run {
            Toast.makeText(this, "Sign in to find channels", Toast.LENGTH_SHORT).show()
            return
        }
        val loading = AlertDialog.Builder(this, ThemeManager.dialogStyle())
            .setTitle("Finding channels")
            .setMessage(if (deep) "$title\n\nSearching your TV guide and channels..." else "$title\n\nChecking the TV listings...")
            .setCancelable(true)
            .show()
        val dayMs = if (startMs > 0) startMs else System.currentTimeMillis()
        lifecycleScope.launch {
            val result = try {
                val streams = SportsChannels.get(this@SportsActivity)
                MatchChannelFinder.find(this@SportsActivity, sideA, sideB, dayMs, streams, listingSlug, deep)
            } catch (_: Exception) {
                MatchChannelFinder.Result(deep = deep)
            }
            if (!loading.isShowing) return@launch
            loading.dismiss()
            if (!deep && result.listedNames.isEmpty()) {
                findChannels(sideA, sideB, startMs, title, slug, deep = true)
                return@launch
            }
            showChannelResults(title, result, creds.serverUrl, creds.username, creds.password,
                onSearchMore = if (deep) null else { { findChannels(sideA, sideB, startMs, title, slug, deep = true) } })
        }
    }

    private fun showChannelResults(
        title: String,
        result: MatchChannelFinder.Result,
        serverUrl: String, username: String, password: String,
        onSearchMore: (() -> Unit)? = null
    ) {
        val dp = resources.displayMetrics.density
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
        }
        scroll.addView(container)
        val zapList = result.allPlayable

        fun sectionHeader(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(0xFF22D3EE.toInt())
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
        }
        fun note(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(0xFFB8B5D6.toInt())
            textSize = 13f
            setPadding((6 * dp).toInt(), (2 * dp).toInt(), 0, (2 * dp).toInt())
        }
        fun channelRow(hit: MatchChannelFinder.Hit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = (4 * dp).toInt() }
            isClickable = true
            isFocusable = true
            background = ThemeManager.focusRowDrawable(dp, 0xFF171531.toInt(), false)
            setOnFocusChangeListener { _, h ->
                background = ThemeManager.focusRowDrawable(dp, 0xFF171531.toInt(), h, focusFillColor = 0xFF2C2764.toInt())
            }
            addView(TextView(this@SportsActivity).apply {
                text = "▶  ${hit.stream.name}"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            })
            if (hit.detail.isNotBlank()) addView(TextView(this@SportsActivity).apply {
                text = hit.detail
                setTextColor(0xFF8A87B0.toInt())
                textSize = 11f
            })
            setOnClickListener {
                ChannelQueue.entries = zapList.map { ChannelQueue.Entry(it.stream.streamId, it.stream.name, it.stream.num ?: -1) }
                ChannelQueue.currentIndex = zapList.indexOfFirst { it.stream.streamId == hit.stream.streamId }.coerceAtLeast(0)
                startActivity(Intent(this@SportsActivity, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_STREAM_URL, xtream.buildStreamUrl(serverUrl, username, password, hit.stream.streamId))
                    putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, hit.stream.name)
                    putExtra(PlayerActivity.EXTRA_STREAM_ID, hit.stream.streamId)
                    putExtra(PlayerActivity.EXTRA_IS_LIVE, true)
                    hit.stream.num?.let { putExtra(PlayerActivity.EXTRA_CHANNEL_NUM, it) }
                })
            }
        }

        // sport-tv-guide's channels first (the broadcasters), then the full search's finds.
        if (result.listedNames.isNotEmpty()) {
            container.addView(sectionHeader("LISTED ON SPORT-TV-GUIDE"))
            container.addView(note(result.listedNames.joinToString("  •  ")))
            result.listedPlayable.forEach { container.addView(channelRow(it)) }
            if (result.listedPlayable.isEmpty()) container.addView(note("None of these are on your server."))
        }
        val listedIds = result.listedPlayable.map { it.stream.streamId }.toSet()
        val epg = result.epgHits.filter { it.stream.streamId !in listedIds }
        val names = result.nameHits.filter { it.stream.streamId !in listedIds }
        if (epg.isNotEmpty()) {
            container.addView(sectionHeader("ON YOUR TV GUIDE"))
            epg.forEach { container.addView(channelRow(it)) }
        }
        if (names.isNotEmpty()) {
            container.addView(sectionHeader("CHANNELS NAMED AFTER THIS EVENT"))
            names.forEach { container.addView(channelRow(it)) }
        }
        if (result.deep && zapList.isEmpty()) {
            container.addView(note("No channel showing this was found in your TV guide."))
            container.addView(note("Tip: run Full EPG refresh in Settings so the guide covers every channel, then try again."))
        }
        if (result.deep && result.listedNames.isNotEmpty() && epg.isEmpty() && names.isEmpty()) {
            container.addView(note("The full search found no other channels."))
        }
        if (zapList.size > 1) container.addView(note("\nUse CH UP / CH DOWN while watching to zap between these channels."))

        val dialog = AlertDialog.Builder(this, ThemeManager.dialogStyle())
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton("Close", null)
        if (onSearchMore != null) dialog.setPositiveButton("Search more channels") { _, _ -> onSearchMore() }
        dialog.show()
    }
}
