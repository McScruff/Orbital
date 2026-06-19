package com.orbital.iptv.ui.emby

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
import com.orbital.iptv.data.emby.EmbyItem
import com.orbital.iptv.data.emby.EmbyRepository
import com.orbital.iptv.databinding.ActivityEmbyBrowserBinding
import com.orbital.iptv.ui.player.PlayerActivity
import com.orbital.iptv.utils.EmbyPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.orbital.iptv.utils.ThemeManager

class EmbyBrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERIES_ID        = "emby_series_id"
        const val EXTRA_SERIES_NAME      = "emby_series_name"
        const val EXTRA_SERIES_IMAGE_TAG = "emby_series_image_tag"
        private const val CONTINUE_ID    = "__continue__"
    }

    private enum class Tab { CONTINUE, MOVIES, TV_SHOWS, MUSIC }

    private sealed class Level {
        object Root : Level()
        data class Seasons(val series: EmbyItem) : Level()
        data class Episodes(val series: EmbyItem, val season: EmbyItem) : Level()
    }

    private lateinit var binding: ActivityEmbyBrowserBinding
    private lateinit var adapter: EmbyMediaAdapter
    private val repository = EmbyRepository()

    private var session: EmbyPrefsManager.EmbySession? = null
    private var currentTab = Tab.CONTINUE
    private var currentLevel: Level = Level.Root
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private var fetchJob: Job? = null

    private var allEmbyGenres: List<EmbyItem> = emptyList()
    private var selectedGenreId: String? = null   // null=ALL, "__continue__"=Continue Watching, else genre id
    private var currentGenreTypes: String = "Movie"
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityEmbyBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.load(this)
        val p = ThemeManager.palette()
        binding.root.setBackgroundColor(p.bgPrimary)
        binding.layoutHeader?.setBackgroundColor(p.bgHeader)
        binding.viewAccent?.setBackgroundColor(p.accent)
        binding.tabBar?.setBackgroundColor(p.bgMid)

        session = EmbyPrefsManager.getSession(this)
        if (session == null) { finish(); return }

        binding.tvUsername.text = session!!.username

        adapter = EmbyMediaAdapter { item -> onItemClicked(item) }
        binding.recyclerView.layoutManager = GridLayoutManager(this, 4)
        binding.recyclerView.adapter = adapter

        setupTabBar()
        setupSearch()

        binding.btnBack.setOnClickListener { if (!handleBack()) finish() }
        binding.btnBack.setOnFocusChangeListener { _, hasFocus ->
            binding.btnBack.setBackgroundColor(if (hasFocus) 0xFF2D6090.toInt() else 0xFF1A3560.toInt())
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!handleBack()) finish()
            }
        })

        binding.btnLogout.setOnClickListener {
            EmbyPrefsManager.clearSession(this)
            startActivity(Intent(this, EmbyLoginActivity::class.java))
            finish()
        }

        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID)
        if (seriesId != null) {
            val seriesName = intent.getStringExtra(EXTRA_SERIES_NAME) ?: ""
            val imageTag   = intent.getStringExtra(EXTRA_SERIES_IMAGE_TAG)
            selectTab(Tab.TV_SHOWS)
            val seriesItem = EmbyItem(
                id = seriesId, name = seriesName, type = "Series",
                imageTags = if (imageTag != null) mapOf("Primary" to imageTag) else null
            )
            loadSeasons(seriesItem)
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
                        if (focused == binding.etSearch) {
                            // Search bar → category panel (if visible) or grid
                            if (binding.categoryScroll.visibility == View.VISIBLE) {
                                binding.categoryPanel.getChildAt(0)?.requestFocus()
                            } else {
                                val lm = binding.recyclerView.layoutManager as? GridLayoutManager
                                lm?.findViewByPosition(0)?.requestFocus() ?: binding.recyclerView.requestFocus()
                            }
                        } else {
                            // Tabs / header buttons → search bar
                            binding.etSearch.requestFocus()
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
                            binding.etSearch.requestFocus()
                            return true
                        }
                    }
                    if (isCategoryPanelChild(focused)) {
                        val panel = binding.categoryPanel
                        if (panel.childCount > 0 && panel.getChildAt(0) == focused) {
                            binding.etSearch.requestFocus()
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
            is Level.Episodes -> {
                currentLevel = Level.Seasons(lvl.series)
                loadSeasons(lvl.series)
                true
            }
            is Level.Seasons -> {
                currentLevel = Level.Root
                selectTab(Tab.TV_SHOWS)
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
                if (query.isBlank()) {
                    selectTab(currentTab)
                    return
                }
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
        fetch {
            repository.getItems(
                serverUrl = s.serverUrl, userId = s.userId, token = s.token,
                types = "Movie,Series,Episode,Audio", recursive = true,
                sortBy = "SortName", limit = 50, searchTerm = query
            ).getOrDefault(emptyList())
        }
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
                    hasFocus -> 0xFF2D6090.toInt()
                    selected -> 0xFF00557A.toInt()
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
            view.setBackgroundColor(if (t == tab) 0xFF00557A.toInt() else 0x00000000)
            view.setTextColor(if (t == tab) 0xFFFFCC00.toInt() else 0xFFFFFFFF.toInt())
        }

        binding.tvBreadcrumb.text = tab.label()

        val sidebarVisible = tab == Tab.MOVIES || tab == Tab.TV_SHOWS
        binding.categoryScroll.visibility = if (sidebarVisible) View.VISIBLE else View.GONE
        binding.categoryDivider.visibility = if (sidebarVisible) View.VISIBLE else View.GONE
        if (!sidebarVisible) { selectedGenreId = null }

        val s = session ?: return
        when (tab) {
            Tab.CONTINUE  -> loadContinue(s)
            Tab.MOVIES    -> {
                selectedGenreId = null
                currentGenreTypes = "Movie"
                loadMovies(s)
                loadEmbyGenres(s, "Movie")
            }
            Tab.TV_SHOWS  -> {
                selectedGenreId = null
                currentGenreTypes = "Series"
                loadSeries(s)
                loadEmbyGenres(s, "Series")
            }
            Tab.MUSIC     -> loadMusic(s)
        }
    }

    private fun Tab.label() = when (this) {
        Tab.CONTINUE  -> "EMBY — CONTINUE WATCHING"
        Tab.MOVIES    -> "EMBY — MOVIES"
        Tab.TV_SHOWS  -> "EMBY — TV SHOWS"
        Tab.MUSIC     -> "EMBY — MUSIC"
    }

    private fun loadEmbyGenres(s: EmbyPrefsManager.EmbySession, types: String) {
        lifecycleScope.launch {
            val genres = withContext(Dispatchers.IO) {
                repository.getGenres(s.serverUrl, s.userId, s.token, types).getOrDefault(emptyList())
            }
            allEmbyGenres = genres
            buildGenreSidebar(genres, types)
        }
    }

    private fun buildGenreSidebar(genres: List<EmbyItem>, types: String) {
        val container = binding.categoryPanel
        container.removeAllViews()
        val rowH = (38 * resources.displayMetrics.density).toInt()
        val pad  = (12 * resources.displayMetrics.density).toInt()
        val isMovies = types == "Movie"

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
                setBackgroundColor(if (isSelected) 0xFF00AACC.toInt() else normalBg)
                setTextColor(if (isSelected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                setOnFocusChangeListener { _, hasFocus ->
                    if (!isSelected)
                        setBackgroundColor(if (hasFocus) 0xFF2A5A8A.toInt() else normalBg)
                }
                setOnClickListener { onClick() }
            }

        container.addView(makeButton("▶ CONTINUE WATCHING", selectedGenreId == CONTINUE_ID, 0) {
            selectedGenreId = CONTINUE_ID
            val s = session ?: return@makeButton
            val filterType = if (isMovies) "Movie" else "Episode"
            val section = if (isMovies) "MOVIES" else "TV SHOWS"
            binding.tvBreadcrumb.text = "EMBY — $section — CONTINUE WATCHING"
            fetch("EMBY") {
                repository.getResumeItems(s.serverUrl, s.userId, s.token)
                    .getOrDefault(emptyList())
                    .filter { it.type == filterType }
            }
            buildGenreSidebar(allEmbyGenres, types)
        })

        container.addView(makeButton(if (isMovies) "ALL MOVIES" else "ALL TV SHOWS", selectedGenreId == null, 1) {
            selectedGenreId = null
            val s = session ?: return@makeButton
            if (isMovies) loadMovies(s) else loadSeries(s)
            binding.tvBreadcrumb.text = if (isMovies) Tab.MOVIES.label() else Tab.TV_SHOWS.label()
            buildGenreSidebar(allEmbyGenres, types)
        })

        genres.forEachIndexed { i, genre ->
            container.addView(makeButton(genre.name.uppercase(), selectedGenreId == genre.id, i + 2) {
                selectedGenreId = genre.id
                val s = session ?: return@makeButton
                val section = if (isMovies) "MOVIES" else "TV SHOWS"
                binding.tvBreadcrumb.text = "EMBY — $section — ${genre.name.uppercase()}"
                fetch {
                    repository.getItems(
                        serverUrl = s.serverUrl, userId = s.userId, token = s.token,
                        types = types, recursive = true, sortBy = "SortName",
                        genreIds = genre.id
                    ).getOrDefault(emptyList())
                }
                buildGenreSidebar(allEmbyGenres, types)
            })
        }
    }

    private fun loadContinue(s: EmbyPrefsManager.EmbySession) {
        fetch {
            repository.getResumeItems(s.serverUrl, s.userId, s.token).getOrDefault(emptyList())
        }
    }

    private fun loadMovies(s: EmbyPrefsManager.EmbySession) {
        fetch {
            repository.getItems(
                serverUrl = s.serverUrl, userId = s.userId, token = s.token,
                types = "Movie", recursive = true, sortBy = "SortName"
            ).getOrDefault(emptyList())
        }
    }

    private fun loadSeries(s: EmbyPrefsManager.EmbySession) {
        fetch {
            repository.getItems(
                serverUrl = s.serverUrl, userId = s.userId, token = s.token,
                types = "Series", recursive = true, sortBy = "SortName"
            ).getOrDefault(emptyList())
        }
    }

    private fun loadMusic(s: EmbyPrefsManager.EmbySession) {
        fetch {
            repository.getItems(
                serverUrl = s.serverUrl, userId = s.userId, token = s.token,
                types = "MusicAlbum", recursive = true, sortBy = "SortName"
            ).getOrDefault(emptyList())
        }
    }

    private fun loadSeasons(series: EmbyItem) {
        val s = session ?: return
        currentLevel = Level.Seasons(series)
        binding.tvBreadcrumb.text = series.name.uppercase()
        fetch {
            repository.getSeasons(s.serverUrl, s.userId, s.token, series.id).getOrDefault(emptyList())
        }
    }

    private fun loadEpisodes(series: EmbyItem, season: EmbyItem) {
        val s = session ?: return
        currentLevel = Level.Episodes(series, season)
        binding.tvBreadcrumb.text = "${series.name.uppercase()}  —  ${season.name.uppercase()}"
        fetch {
            repository.getEpisodes(s.serverUrl, s.userId, s.token, series.id, season.id).getOrDefault(emptyList())
        }
    }

    private fun fetch(sourceBadge: String? = null, block: suspend () -> List<EmbyItem>) {
        fetchJob?.cancel()
        setLoading(true)
        binding.tvEmpty.visibility = View.GONE
        fetchJob = lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { runCatching { block() }.getOrDefault(emptyList()) }
            setLoading(false)
            if (items.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                adapter.submitList(emptyList()) { _ -> null }
            } else {
                binding.tvEmpty.visibility = View.GONE
                adapter.submitList(items, sourceBadge) { item -> artworkUrlFor(item) }
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

    private fun artworkUrlFor(item: EmbyItem): String? {
        val s = session ?: return null
        val tag = item.imageTags?.get("Primary")
        if (tag != null) return repository.buildArtworkUrl(s.serverUrl, item.id, s.token, tag)
        // Fall back to series artwork for episodes
        if (item.type == "Episode" && item.seriesId != null && item.seriesPrimaryImageTag != null) {
            return repository.buildArtworkUrl(s.serverUrl, item.seriesId, s.token, item.seriesPrimaryImageTag)
        }
        return null
    }

    private fun onItemClicked(item: EmbyItem) {
        when (item.type) {
            "Series" -> loadSeasons(item)
            "Season" -> {
                val lvl = currentLevel
                val series = if (lvl is Level.Seasons) lvl.series else return
                loadEpisodes(series, item)
            }
            "Movie", "Episode" -> playItem(item)
            "MusicAlbum" -> {
                val s = session ?: return
                val albumId = item.id
                fetch {
                    repository.getItems(
                        serverUrl = s.serverUrl, userId = s.userId, token = s.token,
                        parentId = albumId, types = "Audio", recursive = false, sortBy = "SortParentId,IndexNumber"
                    ).getOrDefault(emptyList())
                }
                binding.tvBreadcrumb.text = item.name.uppercase()
            }
            "Audio" -> playItem(item)
            else -> {
                // Try drilling into the item as a collection
                val s = session ?: return
                fetch {
                    repository.getItems(
                        serverUrl = s.serverUrl, userId = s.userId, token = s.token,
                        parentId = item.id, types = null, recursive = false, sortBy = "SortName"
                    ).getOrDefault(emptyList())
                }
            }
        }
    }

    private fun playItem(item: EmbyItem) {
        val s = session ?: return
        val resumeMs = item.resumeMs()
        val displayName = when (item.type) {
            "Episode" -> {
                val s2 = item.parentIndexNumber?.let { "S$it" } ?: ""
                val e  = item.indexNumber?.let { "E%02d".format(it) } ?: ""
                "${item.seriesName ?: ""} $s2$e — ${item.name}".trim()
            }
            else -> item.name
        }

        lifecycleScope.launch {
            val streamUrl = withContext(Dispatchers.IO) {
                val source = repository.getPlaybackInfo(s.serverUrl, s.userId, s.token, item.id)
                    .getOrNull()?.mediaSources?.firstOrNull()
                repository.buildStreamUrl(s.serverUrl, item.id, s.token, source)
            }

            val intent = Intent(this@EmbyBrowserActivity, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_STREAM_URL,   streamUrl)
                putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, displayName)
                putExtra(PlayerActivity.EXTRA_STREAM_ID,    -1)
                putExtra(PlayerActivity.EXTRA_IS_LIVE,      false)
                putExtra(PlayerActivity.EXTRA_RESUME_MS,    resumeMs)
                putExtra(PlayerActivity.EXTRA_EMBY_ITEM_ID, item.id)
            }
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) { firstResume = false; return }
        if (currentTab == Tab.CONTINUE) {
            session?.let { loadContinue(it) }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
