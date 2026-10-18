package com.orbital.iptv.ui.series

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import com.orbital.iptv.data.model.SeriesCategory
import com.orbital.iptv.data.model.SeriesStream
import com.orbital.iptv.databinding.ActivitySeriesBinding
import com.orbital.iptv.databinding.ActivityNebulaBrowseBinding
import com.orbital.iptv.ui.nebula.BrowseViews
import com.orbital.iptv.ui.nebula.NebulaLiveActivity
import com.orbital.iptv.ui.nebula.NebulaRail
import com.orbital.iptv.data.model.FavType
import com.orbital.iptv.utils.ContentCache
import com.orbital.iptv.utils.FavouritesManager
import com.orbital.iptv.utils.PlayerLauncher
import com.orbital.iptv.utils.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.orbital.iptv.utils.ThemeManager

class SeriesActivity : AppCompatActivity() {

    private lateinit var v: BrowseViews
    private lateinit var viewModel: SeriesViewModel
    private lateinit var adapter: SeriesAdapter
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var allShows: List<SeriesStream> = emptyList()
    private var isSearchActive = false
    private var showingContinue = false
    private var showingFavourites = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        ThemeManager.load(this)
        val nebula = ThemeManager.current == ThemeManager.AppTheme.NEBULA
        if (nebula) {
            val b = ActivityNebulaBrowseBinding.inflate(layoutInflater)
            setContentView(b.root)
            b.nbBrowseTitle.text = "Series"
            b.etSearch.hint = "Search series"
            v = BrowseViews.from(b)
            NebulaRail.bind(
                this, b.rail, NebulaRail.Item.SERIES,
                onHome = { finish() },
                onLive = { startActivity(Intent(this, NebulaLiveActivity::class.java)) }
            )
        } else {
            val b = ActivitySeriesBinding.inflate(layoutInflater)
            setContentView(b.root)
            v = BrowseViews(
                b.root, b.btnBack, b.etSearch, b.catContainer, b.tvCurrentCategory, b.progressBar,
                b.tvError, b.rvShows, b.tvShowCount, b.layoutHeader, b.viewAccent
            )
        }
        val p = ThemeManager.palette()
        v.root.setBackgroundColor(p.bgPrimary)
        v.layoutHeader?.setBackgroundColor(p.bgHeader)
        v.viewAccent?.setBackgroundColor(p.accent)

        viewModel = ViewModelProvider(this)[SeriesViewModel::class.java]
        v.btnBack.setOnClickListener { finish() }
        if (!nebula) v.btnBack.setOnFocusChangeListener { _, hasFocus ->
            val d = resources.displayMetrics.density
            v.btnBack.background = ThemeManager.focusRowDrawable(d, 0xFF1E3D72.toInt(), hasFocus)
        }

        adapter = SeriesAdapter(this) { show -> onShowSelected(show) }
        v.rv.apply {
            this.adapter = this@SeriesActivity.adapter
            layoutManager = GridLayoutManager(this@SeriesActivity, 5)
        }

        setupSearch()

        viewModel.uiState.observe(this) { state ->
            v.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
            state.error?.let {
                v.tvError.text = it.uppercase(); v.tvError.visibility = View.VISIBLE
            } ?: run { v.tvError.visibility = View.GONE }

            if (!isSearchActive && !showingContinue && !showingFavourites) {
                adapter.submitList(state.shows)
                v.tvCount.text = "${state.shows.size} SHOWS"
            }
            if (state.categories.isNotEmpty()) buildCategoryMenu(state.categories, state.selectedCategory)
            if (!showingContinue && !showingFavourites) state.selectedCategory?.let { v.tvCurrentCategory.text = it.categoryName.uppercase() }
        }

        val creds = PrefsManager.getCredentials(this) ?: run { finish(); return }
        viewModel.loadCategories(creds.serverUrl, creds.username, creds.password)

        scope.launch {
            val cached = ContentCache.getSeries(this@SeriesActivity, creds.serverUrl)
            if (cached != null) {
                allShows = cached
            } else {
                // Stream straight to disk (not through Retrofit/Gson) to avoid OOM on large
                // series catalogs, then read back via streaming JsonReader.
                ContentCache.downloadAndSaveSeries(this@SeriesActivity, creds.serverUrl, creds.username, creds.password)
                ContentCache.getSeries(this@SeriesActivity, creds.serverUrl)?.let { allShows = it }
            }
        }
    }

    private fun setupSearch() {
        v.etSearch.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN &&
                event.action == android.view.KeyEvent.ACTION_DOWN) {
                for (i in 0 until v.catContainer.childCount) {
                    val child = v.catContainer.getChildAt(i)
                    if (child.isFocusable) { child.requestFocus(); break }
                }
                true
            } else false
        }
        v.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                if (query.length < 2) {
                    isSearchActive = false
                    when {
                        showingFavourites -> showFavouritesSeries()
                        showingContinue   -> showContinueWatching()
                        else -> {
                            val current = viewModel.uiState.value?.shows ?: emptyList()
                            adapter.submitList(current)
                            v.tvCount.text = "${current.size} SHOWS"
                            v.tvCurrentCategory.text = viewModel.uiState.value?.selectedCategory?.categoryName?.uppercase() ?: "SERIES"
                        }
                    }
                } else {
                    isSearchActive = true
                    val source = allShows.ifEmpty { viewModel.uiState.value?.shows ?: emptyList() }
                    val filtered = source.filter { it.name.contains(query, ignoreCase = true) }
                    adapter.submitList(filtered)
                    v.tvCount.text = "${filtered.size} RESULTS"
                    v.tvCurrentCategory.text = "SEARCH: $query"
                }
            }
        })
    }

    private fun buildCategoryMenu(categories: List<SeriesCategory>, selected: SeriesCategory?) {
        val container = v.catContainer
        while (container.childCount > 1) container.removeViewAt(1)

        val p = ThemeManager.palette()
        val nebula = ThemeManager.current == ThemeManager.AppTheme.NEBULA
        val selBg = if (nebula) 0xFF221B4F.toInt() else p.highlight
        val selFg = if (nebula) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val idleFg = if (nebula) 0xFFD8D6F0.toInt() else 0xFFFFFFFF.toInt()
        val idleBg = if (nebula) 0x00000000 else p.bgMid
        val density = resources.displayMetrics.density
        val rowH = (38 * density).toInt()
        val pad  = (12 * density).toInt()
        val marginPx = (p.itemMarginDp * density).toInt()

        val allFavs = FavouritesManager.getAll(this)
        val favItems      = allFavs.filter { it.type == FavType.EPISODE && !it.hasResume && !it.isUpNext && it.seriesId >= 0 }
        val continueItems = allFavs.filter { it.type == FavType.EPISODE && (it.hasResume || it.isUpNext) }

        if (favItems.isNotEmpty()) {
            val isSel = showingFavourites
            container.addView(TextView(this).apply {
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH)
                if (marginPx > 0) lp.setMargins(marginPx, marginPx / 2, marginPx, marginPx / 2)
                layoutParams = lp
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, 0, 0, 0)
                text = "★ FAVOURITES"
                textSize = if (nebula) 13f else 11f
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                isClickable = true; isFocusable = true
                background = ThemeManager.roundedBg(if (isSel) selBg else idleBg, density)
                setTextColor(if (isSel) selFg else idleFg)
                setOnFocusChangeListener { _, hasFocus ->
                    if (!isSel) background = ThemeManager.focusRowDrawable(density, idleBg, hasFocus)
                }
                setOnClickListener {
                    showingFavourites = true; showingContinue = false
                    v.etSearch.text?.clear()
                    showFavouritesSeries()
                    buildCategoryMenu(categories, selected)
                }
            })
        }

        if (continueItems.isNotEmpty()) {
            val isSel = showingContinue
            container.addView(TextView(this).apply {
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH)
                if (marginPx > 0) lp.setMargins(marginPx, marginPx / 2, marginPx, marginPx / 2)
                layoutParams = lp
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, 0, 0, 0)
                text = "▶ CONTINUE WATCHING"
                textSize = if (nebula) 13f else 11f
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                isClickable = true; isFocusable = true
                background = ThemeManager.roundedBg(if (isSel) selBg else idleBg, density)
                setTextColor(if (isSel) selFg else idleFg)
                setOnFocusChangeListener { _, hasFocus ->
                    if (!isSel) background = ThemeManager.focusRowDrawable(density, idleBg, hasFocus)
                }
                setOnClickListener {
                    showingContinue = true; showingFavourites = false
                    v.etSearch.text?.clear()
                    showContinueWatching()
                    buildCategoryMenu(categories, selected)
                }
            })
        }

        val offset = (if (favItems.isNotEmpty()) 1 else 0) + (if (continueItems.isNotEmpty()) 1 else 0)
        categories.take(60).forEachIndexed { i, cat ->
            val isSelected = !showingContinue && !showingFavourites && cat.categoryId == selected?.categoryId
            val idx = i + offset
            val normalBg = if (nebula) 0x00000000 else if (idx % 2 == 0) p.bgMid else p.bgPrimary
            container.addView(TextView(this).apply {
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH)
                if (marginPx > 0) lp.setMargins(marginPx, marginPx / 2, marginPx, marginPx / 2)
                layoutParams = lp
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, 0, 0, 0)
                text = cat.categoryName.uppercase()
                textSize = if (nebula) 13f else 11f
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                isClickable = true; isFocusable = true
                background = if (isSelected) ThemeManager.roundedBg(selBg, density) else ThemeManager.roundedBg(normalBg, density)
                setTextColor(if (isSelected) selFg else idleFg)
                setOnFocusChangeListener { _, hasFocus ->
                    if (!isSelected) background = ThemeManager.focusRowDrawable(density, normalBg, hasFocus)
                }
                setOnClickListener {
                    showingContinue = false; showingFavourites = false
                    v.etSearch.text?.clear()
                    viewModel.selectCategory(cat)
                }
            })
        }
    }

    private fun showFavouritesSeries() {
        val favSeriesIds = FavouritesManager.getAll(this)
            .filter { it.type == FavType.EPISODE && !it.hasResume && !it.isUpNext && it.seriesId >= 0 }
            .map { it.seriesId }.toSet()
        val favShows = allShows.filter { it.seriesId in favSeriesIds }
        adapter.submitList(favShows)
        v.tvCount.text = "${favShows.size} SHOWS"
        v.tvCurrentCategory.text = "FAVOURITES"
    }

    private fun showContinueWatching() {
        val continueItems = FavouritesManager.getAll(this)
            .filter { it.type == FavType.EPISODE && (it.hasResume || it.isUpNext) }
        // Per series, take the most recently touched episode
        val latestBySeriesId = continueItems
            .groupBy { it.seriesId }
            .mapValues { (_, eps) -> eps.maxByOrNull { it.addedAt }!! }
        val resumeLabels = latestBySeriesId.mapValues { (_, fav) ->
            val ep = if (fav.season.isNotBlank() && fav.episodeNum > 0)
                "S${fav.season}E${"%-2d".format(fav.episodeNum).trim()}  " else ""
            if (fav.isUpNext) "▶ ${ep}UP NEXT"
            else "▶ ${ep}FROM ${FavouritesManager.formatDuration(fav.resumePositionMs)} / ${FavouritesManager.formatDuration(fav.durationMs)}"
        }
        val resumeShows = allShows.filter { it.seriesId in resumeLabels }
        adapter.submitList(resumeShows, resumeLabels)
        v.tvCount.text = "${resumeShows.size} SHOWS"
        v.tvCurrentCategory.text = "CONTINUE WATCHING"
    }

    private fun onShowSelected(show: SeriesStream) {
        val creds = viewModel.getCredentials() ?: return
        if (showingContinue) {
            val fav = FavouritesManager.getAll(this)
                .filter { it.type == FavType.EPISODE && it.seriesId == show.seriesId && (it.hasResume || it.isUpNext) }
                .maxByOrNull { it.addedAt }
            if (fav != null) {
                PlayerLauncher.launch(
                    activity     = this,
                    streamUrl    = fav.streamUrl,
                    title        = fav.title,
                    streamId     = fav.streamId,
                    isLive       = false,
                    favId        = fav.id,
                    artUrl       = fav.artUrl,
                    resumeMs     = fav.resumePositionMs,
                    seriesId     = fav.seriesId,
                    season       = fav.season,
                    episodeNum   = fav.episodeNum,
                    episodeId    = fav.episodeId,
                    nextEpUrl    = fav.nextEpisodeUrl,
                    nextEpTitle  = fav.nextEpisodeTitle,
                    nextEpNum    = fav.nextEpisodeNum,
                    nextEpSeason = fav.nextEpisodeSeason,
                    nextEpId     = fav.nextEpisodeId
                )
                return
            }
        }
        startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
            putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, show.seriesId)
            putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, show.name)
            putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, show.cover ?: "")
            putExtra(SeriesDetailActivity.EXTRA_RATING, show.rating ?: "")
            putExtra(SeriesDetailActivity.EXTRA_SERVER_URL, creds.first)
            putExtra(SeriesDetailActivity.EXTRA_USERNAME, creds.second)
            putExtra(SeriesDetailActivity.EXTRA_PASSWORD, creds.third)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
