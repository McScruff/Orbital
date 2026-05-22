package com.skyretro.iptv.ui.series

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
import com.skyretro.iptv.data.model.SeriesCategory
import com.skyretro.iptv.data.model.SeriesStream
import com.skyretro.iptv.databinding.ActivitySeriesBinding
import com.skyretro.iptv.utils.ContentCache
import com.skyretro.iptv.utils.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SeriesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySeriesBinding
    private lateinit var viewModel: SeriesViewModel
    private lateinit var adapter: SeriesAdapter
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var allShows: List<SeriesStream> = emptyList()
    private var isSearchActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivitySeriesBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

            if (!isSearchActive) {
                adapter.submitList(state.shows)
                binding.tvShowCount.text = "${state.shows.size} SHOWS"
            }
            if (state.categories.isNotEmpty()) buildCategoryMenu(state.categories, state.selectedCategory)
            state.selectedCategory?.let { binding.tvCurrentCategory.text = it.categoryName.uppercase() }
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
                    val current = viewModel.uiState.value?.shows ?: emptyList()
                    adapter.submitList(current)
                    binding.tvShowCount.text = "${current.size} SHOWS"
                    binding.tvCurrentCategory.text = viewModel.uiState.value?.selectedCategory?.categoryName?.uppercase() ?: "SERIES"
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

        val rowH = (38 * resources.displayMetrics.density).toInt()
        val pad  = (12 * resources.displayMetrics.density).toInt()

        categories.take(60).forEachIndexed { i, cat ->
            val isSelected = cat.categoryId == selected?.categoryId
            container.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, 0, 0, 0)
                text = cat.categoryName.uppercase()
                textSize = 11f
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                isClickable = true; isFocusable = true
                setBackgroundColor(if (isSelected) 0xFFFFCC00.toInt() else if (i % 2 == 0) 0xFF1A3A6A.toInt() else 0xFF0D1B35.toInt())
                setTextColor(if (isSelected) 0xFF000080.toInt() else 0xFFFFFFFF.toInt())
                setOnFocusChangeListener { _, hasFocus ->
                    if (!isSelected) {
                        setBackgroundColor(if (hasFocus) 0xFF2A5A8A.toInt() else if (i % 2 == 0) 0xFF1A3A6A.toInt() else 0xFF0D1B35.toInt())
                    }
                }
                setOnClickListener {
                    binding.etSearch.text?.clear()
                    viewModel.selectCategory(cat)
                }
            })
        }
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
