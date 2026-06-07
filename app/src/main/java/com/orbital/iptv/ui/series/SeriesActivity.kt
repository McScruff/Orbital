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
import com.orbital.iptv.data.model.FavType
import com.orbital.iptv.utils.ContentCache
import com.orbital.iptv.utils.FavouritesManager
import com.orbital.iptv.utils.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.orbital.iptv.utils.ThemeManager

class SeriesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySeriesBinding
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
        binding = ActivitySeriesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.load(this)
        val p = ThemeManager.palette()
        binding.root.setBackgroundColor(p.bgPrimary)
        binding.layoutHeader?.setBackgroundColor(p.bgHeader)
        binding.viewAccent?.setBackgroundColor(p.accent)

        viewModel = ViewModelProvider(this)[SeriesViewModel::class.java]
        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { _, hasFocus ->
            binding.btnBack.setBackgroundColor(if (hasFocus) 0xFF2D6090.toInt() else 0xFF1E3D72.toInt())
        }

        adapter = SeriesAdapter(this) { show -> onShowSelected(show) }
        binding.rvShows.apply {
            this.adapter = this@SeriesActivity.adapter
            layoutManager = GridLayoutManager(this@SeriesActivity, 3)
        }

        setupSearch()

        viewModel.uiState.observe(this) { state ->
            binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
            state.error?.let {
                binding.tvError.text = it.uppercase(); binding.tvError.visibility = View.VISIBLE
            } ?: run { binding.tvError.visibility = View.GONE }

            if (!isSearchActive && !showingContinue && !showingFavourites) {
                adapter.submitList(state.shows)
                binding.tvShowCount.text = "${state.shows.size} SHOWS"
            }
            if (state.categories.isNotEmpty()) buildCategoryMenu(state.categories, state.selectedCategory)
            if (!showingContinue && !showingFavourites) state.selectedCategory?.let { binding.tvCurrentCategory.text = it.categoryName.uppercase() }
        }

        val creds = PrefsManager.getCredentials(this) ?: run { finish(); return }
        viewModel.loadCategories(creds.serverUrl, creds.username, creds.password)

        scope.launch {
            val cached = ContentCache.getSeries(this@SeriesActivity, creds.serverUrl)
            if (cached != null) {
                allShows = cached
            } else {
                val result = viewModel.getAllSeriesForCache(creds.serverUrl, creds.username, creds.password)
                result?.let {
                    allShows = it
                    ContentCache.saveSeries(this@SeriesActivity, creds.serverUrl, it)
                }
            }
        }
    }

    private fun setupSearch() {
        binding.etSearch.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN &&
                event.action == android.view.KeyEvent.ACTION_DOWN) {
                for (i in 0 until binding.catContainer.childCount) {
                    val child = binding.catContainer.getChildAt(i)
                    if (child.isFocusable) { child.requestFocus(); break }
                }
                true
            } else false
        }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
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
                            binding.tvShowCount.text = "${current.size} SHOWS"
                            binding.tvCurrentCategory.text = viewModel.uiState.value?.selectedCategory?.categoryName?.uppercase() ?: "SERIES"
                        }
                    }
                } else {
                    isSearchActive = true
                    val source = allShows.ifEmpty { viewModel.uiState.value?.shows ?: emptyList() }
                    val filtered = source.filter { it.name.contains(query, ignoreCase = true) }
                    adapter.submitList(filtered)
                    binding.tvShowCount.text = "${filtered.size} RESULTS"
                    binding.tvCurrentCategory.text = "SEARCH: $query"
                }
            }
        })
    }

    private fun buildCategoryMenu(categories: List<SeriesCategory>, selected: SeriesCategory?) {
        val container = binding.catContainer
        while (container.childCount > 1) container.removeViewAt(1)

        val p = ThemeManager.palette()
        val density = resources.displayMetrics.density
        val rowH = (38 * density).toInt()
        val pad  = (12 * density).toInt()
        val marginPx = (p.itemMarginDp * density).toInt()

        val allFavs = FavouritesManager.getAll(this)
        val favItems      = allFavs.filter { it.type == FavType.EPISODE && !it.hasResume && it.seriesId >= 0 }
        val continueItems = allFavs.filter { it.hasResume && it.type == FavType.EPISODE }

        if (favItems.isNotEmpty()) {
            val isSel = showingFavourites
            container.addView(TextView(this).apply {
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH)
                if (marginPx > 0) lp.setMargins(marginPx, marginPx / 2, marginPx, marginPx / 2)
                layoutParams = lp
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, 0, 0, 0)
                text = "★ FAVOURITES"
                textSize = 11f
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                isClickable = true; isFocusable = true
                background = ThemeManager.roundedBg(if (isSel) p.highlight else p.bgMid, density)
                setTextColor(if (isSel) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                setOnFocusChangeListener { _, hasFocus ->
                    if (!isSel) background = ThemeManager.roundedBg(if (hasFocus) p.focus else p.bgMid, density)
                }
                setOnClickListener {
                    showingFavourites = true; showingContinue = false
                    binding.etSearch.text?.clear()
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
                textSize = 11f
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                isClickable = true; isFocusable = true
                background = ThemeManager.roundedBg(if (isSel) p.highlight else p.bgMid, density)
                setTextColor(if (isSel) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                setOnFocusChangeListener { _, hasFocus ->
                    if (!isSel) background = ThemeManager.roundedBg(if (hasFocus) p.focus else p.bgMid, density)
                }
                setOnClickListener {
                    showingContinue = true; showingFavourites = false
                    binding.etSearch.text?.clear()
                    showContinueWatching()
                    buildCategoryMenu(categories, selected)
                }
            })
        }

        val offset = (if (favItems.isNotEmpty()) 1 else 0) + (if (continueItems.isNotEmpty()) 1 else 0)
        categories.take(60).forEachIndexed { i, cat ->
            val isSelected = !showingContinue && !showingFavourites && cat.categoryId == selected?.categoryId
            val idx = i + offset
            val normalBg = if (idx % 2 == 0) p.bgMid else p.bgPrimary
            container.addView(TextView(this).apply {
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH)
                if (marginPx > 0) lp.setMargins(marginPx, marginPx / 2, marginPx, marginPx / 2)
                layoutParams = lp
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, 0, 0, 0)
                text = cat.categoryName.uppercase()
                textSize = 11f
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                isClickable = true; isFocusable = true
                background = if (isSelected) ThemeManager.roundedBg(p.highlight, density) else ThemeManager.roundedBg(normalBg, density)
                setTextColor(if (isSelected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                setOnFocusChangeListener { _, hasFocus ->
                    if (!isSelected) background = ThemeManager.roundedBg(if (hasFocus) p.focus else normalBg, density)
                }
                setOnClickListener {
                    showingContinue = false; showingFavourites = false
                    binding.etSearch.text?.clear()
                    viewModel.selectCategory(cat)
                }
            })
        }
    }

    private fun showFavouritesSeries() {
        val favSeriesIds = FavouritesManager.getAll(this)
            .filter { it.type == FavType.EPISODE && !it.hasResume && it.seriesId >= 0 }
            .map { it.seriesId }.toSet()
        val favShows = allShows.filter { it.seriesId in favSeriesIds }
        adapter.submitList(favShows)
        binding.tvShowCount.text = "${favShows.size} SHOWS"
        binding.tvCurrentCategory.text = "FAVOURITES"
    }

    private fun showContinueWatching() {
        val continueItems = FavouritesManager.getAll(this).filter { it.hasResume && it.type == FavType.EPISODE }
        // Per series, take the most recently touched episode
        val latestBySeriesId = continueItems
            .groupBy { it.seriesId }
            .mapValues { (_, eps) -> eps.maxByOrNull { it.addedAt }!! }
        val resumeLabels = latestBySeriesId.mapValues { (_, fav) ->
            val ep = if (fav.season.isNotBlank() && fav.episodeNum > 0)
                "S${fav.season}E${"%-2d".format(fav.episodeNum).trim()}  " else ""
            "▶ ${ep}FROM ${FavouritesManager.formatDuration(fav.resumePositionMs)} / ${FavouritesManager.formatDuration(fav.durationMs)}"
        }
        val resumeShows = allShows.filter { it.seriesId in resumeLabels }
        adapter.submitList(resumeShows, resumeLabels)
        binding.tvShowCount.text = "${resumeShows.size} SHOWS"
        binding.tvCurrentCategory.text = "CONTINUE WATCHING"
    }

    private fun onShowSelected(show: SeriesStream) {
        val creds = viewModel.getCredentials() ?: return
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
