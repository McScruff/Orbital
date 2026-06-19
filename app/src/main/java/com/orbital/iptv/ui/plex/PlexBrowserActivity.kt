package com.orbital.iptv.ui.plex

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.orbital.iptv.data.plex.PlexGenre
import com.orbital.iptv.data.plex.PlexItem
import com.orbital.iptv.data.plex.PlexRepository
import com.orbital.iptv.databinding.ActivityPlexBrowserBinding
import com.orbital.iptv.ui.player.PlayerActivity
import com.orbital.iptv.utils.PlexPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.orbital.iptv.utils.ThemeManager
import com.orbital.iptv.R

class PlexBrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SHOW_KEY       = "plex_show_key"
        const val EXTRA_SHOW_TITLE     = "plex_show_title"
        const val EXTRA_SHOW_THUMB     = "plex_show_thumb"
        private const val CONTINUE_KEY = "__continue__"
    }

    private enum class Tab { CONTINUE, MOVIES, TV_SHOWS, MUSIC }

    private sealed class Level {
        object Root : Level()
        data class L1(val parent: PlexItem) : Level()
        data class L2(val grandparent: PlexItem, val parent: PlexItem) : Level()
    }

    private lateinit var binding: ActivityPlexBrowserBinding
    private lateinit var adapter: PlexMediaAdapter
    private val repository = PlexRepository()

    private var session: PlexPrefsManager.PlexSession? = null
    private var currentTab = Tab.CONTINUE
    private var currentLevel: Level = Level.Root
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private var fetchJob: Job? = null

    private var allPlexGenres: List<PlexGenre> = emptyList()
    private var selectedGenreTitle: String? = null  // null=ALL, "__continue__"=Continue Watching, else genre title
    private var currentSidebarType: String = "movie"
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityPlexBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.load(this)
        val p = ThemeManager.palette()
        binding.root.setBackgroundColor(p.bgPrimary)
        binding.root.findViewById<android.view.View>(R.id.layout_header)?.setBackgroundColor(p.bgHeader)
        binding.root.findViewById<android.view.View>(R.id.view_accent)?.setBackgroundColor(p.accent)
        binding.tabBar?.setBackgroundColor(p.bgMid)

        session = PlexPrefsManager.getSession(this)
        if (session == null) { finish(); return }

        binding.tvUsername.text = session!!.username

        adapter = PlexMediaAdapter { item -> onItemClicked(item) }
        binding.recyclerView.layoutManager = GridLayoutManager(this, 4)
        binding.recyclerView.adapter = adapter

        setupTabBar()
        setupSearch()

        binding.btnBack.setOnClickListener { if (!handleBack()) finish() }
        binding.btnBack.setOnFocusChangeListener { _, hasFocus ->
            binding.btnBack.setBackgroundColor(if (hasFocus) 0xFF8B6914.toInt() else 0xFF1A3560.toInt())
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!handleBack()) finish()
            }
        })

        binding.btnLogout.setOnClickListener {
            PlexPrefsManager.clearSession(this)
            startActivity(Intent(this, PlexLoginActivity::class.java))
            finish()
        }

        val showKey = intent.getStringExtra(EXTRA_SHOW_KEY)
        if (showKey != null) {
            val showTitle = intent.getStringExtra(EXTRA_SHOW_TITLE) ?: ""
            val showThumb = intent.getStringExtra(EXTRA_SHOW_THUMB)
            selectTab(Tab.TV_SHOWS)
            val showItem = PlexItem(
                ratingKey = showKey, title = showTitle, type = "show",
                year = null, thumb = showThumb, parentTitle = null,
                grandparentTitle = null, parentIndex = null, index = null,
                duration = null, viewOffset = null, partKey = null
            )
            currentLevel = Level.L1(showItem)
            loadChildren(showItem, showTitle.uppercase())
        } else {
            selectTab(Tab.CONTINUE)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val focused = currentFocus
                    if (focused != null && isHeaderView(focused) && adapter.itemCount > 0) {
                        if (binding.categoryScroll.visibility == View.VISIBLE) {
                            binding.categoryPanel.getChildAt(0)?.requestFocus()
                        } else {
                            val lm = binding.recyclerView.layoutManager as? GridLayoutManager
                            lm?.findViewByPosition(0)?.requestFocus() ?: binding.recyclerView.requestFocus()
                        }
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val focused = currentFocus ?: return super.dispatchKeyEvent(event)
                    val vh = binding.recyclerView.findContainingViewHolder(focused)
                    if (vh != null) {
                        val pos = vh.bindingAdapterPosition
                        val spanCount = (binding.recyclerView.layoutManager as? GridLayoutManager)?.spanCount ?: 4
                        if (pos in 0 until spanCount) {
                            binding.tabContinue.requestFocus()
                            return true
                        }
                    }
                    if (isCategoryPanelChild(focused)) {
                        val panel = binding.categoryPanel
                        if (panel.childCount > 0 && panel.getChildAt(0) == focused) {
                            binding.tabContinue.requestFocus()
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val focused = currentFocus ?: return super.dispatchKeyEvent(event)
                    if (isCategoryPanelChild(focused) && adapter.itemCount > 0) {
                        val lm = binding.recyclerView.layoutManager as? GridLayoutManager
                        lm?.findViewByPosition(0)?.requestFocus() ?: binding.recyclerView.requestFocus()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val focused = currentFocus ?: return super.dispatchKeyEvent(event)
                    val vh = binding.recyclerView.findContainingViewHolder(focused)
                    if (vh != null && binding.categoryScroll.visibility == View.VISIBLE) {
                        val pos = vh.bindingAdapterPosition
                        val spanCount = (binding.recyclerView.layoutManager as? GridLayoutManager)?.spanCount ?: 4
                        if (pos % spanCount == 0) {
                            val panel = binding.categoryPanel
                            (0 until panel.childCount).firstOrNull { panel.getChildAt(it).isSelected }
                                ?.let { panel.getChildAt(it).requestFocus() }
                                ?: panel.getChildAt(0)?.requestFocus()
                            return true
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isHeaderView(v: View): Boolean =
        v == binding.btnBack || v == binding.btnLogout ||
        v == binding.tabContinue || v == binding.tabMovies ||
        v == binding.tabTv || v == binding.tabMusic ||
        v == binding.etSearch

    private fun isCategoryPanelChild(v: View): Boolean {
        val panel = binding.categoryPanel
        for (i in 0 until panel.childCount) if (panel.getChildAt(i) == v) return true
        return false
    }

    private fun handleBack(): Boolean {
        return when (val lvl = currentLevel) {
            is Level.L2 -> {
                currentLevel = Level.L1(lvl.grandparent)
                loadChildren(lvl.grandparent, lvl.grandparent.title.uppercase())
                true
            }
            is Level.L1 -> {
                currentLevel = Level.Root
                selectTab(currentTab)
                true
            }
            is Level.Root -> false
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                val query = s?.toString()?.trim() ?: ""
                if (query.isBlank()) { selectTab(currentTab); return }
                val r = Runnable { loadSearch(query) }
                searchRunnable = r
                searchHandler.postDelayed(r, 400)
            }
        })
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                val query = binding.etSearch.text?.toString()?.trim() ?: ""
                if (query.isNotBlank()) loadSearch(query)
                true
            } else false
        }
    }

    private fun loadSearch(query: String) {
        val s = session ?: return
        currentLevel = Level.Root
        binding.tvBreadcrumb.text = "SEARCH: ${query.uppercase()}"
        fetch { repository.search(s.serverUrl, s.token, query) }
    }

    private fun setupTabBar() {
        val tabs = listOf(
            binding.tabContinue to Tab.CONTINUE,
            binding.tabMovies   to Tab.MOVIES,
            binding.tabTv       to Tab.TV_SHOWS,
            binding.tabMusic    to Tab.MUSIC
        )
        tabs.forEach { (view, tab) ->
            view.setOnClickListener {
                currentLevel = Level.Root
                selectTab(tab)
            }
            view.setOnFocusChangeListener { _, hasFocus ->
                val selected = tab == currentTab
                view.setBackgroundColor(when {
                    hasFocus -> 0xFF8B6914.toInt()
                    selected -> 0xFF7A4A00.toInt()
                    else     -> 0x00000000
                })
            }
        }
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab
        currentLevel = Level.Root

        val tabs = listOf(
            binding.tabContinue to Tab.CONTINUE,
            binding.tabMovies   to Tab.MOVIES,
            binding.tabTv       to Tab.TV_SHOWS,
            binding.tabMusic    to Tab.MUSIC
        )
        tabs.forEach { (view, t) ->
            view.setBackgroundColor(if (t == tab) 0xFF7A4A00.toInt() else 0x00000000)
            view.setTextColor(if (t == tab) 0xFFE5A00D.toInt() else 0xFFFFFFFF.toInt())
        }

        binding.tvBreadcrumb.text = tab.label()

        val sidebarVisible = tab == Tab.MOVIES || tab == Tab.TV_SHOWS
        binding.categoryScroll.visibility = if (sidebarVisible) View.VISIBLE else View.GONE
        binding.categoryDivider.visibility = if (sidebarVisible) View.VISIBLE else View.GONE
        if (!sidebarVisible) { selectedGenreTitle = null }

        val s = session ?: return
        when (tab) {
            Tab.CONTINUE  -> fetch { repository.getOnDeck(s.serverUrl, s.token) }
            Tab.MOVIES    -> {
                selectedGenreTitle = null
                currentSidebarType = "movie"
                fetch { repository.getMovies(s.serverUrl, s.token) }
                loadPlexGenres(s, "movie")
            }
            Tab.TV_SHOWS  -> {
                selectedGenreTitle = null
                currentSidebarType = "show"
                fetch { repository.getShows(s.serverUrl, s.token) }
                loadPlexGenres(s, "show")
            }
            Tab.MUSIC     -> fetch { repository.getArtists(s.serverUrl, s.token) }
        }
    }

    private fun Tab.label() = when (this) {
        Tab.CONTINUE  -> "PLEX — CONTINUE WATCHING"
        Tab.MOVIES    -> "PLEX — MOVIES"
        Tab.TV_SHOWS  -> "PLEX — TV SHOWS"
        Tab.MUSIC     -> "PLEX — MUSIC"
    }

    private fun loadPlexGenres(s: PlexPrefsManager.PlexSession, sectionType: String) {
        lifecycleScope.launch {
            val genres = withContext(Dispatchers.IO) {
                runCatching {
                    if (sectionType == "movie") repository.getMovieGenres(s.serverUrl, s.token)
                    else repository.getShowGenres(s.serverUrl, s.token)
                }.getOrDefault(emptyList())
            }
            allPlexGenres = genres
            buildGenreSidebar(genres, sectionType)
        }
    }

    private fun buildGenreSidebar(genres: List<PlexGenre>, sectionType: String) {
        val container = binding.categoryPanel
        container.removeAllViews()
        val rowH = (38 * resources.displayMetrics.density).toInt()
        val pad  = (12 * resources.displayMetrics.density).toInt()
        val isMovies = sectionType == "movie"

        fun makeButton(label: String, isSelected: Boolean, i: Int, onClick: () -> Unit): TextView =
            TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, 0, 0, 0)
                text = label
                textSize = 11f
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                isClickable = true; isFocusable = true
                val normalBg = if (i % 2 == 0) 0xFF1A3A6A.toInt() else 0xFF0D1B35.toInt()
                setBackgroundColor(if (isSelected) 0xFFE5A00D.toInt() else normalBg)
                setTextColor(if (isSelected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                setOnFocusChangeListener { _, hasFocus ->
                    if (!isSelected)
                        setBackgroundColor(if (hasFocus) 0xFF2A5A8A.toInt() else normalBg)
                }
                setOnClickListener { onClick() }
            }

        container.addView(makeButton("▶ CONTINUE WATCHING", selectedGenreTitle == CONTINUE_KEY, 0) {
            selectedGenreTitle = CONTINUE_KEY
            val s = session ?: return@makeButton
            val filterType = if (isMovies) "movie" else "episode"
            val section = if (isMovies) "MOVIES" else "TV SHOWS"
            binding.tvBreadcrumb.text = "PLEX — $section — CONTINUE WATCHING"
            fetch("PLEX") {
                repository.getOnDeck(s.serverUrl, s.token).map { items ->
                    items.filter { it.type == filterType }
                }
            }
            buildGenreSidebar(allPlexGenres, sectionType)
        })

        container.addView(makeButton(if (isMovies) "ALL MOVIES" else "ALL TV SHOWS", selectedGenreTitle == null, 1) {
            selectedGenreTitle = null
            val s = session ?: return@makeButton
            if (isMovies) fetch { repository.getMovies(s.serverUrl, s.token) }
            else fetch { repository.getShows(s.serverUrl, s.token) }
            binding.tvBreadcrumb.text = if (isMovies) Tab.MOVIES.label() else Tab.TV_SHOWS.label()
            buildGenreSidebar(allPlexGenres, sectionType)
        })

        genres.forEachIndexed { i, genre ->
            container.addView(makeButton(genre.title.uppercase(), selectedGenreTitle == genre.title, i + 2) {
                selectedGenreTitle = genre.title
                val s = session ?: return@makeButton
                val section = if (isMovies) "MOVIES" else "TV SHOWS"
                binding.tvBreadcrumb.text = "PLEX — $section — ${genre.title.uppercase()}"
                if (isMovies) fetch { repository.getMoviesByGenreName(s.serverUrl, s.token, genre.title) }
                else fetch { repository.getShowsByGenreName(s.serverUrl, s.token, genre.title) }
                buildGenreSidebar(allPlexGenres, sectionType)
            })
        }
    }

    private fun loadChildren(item: PlexItem, breadcrumb: String) {
        val s = session ?: return
        binding.tvBreadcrumb.text = breadcrumb
        fetch { repository.getChildren(s.serverUrl, s.token, item.ratingKey) }
    }

    private fun fetch(sourceBadge: String? = null, block: suspend () -> Result<List<PlexItem>>) {
        fetchJob?.cancel()
        setLoading(true)
        binding.tvEmpty.visibility = View.GONE
        fetchJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block().getOrThrow() } }
            setLoading(false)
            result.onFailure { err ->
                binding.tvEmpty.text = "Error: ${err.message?.take(160) ?: "unknown error"}"
                binding.tvEmpty.visibility = View.VISIBLE
                adapter.submitList(emptyList()) { null }
            }
            result.onSuccess { items ->
                if (items.isEmpty()) {
                    binding.tvEmpty.text = "No content found"
                    binding.tvEmpty.visibility = View.VISIBLE
                    adapter.submitList(emptyList()) { null }
                } else {
                    binding.tvEmpty.visibility = View.GONE
                    adapter.submitList(items, sourceBadge) { item -> thumbUrlFor(item) }
                    binding.recyclerView.scrollToPosition(0)
                    binding.recyclerView.post {
                        if (currentFocus != binding.etSearch) {
                            (binding.recyclerView.layoutManager as? GridLayoutManager)
                                ?.findViewByPosition(0)?.requestFocus()
                                ?: binding.recyclerView.requestFocus()
                        }
                    }
                }
            }
        }
    }

    private fun thumbUrlFor(item: PlexItem): String? {
        val s = session ?: return null
        return repository.buildThumbUrl(s.serverUrl, item.thumb, s.token)
    }

    private fun onItemClicked(item: PlexItem) {
        when (item.type) {
            "movie", "episode", "track" -> playItem(item)
            "show", "artist", "album" -> {
                val newLevel = when (val lvl = currentLevel) {
                    is Level.Root -> Level.L1(item)
                    is Level.L1   -> Level.L2(lvl.parent, item)
                    is Level.L2   -> Level.L2(lvl.parent, item)
                }
                currentLevel = newLevel
                val breadcrumb = when (newLevel) {
                    is Level.L1 -> newLevel.parent.title.uppercase()
                    is Level.L2 -> "${newLevel.grandparent.title.uppercase()}  —  ${newLevel.parent.title.uppercase()}"
                    is Level.Root -> ""
                }
                loadChildren(item, breadcrumb)
            }
            "season" -> {
                val lvl = currentLevel
                val grandparent = if (lvl is Level.L1) lvl.parent else item
                currentLevel = Level.L2(grandparent, item)
                val breadcrumb = "${grandparent.title.uppercase()}  —  ${item.title.uppercase()}"
                loadChildren(item, breadcrumb)
            }
            else -> {
                val newLevel = when (val lvl = currentLevel) {
                    is Level.Root -> Level.L1(item)
                    is Level.L1   -> Level.L2(lvl.parent, item)
                    is Level.L2   -> Level.L2(lvl.parent, item)
                }
                currentLevel = newLevel
                loadChildren(item, item.title.uppercase())
            }
        }
    }

    private fun playItem(item: PlexItem) {
        val s = session ?: return
        val partKey = item.partKey ?: return
        val streamUrl = repository.buildStreamUrl(s.serverUrl, partKey, s.token)

        val displayName = when (item.type) {
            "episode" -> {
                val sn = item.parentIndex?.let { "S$it" } ?: ""
                val ep = item.index?.let { "E%02d".format(it) } ?: ""
                "${item.grandparentTitle ?: ""} $sn$ep — ${item.title}".trim()
            }
            else -> item.title
        }

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL,      streamUrl)
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME,    displayName)
            putExtra(PlayerActivity.EXTRA_STREAM_ID,       -1)
            putExtra(PlayerActivity.EXTRA_IS_LIVE,         false)
            putExtra(PlayerActivity.EXTRA_RESUME_MS,       item.resumeMs())
            putExtra(PlayerActivity.EXTRA_PLEX_RATING_KEY, item.ratingKey)
            putExtra(PlayerActivity.EXTRA_PLEX_DURATION_MS, item.duration ?: 0L)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) { firstResume = false; return }
        if (currentTab == Tab.CONTINUE) {
            val s = session ?: return
            fetch { repository.getOnDeck(s.serverUrl, s.token) }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
