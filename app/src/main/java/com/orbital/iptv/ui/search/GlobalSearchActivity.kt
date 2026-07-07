package com.orbital.iptv.ui.search

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.orbital.iptv.R
import com.orbital.iptv.data.emby.EmbyItem
import com.orbital.iptv.data.emby.EmbyRepository
import com.orbital.iptv.data.model.LiveStream
import com.orbital.iptv.data.plex.PlexItem
import com.orbital.iptv.data.plex.PlexRepository
import com.orbital.iptv.data.repository.XtreamRepository
import com.orbital.iptv.databinding.ActivityGlobalSearchBinding
import com.orbital.iptv.databinding.ItemGlobalSearchBinding
import com.orbital.iptv.data.model.SeriesStream
import com.orbital.iptv.ui.emby.EmbyBrowserActivity
import com.orbital.iptv.ui.player.PlayerActivity
import com.orbital.iptv.ui.plex.PlexBrowserActivity
import com.orbital.iptv.ui.series.SeriesDetailActivity
import com.orbital.iptv.utils.ContentCache
import com.orbital.iptv.utils.EmbyPrefsManager
import com.orbital.iptv.utils.EpgCache
import com.orbital.iptv.utils.PlexPrefsManager
import com.orbital.iptv.utils.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicInteger
import com.orbital.iptv.utils.ThemeManager

// ── Data models ───────────────────────────────────────────────────────────────

sealed class SearchSource {
    abstract val label: String
    abstract val thumbUrl: String?

    data class Iptv(
        val serverName: String,
        val streamUrl: String,
        val title: String,
        override val thumbUrl: String?
    ) : SearchSource() {
        override val label get() = serverName
    }

    data class Emby(
        val itemId: String,
        val title: String,
        val resumeMs: Long,
        val serverUrl: String,
        val userId: String,
        val token: String,
        override val thumbUrl: String?,
        val resolution: String? = null
    ) : SearchSource() {
        override val label = "EMBY"
    }

    data class Plex(
        val item: PlexItem,
        val serverUrl: String,
        val token: String,
        override val thumbUrl: String?,
        val resolution: String? = null
    ) : SearchSource() {
        override val label = "PLEX"
    }

    data class IptvSeries(
        val show: SeriesStream,
        val serverUrl: String,
        val username: String,
        val password: String,
        val serverName: String,
        override val thumbUrl: String?
    ) : SearchSource() {
        override val label get() = serverName
    }

    data class EmbyShow(
        val item: EmbyItem,
        val serverUrl: String,
        val userId: String,
        val token: String,
        override val thumbUrl: String?
    ) : SearchSource() {
        override val label = "EMBY"
    }

    data class PlexShow(
        val item: PlexItem,
        val serverUrl: String,
        val token: String,
        override val thumbUrl: String?
    ) : SearchSource() {
        override val label = "PLEX"
    }

    data class LiveChannel(
        val streamId: Int,
        val streamUrl: String,
        val channelName: String,
        val serverName: String,
        val epgTitle: String?,
        override val thumbUrl: String?
    ) : SearchSource() {
        override val label = "LIVE TV"
    }
}

enum class ContentType { MOVIE, SERIES, TV }
enum class SearchFilter { ALL, TV, MOVIES, SERIES }

data class SearchResultItem(
    val title: String,
    val year: String?,
    val thumbUrl: String?,
    val sources: List<SearchSource>,
    val contentType: ContentType = ContentType.MOVIE
)

private fun formatPlexResolution(raw: String?): String? = when (raw?.lowercase()) {
    "4k", "2160", "uhd" -> "4K"
    "1080"              -> "1080p"
    "720"               -> "720p"
    "480", "sd"         -> "480p"
    else                -> null
}

// ── Adapter ───────────────────────────────────────────────────────────────────

class GlobalSearchAdapter(
    private val onClick: (SearchResultItem) -> Unit
) : ListAdapter<SearchResultItem, GlobalSearchAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SearchResultItem>() {
            override fun areItemsTheSame(a: SearchResultItem, b: SearchResultItem) =
                a.title == b.title && a.contentType == b.contentType
            override fun areContentsTheSame(a: SearchResultItem, b: SearchResultItem) = a == b
        }
    }

    inner class VH(val binding: ItemGlobalSearchBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemGlobalSearchBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        val vh = VH(b)
        b.root.setOnClickListener { onClick(getItem(vh.bindingAdapterPosition)) }
        b.root.setOnFocusChangeListener { _, hasFocus ->
            val p = ThemeManager.palette()
            b.root.setBackgroundColor(if (hasFocus) p.focus else p.bgPrimary)
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding

        b.tvTitle.text = item.title
        val resolution = item.sources.firstNotNullOfOrNull {
            when (it) {
                is SearchSource.Emby -> it.resolution
                is SearchSource.Plex -> formatPlexResolution(it.resolution)
                else                 -> null
            }
        }
        b.tvSubtitle.text = when (item.contentType) {
            ContentType.TV -> {
                val ch = item.sources.filterIsInstance<SearchSource.LiveChannel>().firstOrNull()
                buildList {
                    add("LIVE TV")
                    ch?.epgTitle?.takeIf { it.isNotBlank() }?.let { add(it) }
                    add(item.sources.joinToString("  ·  ") { it.label })
                }.joinToString("  |  ")
            }
            else -> buildList {
                if (item.contentType == ContentType.SERIES) add("SERIES")
                item.year?.let { add(it) }
                resolution?.let { add(it) }
                add(item.sources.joinToString("  ·  ") { it.label })
            }.joinToString("  |  ")
        }

        // Source badge colours
        b.llBadges.removeAllViews()
        item.sources.forEach { src ->
            val badge = TextView(b.root.context).apply {
                text = when (src) {
                    is SearchSource.Iptv        -> "IPTV"
                    is SearchSource.Emby        -> "EMBY"
                    is SearchSource.Plex        -> "PLEX"
                    is SearchSource.IptvSeries  -> "IPTV"
                    is SearchSource.EmbyShow    -> "EMBY"
                    is SearchSource.PlexShow    -> "PLEX"
                    is SearchSource.LiveChannel -> "LIVE"
                }
                textSize = 8f
                setPadding(6, 2, 6, 2)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(when (src) {
                    is SearchSource.Iptv, is SearchSource.IptvSeries -> 0xFF1565C0.toInt()
                    is SearchSource.Emby, is SearchSource.EmbyShow   -> 0xFF00838F.toInt()
                    is SearchSource.Plex, is SearchSource.PlexShow   -> 0xFFE5A00D.toInt()
                    is SearchSource.LiveChannel                       -> 0xFFAD1457.toInt()
                })
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 2) }
                layoutParams = lp
            }
            b.llBadges.addView(badge)
        }

        // Thumbnail
        val thumb = item.thumbUrl
        if (!thumb.isNullOrBlank()) {
            Glide.with(b.root.context)
                .load(thumb)
                .placeholder(android.R.color.darker_gray)
                .into(b.ivPoster)
        } else {
            Glide.with(b.root.context).clear(b.ivPoster)
            b.ivPoster.setBackgroundColor(ThemeManager.palette().bgMid)
        }
    }
}

// ── Activity ──────────────────────────────────────────────────────────────────

class GlobalSearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGlobalSearchBinding
    private lateinit var adapter: GlobalSearchAdapter

    private val xtream   = XtreamRepository()
    private val embyRepo = EmbyRepository()
    private val plexRepo = PlexRepository()

    private var currentFilter    = SearchFilter.ALL
    private var fullResults      = listOf<SearchResultItem>()
    private var searchJob: Job?  = null
    private var lastSearchQuery  = ""
    private var lastSearchMs     = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityGlobalSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.load(this)
        val p = ThemeManager.palette()
        binding.root.setBackgroundColor(p.bgPrimary)
        binding.root.findViewById<android.view.View>(R.id.layout_header)?.setBackgroundColor(p.bgHeader)
        binding.root.findViewById<android.view.View>(R.id.view_accent)?.setBackgroundColor(p.accent)

        adapter = GlobalSearchAdapter { onResultClicked(it) }
        binding.recyclerView.layoutManager = GridLayoutManager(this, 4)
        binding.recyclerView.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { _, hasFocus ->
            binding.btnBack.setBackgroundColor(if (hasFocus) 0xFF8B6914.toInt() else 0xFF1A3560.toInt())
        }

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO) {
                val q = binding.etSearch.text?.toString()?.trim() ?: ""
                if (q.length >= 2) doSearch(q) else showHint()
                true
            } else false
        }

        // On TV (no touchscreen) suppress the system keyboard — the D-pad keyboard handles input.
        // On phones/tablets keep showSoftInputOnFocus = true so the keyboard appears normally.
        val isTv = !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
        binding.etSearch.showSoftInputOnFocus = !isTv
        setupFilterButtons()
        binding.etSearch.requestFocus()
    }

    private fun setupFilterButtons() {
        listOf(
            binding.btnFilterAll    to SearchFilter.ALL,
            binding.btnFilterTv     to SearchFilter.TV,
            binding.btnFilterMovies to SearchFilter.MOVIES,
            binding.btnFilterSeries to SearchFilter.SERIES
        ).forEach { (btn, filter) ->
            btn.setOnFocusChangeListener { _, hasFocus ->
                if (filter != currentFilter) {
                    val p = ThemeManager.palette()
                    btn.setBackgroundColor(if (hasFocus) p.focus else p.bgHeader)
                }
            }
            btn.setOnClickListener {
                currentFilter = filter
                updateFilterButtons()
                if (fullResults.isNotEmpty()) applyFilter()
            }
        }
        updateFilterButtons()
    }

    private fun updateFilterButtons() {
        val p = ThemeManager.palette()
        listOf(
            binding.btnFilterAll    to SearchFilter.ALL,
            binding.btnFilterTv     to SearchFilter.TV,
            binding.btnFilterMovies to SearchFilter.MOVIES,
            binding.btnFilterSeries to SearchFilter.SERIES
        ).forEach { (btn, filter) ->
            val selected = filter == currentFilter
            btn.setBackgroundColor(if (selected) p.highlight else p.bgHeader)
            btn.setTextColor(if (selected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            val filterButtons = setOf(binding.btnFilterAll, binding.btnFilterTv, binding.btnFilterMovies, binding.btnFilterSeries)
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (focused == binding.etSearch) { showTvKeyboard(); return true }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (focused == binding.etSearch || focused == binding.btnBack) {
                        binding.btnFilterAll.requestFocus()
                        return true
                    }
                    if (focused in filterButtons) {
                        val lm = binding.recyclerView.layoutManager as? GridLayoutManager
                        lm?.findViewByPosition(0)?.requestFocus() ?: binding.recyclerView.requestFocus()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (focused in filterButtons) {
                        binding.etSearch.requestFocus()
                        return true
                    }
                    val vh = binding.recyclerView.findContainingViewHolder(focused ?: return super.dispatchKeyEvent(event))
                    if (vh != null) {
                        val spanCount = (binding.recyclerView.layoutManager as? GridLayoutManager)?.spanCount ?: 4
                        if (vh.bindingAdapterPosition in 0 until spanCount) {
                            binding.btnFilterAll.requestFocus()
                            return true
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showTvKeyboard() {
        val sb = StringBuilder(binding.etSearch.text?.toString() ?: "")
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT

        val tvDisplay = TextView(this).apply {
            text = sb.toString().ifEmpty { "TYPE SEARCH TERM…" }
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF060C1A.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTypeface(null, Typeface.BOLD)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF060C1A.toInt())
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        root.addView(tvDisplay, LinearLayout.LayoutParams(mp, wc).also { it.bottomMargin = dp(6) })

        fun updateDisplay() { tvDisplay.text = sb.toString().ifEmpty { "TYPE SEARCH TERM…" } }

        lateinit var dialog: AlertDialog

        fun makeKey(label: String, weight: Float = 1f, action: () -> Unit): TextView =
            TextView(this).apply {
                text = label
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF1A3060.toInt())
                isFocusable = true; isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, dp(40), weight).also {
                    it.setMargins(dp(2), dp(2), dp(2), dp(2))
                }
                setOnFocusChangeListener { _, h ->
                    setBackgroundColor(if (h) 0xFFE5A00D.toInt() else 0xFF1A3060.toInt())
                    setTextColor(if (h) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
                setOnClickListener { action() }
            }

        for (row in listOf("ABCDEFGHIJ", "KLMNOPQRST", "UVWXYZ0123")) {
            val rl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { ch -> rl.addView(makeKey(ch.toString()) { sb.append(ch); updateDisplay() }) }
            root.addView(rl, LinearLayout.LayoutParams(mp, wc))
        }
        val lastRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        "456789".forEach { ch -> lastRow.addView(makeKey(ch.toString()) { sb.append(ch); updateDisplay() }) }
        lastRow.addView(makeKey("SPC") { sb.append(' '); updateDisplay() })
        lastRow.addView(makeKey("DEL") { if (sb.isNotEmpty()) { sb.deleteCharAt(sb.lastIndex); updateDisplay() } })
        lastRow.addView(makeKey("SEARCH", 2f) {
            val q = sb.toString().trim()
            binding.etSearch.setText(q)
            dialog.dismiss()
            if (q.length >= 2) doSearch(q) else showHint()
        })
        root.addView(lastRow, LinearLayout.LayoutParams(mp, wc))

        dialog = AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
            .setView(root)
            .setNegativeButton("CANCEL", null)
            .create()
        dialog.show()
        root.post { (root.getChildAt(1) as? LinearLayout)?.getChildAt(0)?.requestFocus() }
    }

    private fun showHint() {
        fullResults     = emptyList()
        lastSearchQuery = ""
        binding.recyclerView.visibility = View.GONE
        binding.tvEmpty.visibility = View.VISIBLE
        binding.sidebarScroll.visibility = View.GONE
        binding.tvEmpty.text = "TYPE TO SEARCH TV, MOVIES & SERIES ACROSS ALL SOURCES"
        binding.tvResultCount.text = ""
        adapter.submitList(emptyList())
    }

    private fun doSearch(query: String) {
        // Guard against double-trigger: some IME implementations fire EditorActionListener
        // asynchronously after setText(), re-entering doSearch while results are already showing.
        val now = System.currentTimeMillis()
        if (query == lastSearchQuery && now - lastSearchMs < 1500L) return
        lastSearchQuery = query
        lastSearchMs    = now

        // Dismiss the soft keyboard so it can't re-fire the action while the search runs.
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)

        searchJob?.cancel()
        currentFilter = SearchFilter.ALL
        updateFilterButtons()
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE

        searchJob = lifecycleScope.launch {
            // Build missing caches before searching so we don't hang mid-search
            val profiles = PrefsManager.getProfiles(this@GlobalSearchActivity)
            val needsCache = profiles.any { p ->
                !ContentCache.isMoviesValid(this@GlobalSearchActivity, p.serverUrl) ||
                !ContentCache.isSeriesValid(this@GlobalSearchActivity, p.serverUrl)
            }
            if (needsCache) {
                binding.tvEmpty.text = "BUILDING SEARCH INDEX — PLEASE WAIT…"
                binding.tvEmpty.visibility = View.VISIBLE
                withContext(Dispatchers.IO) {
                    profiles.forEach { p ->
                        if (!ContentCache.isMoviesValid(this@GlobalSearchActivity, p.serverUrl)) {
                            ContentCache.downloadAndSaveMovies(this@GlobalSearchActivity, p.serverUrl, p.username, p.password)
                        }
                        if (!ContentCache.isSeriesValid(this@GlobalSearchActivity, p.serverUrl)) {
                            ContentCache.downloadAndSaveSeries(this@GlobalSearchActivity, p.serverUrl, p.username, p.password)
                        }
                    }
                }
                binding.tvEmpty.text = "INDEX READY — SEARCHING…"
            }

            // ── EPG index check ───────────────────────────────────────────────
            val cachedEpgCount = EpgCache.getCachedCount(this@GlobalSearchActivity)
            val totalLiveChannels = withContext(Dispatchers.IO) {
                profiles.sumOf { p ->
                    ContentCache.getLiveStreams(this@GlobalSearchActivity, p.serverUrl)?.size ?: 0
                }
            }
            if (cachedEpgCount < 5 && totalLiveChannels > 0) {
                val shouldBuild = suspendCancellableCoroutine { cont ->
                    AlertDialog.Builder(this@GlobalSearchActivity, com.orbital.iptv.utils.ThemeManager.dialogStyle())
                        .setTitle("NO EPG INDEX")
                        .setMessage(
                            "TV Guide data has not been indexed yet.\n\n" +
                            "Build the EPG index now to search programme titles across " +
                            "$totalLiveChannels channels?\n\n" +
                            "This downloads guide data for all channels and may take a few minutes."
                        )
                        .setPositiveButton("BUILD INDEX") { _, _ -> cont.resume(true) }
                        .setNegativeButton("SEARCH WITHOUT EPG") { _, _ -> cont.resume(false) }
                        .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                        .show()
                }
                if (shouldBuild) {
                    binding.tvEmpty.text = "BUILDING EPG INDEX…  0 / $totalLiveChannels"
                    binding.tvEmpty.visibility = View.VISIBLE
                    buildEpgIndex(totalLiveChannels)
                    binding.tvEmpty.text = "EPG INDEX READY — SEARCHING…"
                }
            }
            // ─────────────────────────────────────────────────────────────────

            val results = withContext(Dispatchers.IO) { searchAll(query) }
            fullResults = results
            binding.progressBar.visibility = View.GONE

            if (results.isEmpty()) {
                binding.tvEmpty.text = "NO RESULTS FOR \"${query.uppercase()}\""
                binding.tvEmpty.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
                binding.tvResultCount.text = ""
            } else {
                applyFilter(scrollToTop = true)
            }
        }
    }

    private fun applyFilter(scrollToTop: Boolean = false) {
        rebuildSidebar()
        val filtered = when (currentFilter) {
            SearchFilter.ALL    -> fullResults
            SearchFilter.TV     -> fullResults.filter { it.contentType == ContentType.TV }
            SearchFilter.MOVIES -> fullResults.filter { it.contentType == ContentType.MOVIE }
            SearchFilter.SERIES -> fullResults.filter { it.contentType == ContentType.SERIES }
        }
        if (filtered.isEmpty()) {
            val label = when (currentFilter) {
                SearchFilter.ALL    -> "NO RESULTS"
                SearchFilter.TV     -> "NO TV RESULTS"
                SearchFilter.MOVIES -> "NO MOVIE RESULTS"
                SearchFilter.SERIES -> "NO SERIES RESULTS"
            }
            binding.tvEmpty.text = label
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            binding.tvResultCount.text = ""
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            binding.tvResultCount.text = "${filtered.size} RESULTS"
            adapter.submitList(filtered)
            if (scrollToTop) binding.recyclerView.scrollToPosition(0)
        }
    }

    private fun rebuildSidebar() {
        if (fullResults.isEmpty()) { binding.sidebarScroll.visibility = View.GONE; return }
        binding.sidebarScroll.visibility = View.VISIBLE

        val container = binding.sidebarContainer
        container.removeAllViews()
        val p = ThemeManager.palette()
        val density = resources.displayMetrics.density
        val rowH = (46 * density).toInt()
        val pad  = (8 * density).toInt()
        val marginPx = (3 * density).toInt()

        data class SidebarEntry(val label: String, val count: Int, val filter: SearchFilter)
        val entries = listOf(
            SidebarEntry("ALL\n${fullResults.size}", fullResults.size, SearchFilter.ALL),
            SidebarEntry("📺 TV\n${fullResults.count { it.contentType == ContentType.TV }}", fullResults.count { it.contentType == ContentType.TV }, SearchFilter.TV),
            SidebarEntry("🎬 MOVIES\n${fullResults.count { it.contentType == ContentType.MOVIE }}", fullResults.count { it.contentType == ContentType.MOVIE }, SearchFilter.MOVIES),
            SidebarEntry("📺 SERIES\n${fullResults.count { it.contentType == ContentType.SERIES }}", fullResults.count { it.contentType == ContentType.SERIES }, SearchFilter.SERIES)
        )
        entries.forEach { entry ->
            val isSelected = entry.filter == currentFilter
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH)
            lp.setMargins(0, 0, 0, marginPx)
            val tv = TextView(this).apply {
                layoutParams = lp
                gravity = Gravity.CENTER
                setPadding(pad, 0, pad, 0)
                text = entry.label
                textSize = 10f
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                isClickable = true; isFocusable = true
                background = ThemeManager.roundedBg(if (isSelected) p.highlight else p.bgMid, density)
                setTextColor(if (isSelected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                setOnFocusChangeListener { _, hasFocus ->
                    if (!isSelected) background = ThemeManager.roundedBg(if (hasFocus) p.focus else p.bgMid, density)
                }
                setOnClickListener {
                    currentFilter = entry.filter
                    updateFilterButtons()
                    applyFilter()
                }
            }
            container.addView(tv)
        }
    }

    private suspend fun buildEpgIndex(totalChannels: Int) {
        val profiles = PrefsManager.getProfiles(this)
        val done     = AtomicInteger(0)
        val sem      = Semaphore(5)   // max 5 concurrent EPG fetches
        coroutineScope {
            for (profile in profiles) {
                val streams = ContentCache.getLiveStreams(this@GlobalSearchActivity, profile.serverUrl)
                    ?: continue
                for (stream in streams) {
                    if (EpgCache.isValid(this@GlobalSearchActivity, stream.streamId)) {
                        // Already cached — just count it
                        val n = done.incrementAndGet()
                        launch(Dispatchers.Main) {
                            binding.tvEmpty.text = "BUILDING EPG INDEX…  $n / $totalChannels"
                        }
                    } else {
                        launch(Dispatchers.IO) {
                            sem.withPermit {
                                xtream.getFullChannelEpg(
                                    profile.serverUrl, profile.username, profile.password, stream.streamId
                                ).onSuccess { epg ->
                                    val listings = epg.listings ?: emptyList()
                                    if (listings.isNotEmpty())
                                        EpgCache.put(this@GlobalSearchActivity, stream.streamId, listings)
                                }
                            }
                            val n = done.incrementAndGet()
                            withContext(Dispatchers.Main) {
                                binding.tvEmpty.text = "BUILDING EPG INDEX…  $n / $totalChannels"
                            }
                        }
                    }
                }
            }
        }
        EpgCache.markBatchRefreshed(this)
    }

    private suspend fun searchAll(query: String): List<SearchResultItem> = coroutineScope {
        val profiles = PrefsManager.getProfiles(this@GlobalSearchActivity)
        val embySession = EmbyPrefsManager.getSession(this@GlobalSearchActivity)
        val plexSession = PlexPrefsManager.getSession(this@GlobalSearchActivity)

        // Fire all searches concurrently
        val iptv = profiles.map { profile ->
            async {
                try {
                    ContentCache.searchMovies(this@GlobalSearchActivity, profile.serverUrl, query)
                        .map { stream ->
                            val url = xtream.buildVodUrl(
                                profile.serverUrl, profile.username, profile.password,
                                stream.streamId, stream.containerExtension ?: "mp4"
                            )
                            SearchSource.Iptv(
                                serverName = profile.name,
                                streamUrl  = url,
                                title      = stream.name,
                                thumbUrl   = stream.streamIcon?.takeIf { it.isNotBlank() }
                            )
                        }
                } catch (_: Throwable) { emptyList() }
            }
        }

        val emby = async {
            if (embySession == null) return@async emptyList()
            try {
                embyRepo.getItems(
                    embySession.serverUrl, embySession.userId, embySession.token,
                    types = "Movie", recursive = true, searchTerm = query, limit = 50
                ).getOrNull()?.map { item ->
                    val thumb = embyRepo.buildArtworkUrl(embySession.serverUrl, item.id, embySession.token, item.imageTags?.get("Primary"))
                    SearchSource.Emby(
                        itemId     = item.id,
                        title      = item.name,
                        resumeMs   = item.resumeMs(),
                        serverUrl  = embySession.serverUrl,
                        userId     = embySession.userId,
                        token      = embySession.token,
                        thumbUrl   = thumb?.takeIf { it.isNotBlank() },
                        resolution = item.videoResolution()
                    )
                } ?: emptyList()
            } catch (_: Throwable) { emptyList() }
        }

        val plex = async {
            if (plexSession == null) return@async emptyList()
            try {
                plexRepo.search(plexSession.serverUrl, plexSession.token, query)
                    .getOrNull()
                    ?.filter { it.type == "movie" }
                    ?.map { item ->
                        val thumb = plexRepo.buildThumbUrl(plexSession.serverUrl, item.thumb, plexSession.token)
                        SearchSource.Plex(
                            item       = item,
                            serverUrl  = plexSession.serverUrl,
                            token      = plexSession.token,
                            thumbUrl   = thumb,
                            resolution = item.videoResolution
                        )
                    } ?: emptyList()
            } catch (_: Throwable) { emptyList() }
        }

        // Series searches in parallel
        val iptvSeries = profiles.map { profile ->
            async {
                try {
                    ContentCache.searchSeries(this@GlobalSearchActivity, profile.serverUrl, query)
                        .map { show ->
                            SearchSource.IptvSeries(
                                show       = show,
                                serverUrl  = profile.serverUrl,
                                username   = profile.username,
                                password   = profile.password,
                                serverName = profile.name,
                                thumbUrl   = show.cover?.takeIf { it.isNotBlank() }
                            )
                        }
                } catch (_: Throwable) { emptyList() }
            }
        }

        val embySeries = async {
            if (embySession == null) return@async emptyList()
            try {
                embyRepo.getItems(
                    embySession.serverUrl, embySession.userId, embySession.token,
                    types = "Series", recursive = true, searchTerm = query, limit = 50
                ).getOrNull()?.map { item ->
                    val thumb = embyRepo.buildArtworkUrl(embySession.serverUrl, item.id, embySession.token, item.imageTags?.get("Primary"))
                    SearchSource.EmbyShow(
                        item      = item,
                        serverUrl = embySession.serverUrl,
                        userId    = embySession.userId,
                        token     = embySession.token,
                        thumbUrl  = thumb?.takeIf { it.isNotBlank() }
                    )
                } ?: emptyList()
            } catch (_: Throwable) { emptyList() }
        }

        val plexSeries = async {
            if (plexSession == null) return@async emptyList()
            try {
                plexRepo.search(plexSession.serverUrl, plexSession.token, query)
                    .getOrNull()
                    ?.filter { it.type == "show" }
                    ?.map { item ->
                        val thumb = plexRepo.buildThumbUrl(plexSession.serverUrl, item.thumb, plexSession.token)
                        SearchSource.PlexShow(
                            item      = item,
                            serverUrl = plexSession.serverUrl,
                            token     = plexSession.token,
                            thumbUrl  = thumb
                        )
                    } ?: emptyList()
            } catch (_: Throwable) { emptyList() }
        }

        // Live channel search — EPG cache only, no fresh network fetch
        val liveChannels = profiles.map { profile ->
            async {
                try {
                    val streams = ContentCache.getLiveStreams(this@GlobalSearchActivity, profile.serverUrl)
                        ?: return@async emptyList()
                    val matchedIds = mutableSetOf<Int>()

                    val byName = streams.filter { it.name.contains(query, ignoreCase = true) }
                        .take(50)
                        .map { s ->
                            matchedIds.add(s.streamId)
                            s.toLiveResult(profile.name, profile.serverUrl, profile.username, profile.password, null)
                        }

                    val byEpg = streams
                        .filter { it.streamId !in matchedIds && EpgCache.isValid(this@GlobalSearchActivity, it.streamId) }
                        .mapNotNull { s ->
                            val listings = EpgCache.get(this@GlobalSearchActivity, s.streamId)
                            val hit = listings?.firstOrNull { l ->
                                android.util.Base64.decode(l.title ?: "", android.util.Base64.DEFAULT)
                                    .decodeToString().contains(query, ignoreCase = true)
                            }
                            if (hit != null) {
                                val epgTitle = android.util.Base64.decode(hit.title ?: "", android.util.Base64.DEFAULT).decodeToString()
                                s.toLiveResult(profile.name, profile.serverUrl, profile.username, profile.password, epgTitle)
                            } else null
                        }

                    byName + byEpg
                } catch (_: Throwable) { emptyList() }
            }
        }

        val allIptv = iptv.flatMap { it.await() }
        val allEmby = emby.await()
        val allPlex = plex.await()
        val allIptvSeries = iptvSeries.flatMap { it.await() }
        val allEmbySeries = embySeries.await()
        val allPlexSeries = plexSeries.await()
        val allLive = liveChannels.flatMap { it.await() }.distinctBy { it.title }

        groupResults(allIptv, allEmby, allPlex) +
            groupSeriesResults(allIptvSeries, allEmbySeries, allPlexSeries) +
            allLive
    }

    private fun LiveStream.toLiveResult(
        serverName: String, serverUrl: String, username: String, password: String, epgTitle: String?
    ) = SearchResultItem(
        title = name,
        year = null,
        thumbUrl = streamIcon?.takeIf { it.isNotBlank() },
        sources = listOf(SearchSource.LiveChannel(
            streamId = streamId,
            streamUrl = xtream.buildStreamUrl(serverUrl, username, password, streamId),
            channelName = name,
            serverName = serverName,
            epgTitle = epgTitle,
            thumbUrl = streamIcon?.takeIf { it.isNotBlank() }
        )),
        contentType = ContentType.TV
    )

    private fun groupResults(
        iptv: List<SearchSource.Iptv>,
        emby: List<SearchSource.Emby>,
        plex: List<SearchSource.Plex>
    ): List<SearchResultItem> {
        // Bucket by normalised title
        data class Bucket(
            val canonical: String,
            val year: String?,
            val sources: MutableList<SearchSource> = mutableListOf()
        )

        val buckets = linkedMapOf<String, Bucket>()

        fun normalise(title: String) = title.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex(" +"), " ")
            .trim()

        fun getOrCreate(title: String, year: String?): Bucket {
            val key = normalise(title)
            return buckets.getOrPut(key) { Bucket(title, year) }
        }

        emby.forEach { src -> getOrCreate(src.title, null).sources.add(src) }
        plex.forEach { src ->
            val year = src.item.year?.toString()
            getOrCreate(src.item.title, year).sources.add(src)
        }
        iptv.forEach { src -> getOrCreate(src.title, null).sources.add(src) }

        return buckets.values.map { bucket ->
            // Best thumb: Emby > Plex > IPTV
            val thumb = bucket.sources
                .firstOrNull { it is SearchSource.Emby }?.thumbUrl
                ?: bucket.sources.firstOrNull { it is SearchSource.Plex }?.thumbUrl
                ?: bucket.sources.firstOrNull { it is SearchSource.Iptv }?.thumbUrl
            SearchResultItem(bucket.canonical, bucket.year, thumb, bucket.sources)
        }
    }

    private fun groupSeriesResults(
        iptv: List<SearchSource.IptvSeries>,
        emby: List<SearchSource.EmbyShow>,
        plex: List<SearchSource.PlexShow>
    ): List<SearchResultItem> {
        data class Bucket(val canonical: String, val year: String?, val sources: MutableList<SearchSource> = mutableListOf())
        val buckets = linkedMapOf<String, Bucket>()
        fun normalise(title: String) = title.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()
        fun getOrCreate(title: String, year: String?) = buckets.getOrPut(normalise(title)) { Bucket(title, year) }
        emby.forEach { src -> getOrCreate(src.item.name, src.item.productionYear?.toString()).sources.add(src) }
        plex.forEach { src -> getOrCreate(src.item.title, src.item.year?.toString()).sources.add(src) }
        iptv.forEach  { src -> getOrCreate(src.show.name, null).sources.add(src) }
        return buckets.values.map { bucket ->
            val thumb = bucket.sources.firstOrNull { it is SearchSource.EmbyShow }?.thumbUrl
                ?: bucket.sources.firstOrNull { it is SearchSource.PlexShow }?.thumbUrl
                ?: bucket.sources.firstOrNull { it is SearchSource.IptvSeries }?.thumbUrl
            SearchResultItem(bucket.canonical, bucket.year, thumb, bucket.sources, ContentType.SERIES)
        }
    }

    private fun onResultClicked(item: SearchResultItem) {
        if (item.sources.size == 1) {
            playSource(item.sources[0], item.title)
        } else {
            val labels = item.sources.map { it.label }.toTypedArray()
            AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
                .setTitle("PLAY \"${item.title.uppercase()}\" FROM:")
                .setItems(labels) { _, i -> playSource(item.sources[i], item.title) }
                .show()
        }
    }

    private fun playSource(source: SearchSource, @Suppress("UNUSED_PARAMETER") fallbackTitle: String) {
        when (source) {
            is SearchSource.Iptv -> {
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_STREAM_URL,   source.streamUrl)
                    putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, source.title)
                    putExtra(PlayerActivity.EXTRA_STREAM_ID,    -1)
                    putExtra(PlayerActivity.EXTRA_IS_LIVE,      false)
                })
            }
            is SearchSource.Emby -> {
                lifecycleScope.launch {
                    val streamUrl = withContext(Dispatchers.IO) {
                        val mediaSource = embyRepo.getPlaybackInfo(
                            source.serverUrl, source.userId, source.token, source.itemId
                        ).getOrNull()?.mediaSources?.firstOrNull()
                        embyRepo.buildStreamUrl(source.serverUrl, source.itemId, source.token, mediaSource)
                    }
                    startActivity(Intent(this@GlobalSearchActivity, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_STREAM_URL,   streamUrl)
                        putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, source.title)
                        putExtra(PlayerActivity.EXTRA_STREAM_ID,    -1)
                        putExtra(PlayerActivity.EXTRA_IS_LIVE,      false)
                        putExtra(PlayerActivity.EXTRA_RESUME_MS,    source.resumeMs)
                        putExtra(PlayerActivity.EXTRA_EMBY_ITEM_ID, source.itemId)
                    })
                }
            }
            is SearchSource.Plex -> {
                val partKey = source.item.partKey ?: return
                val streamUrl = plexRepo.buildStreamUrl(source.serverUrl, partKey, source.token)
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_STREAM_URL,        streamUrl)
                    putExtra(PlayerActivity.EXTRA_CHANNEL_NAME,      source.item.title)
                    putExtra(PlayerActivity.EXTRA_STREAM_ID,         -1)
                    putExtra(PlayerActivity.EXTRA_IS_LIVE,           false)
                    putExtra(PlayerActivity.EXTRA_RESUME_MS,         source.item.resumeMs())
                    putExtra(PlayerActivity.EXTRA_PLEX_RATING_KEY,   source.item.ratingKey)
                    putExtra(PlayerActivity.EXTRA_PLEX_DURATION_MS,  source.item.duration ?: 0L)
                })
            }
            is SearchSource.IptvSeries -> {
                startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_ID,    source.show.seriesId)
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME,  source.show.name)
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, source.show.cover ?: "")
                    putExtra(SeriesDetailActivity.EXTRA_RATING,       source.show.rating ?: "")
                    putExtra(SeriesDetailActivity.EXTRA_SERVER_URL,   source.serverUrl)
                    putExtra(SeriesDetailActivity.EXTRA_USERNAME,     source.username)
                    putExtra(SeriesDetailActivity.EXTRA_PASSWORD,     source.password)
                })
            }
            is SearchSource.EmbyShow -> {
                startActivity(Intent(this, EmbyBrowserActivity::class.java).apply {
                    putExtra(EmbyBrowserActivity.EXTRA_SERIES_ID,        source.item.id)
                    putExtra(EmbyBrowserActivity.EXTRA_SERIES_NAME,      source.item.name)
                    putExtra(EmbyBrowserActivity.EXTRA_SERIES_IMAGE_TAG, source.item.imageTags?.get("Primary"))
                })
            }
            is SearchSource.PlexShow -> {
                startActivity(Intent(this, PlexBrowserActivity::class.java).apply {
                    putExtra(PlexBrowserActivity.EXTRA_SHOW_KEY,   source.item.ratingKey)
                    putExtra(PlexBrowserActivity.EXTRA_SHOW_TITLE, source.item.title)
                    putExtra(PlexBrowserActivity.EXTRA_SHOW_THUMB, source.item.thumb)
                })
            }
            is SearchSource.LiveChannel -> {
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_STREAM_URL,   source.streamUrl)
                    putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, source.channelName)
                    putExtra(PlayerActivity.EXTRA_STREAM_ID,    source.streamId)
                    putExtra(PlayerActivity.EXTRA_IS_LIVE,      true)
                })
            }
        }
    }
}
