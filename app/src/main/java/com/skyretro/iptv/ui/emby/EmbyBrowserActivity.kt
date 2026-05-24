package com.skyretro.iptv.ui.emby

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.skyretro.iptv.data.emby.EmbyItem
import com.skyretro.iptv.data.emby.EmbyRepository
import com.skyretro.iptv.databinding.ActivityEmbyBrowserBinding
import com.skyretro.iptv.ui.player.PlayerActivity
import com.skyretro.iptv.utils.EmbyPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EmbyBrowserActivity : AppCompatActivity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityEmbyBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = EmbyPrefsManager.getSession(this)
        if (session == null) { finish(); return }

        binding.tvUsername.text = session!!.username

        adapter = EmbyMediaAdapter { item -> onItemClicked(item) }
        binding.recyclerView.layoutManager = GridLayoutManager(this, 4)
        binding.recyclerView.adapter = adapter

        setupTabBar()
        setupSearch()

        binding.btnBack.setOnClickListener { handleBack() }
        binding.btnBack.setOnFocusChangeListener { _, hasFocus ->
            binding.btnBack.setBackgroundColor(if (hasFocus) 0xFF2D6090.toInt() else 0xFF1A3560.toInt())
        }

        binding.btnLogout.setOnClickListener {
            EmbyPrefsManager.clearSession(this)
            startActivity(Intent(this, EmbyLoginActivity::class.java))
            finish()
        }

        selectTab(Tab.CONTINUE)
    }

    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
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

        val s = session ?: return
        when (tab) {
            Tab.CONTINUE  -> loadContinue(s)
            Tab.MOVIES    -> loadMovies(s)
            Tab.TV_SHOWS  -> loadSeries(s)
            Tab.MUSIC     -> loadMusic(s)
        }
    }

    private fun Tab.label() = when (this) {
        Tab.CONTINUE  -> "EMBY — CONTINUE WATCHING"
        Tab.MOVIES    -> "EMBY — MOVIES"
        Tab.TV_SHOWS  -> "EMBY — TV SHOWS"
        Tab.MUSIC     -> "EMBY — MUSIC"
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

    private fun fetch(block: suspend () -> List<EmbyItem>) {
        setLoading(true)
        binding.tvEmpty.visibility = View.GONE
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { runCatching { block() }.getOrDefault(emptyList()) }
            setLoading(false)
            if (items.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                adapter.submitList(emptyList()) { _ -> null }
            } else {
                binding.tvEmpty.visibility = View.GONE
                adapter.submitList(items) { item -> artworkUrlFor(item) }
                binding.recyclerView.scrollToPosition(0)
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

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
