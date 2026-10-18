package com.orbital.iptv.ui.vod

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
import com.orbital.iptv.data.model.VodCategory
import com.orbital.iptv.data.model.VodStream
import com.orbital.iptv.databinding.ActivityVodBinding
import com.orbital.iptv.databinding.ActivityNebulaBrowseBinding
import com.orbital.iptv.ui.nebula.BrowseViews
import com.orbital.iptv.ui.nebula.NebulaLiveActivity
import com.orbital.iptv.ui.nebula.NebulaRail
import com.orbital.iptv.data.model.FavType
import com.orbital.iptv.data.repository.XtreamRepository
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

class VodActivity : AppCompatActivity() {

    private lateinit var v: BrowseViews
    private lateinit var viewModel: VodViewModel
    private lateinit var adapter: VodAdapter
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var allMovies: List<VodStream> = emptyList()
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
            b.nbBrowseTitle.text = "Movies"
            b.etSearch.hint = "Search movies"
            v = BrowseViews.from(b)
            NebulaRail.bind(
                this, b.rail, NebulaRail.Item.MOVIES,
                onHome = { finish() },
                onLive = { startActivity(Intent(this, NebulaLiveActivity::class.java)) }
            )
        } else {
            val b = ActivityVodBinding.inflate(layoutInflater)
            setContentView(b.root)
            v = BrowseViews(
                b.root, b.btnBack, b.etSearch, b.catContainer, b.tvCurrentCategory, b.progressBar,
                b.tvError, b.rvMovies, b.tvMovieCount, b.layoutHeader, b.viewAccent
            )
        }
        val p = ThemeManager.palette()
        v.root.setBackgroundColor(p.bgPrimary)
        v.layoutHeader?.setBackgroundColor(p.bgHeader)
        v.viewAccent?.setBackgroundColor(p.accent)

        viewModel = ViewModelProvider(this)[VodViewModel::class.java]
        v.btnBack.setOnClickListener { finish() }
        if (!nebula) v.btnBack.setOnFocusChangeListener { _, hasFocus ->
            val d = resources.displayMetrics.density
            v.btnBack.background = ThemeManager.focusRowDrawable(d, 0xFF1E3D72.toInt(), hasFocus)
        }

        adapter = VodAdapter(this) { movie -> onMovieSelected(movie) }
        v.rv.apply {
            this.adapter = this@VodActivity.adapter
            layoutManager = GridLayoutManager(this@VodActivity, 5)
        }

        setupSearch()

        viewModel.uiState.observe(this) { state ->
            v.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
            state.error?.let {
                v.tvError.text = it.uppercase(); v.tvError.visibility = View.VISIBLE
            } ?: run { v.tvError.visibility = View.GONE }

            if (!isSearchActive && !showingContinue && !showingFavourites) {
                adapter.submitList(state.movies)
                v.tvCount.text = "${state.movies.size} TITLES"
            }
            if (state.categories.isNotEmpty()) buildCategoryMenu(state.categories, state.selectedCategory)
            if (!showingContinue && !showingFavourites) state.selectedCategory?.let { v.tvCurrentCategory.text = it.categoryName.uppercase() }
        }

        val creds = PrefsManager.getCredentials(this) ?: run { finish(); return }
        viewModel.loadCategories(creds.serverUrl, creds.username, creds.password)

        scope.launch {
            val cached = ContentCache.getMovies(this@VodActivity, creds.serverUrl)
            if (cached != null) {
                allMovies = cached
            } else {
                // Stream straight to disk (not through Retrofit/Gson) to avoid OOM on large
                // VOD catalogs, then read back via streaming JsonReader.
                ContentCache.downloadAndSaveMovies(this@VodActivity, creds.serverUrl, creds.username, creds.password)
                ContentCache.getMovies(this@VodActivity, creds.serverUrl)?.let { allMovies = it }
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
                        showingFavourites -> showFavouritesMovies()
                        showingContinue   -> showContinueWatching()
                        else -> {
                            val current = viewModel.uiState.value?.movies ?: emptyList()
                            adapter.submitList(current)
                            v.tvCount.text = "${current.size} TITLES"
                            v.tvCurrentCategory.text = viewModel.uiState.value?.selectedCategory?.categoryName?.uppercase() ?: "MOVIES"
                        }
                    }
                } else {
                    isSearchActive = true
                    val source = allMovies.ifEmpty { viewModel.uiState.value?.movies ?: emptyList() }
                    val filtered = source.filter { it.name.contains(query, ignoreCase = true) }
                    adapter.submitList(filtered)
                    v.tvCount.text = "${filtered.size} RESULTS"
                    v.tvCurrentCategory.text = "SEARCH: $query"
                }
            }
        })
    }

    private fun buildCategoryMenu(categories: List<VodCategory>, selected: VodCategory?) {
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
        val favItems     = allFavs.filter { it.type == FavType.MOVIE && !it.hasResume }
        val continueItems = allFavs.filter { it.hasResume && it.type == FavType.MOVIE }

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
                    showFavouritesMovies()
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

    private fun showFavouritesMovies() {
        val favIds = FavouritesManager.getAll(this)
            .filter { it.type == FavType.MOVIE && !it.hasResume }
            .map { it.streamId }.toSet()
        val favMovies = allMovies.filter { it.streamId in favIds }
        adapter.submitList(favMovies)
        v.tvCount.text = "${favMovies.size} TITLES"
        v.tvCurrentCategory.text = "FAVOURITES"
    }

    private fun showContinueWatching() {
        val continueItems = FavouritesManager.getAll(this).filter { it.hasResume && it.type == FavType.MOVIE }
        val resumeLabels = continueItems.associate { fav ->
            fav.streamId to "▶ FROM ${FavouritesManager.formatDuration(fav.resumePositionMs)} / ${FavouritesManager.formatDuration(fav.durationMs)}"
        }
        val resumeMovies = allMovies.filter { it.streamId in resumeLabels }
        adapter.submitList(resumeMovies, resumeLabels)
        v.tvCount.text = "${resumeMovies.size} TITLES"
        v.tvCurrentCategory.text = "CONTINUE WATCHING"
    }

    private fun onMovieSelected(movie: VodStream) {
        val creds = viewModel.getCredentials() ?: return
        if (showingContinue) {
            val favId  = "movie_${movie.streamId}"
            val fav    = FavouritesManager.getById(this, favId)
            val vodUrl = XtreamRepository().buildVodUrl(
                creds.first, creds.second, creds.third,
                movie.streamId, movie.containerExtension ?: "mp4"
            )
            PlayerLauncher.launch(
                activity  = this,
                streamUrl = vodUrl,
                title     = movie.name,
                streamId  = movie.streamId,
                isLive    = false,
                favId     = favId,
                artUrl    = movie.streamIcon ?: "",
                resumeMs  = fav?.resumePositionMs ?: 0L
            )
            return
        }
        startActivity(Intent(this, MovieDetailActivity::class.java).apply {
            putExtra(MovieDetailActivity.EXTRA_STREAM_ID, movie.streamId)
            putExtra(MovieDetailActivity.EXTRA_STREAM_NAME, movie.name)
            putExtra(MovieDetailActivity.EXTRA_STREAM_ICON, movie.streamIcon ?: "")
            putExtra(MovieDetailActivity.EXTRA_CONTAINER_EXT, movie.containerExtension ?: "mp4")
            putExtra(MovieDetailActivity.EXTRA_RATING, movie.rating ?: "")
            putExtra(MovieDetailActivity.EXTRA_SERVER_URL, creds.first)
            putExtra(MovieDetailActivity.EXTRA_USERNAME, creds.second)
            putExtra(MovieDetailActivity.EXTRA_PASSWORD, creds.third)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
