package com.skyretro.iptv.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.skyretro.iptv.R
import com.skyretro.iptv.data.model.LiveCategory
import com.skyretro.iptv.data.model.LiveStream
import com.skyretro.iptv.data.model.SkyCategory
import com.skyretro.iptv.databinding.ActivityHomeBinding
import com.skyretro.iptv.ui.login.LoginActivity
import com.skyretro.iptv.ui.epg.EpgActivity
import com.skyretro.iptv.ui.player.PlayerActivity
import com.skyretro.iptv.ui.games.GamesActivity
import com.skyretro.iptv.ui.series.SeriesActivity
import com.skyretro.iptv.ui.favourites.FavouritesActivity
import com.skyretro.iptv.ui.vod.VodActivity
import com.skyretro.iptv.utils.ContentCache
import com.skyretro.iptv.utils.EpgCache
import com.skyretro.iptv.utils.PlayerType
import com.skyretro.iptv.utils.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var viewModel: HomeViewModel
    private lateinit var channelAdapter: ChannelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[HomeViewModel::class.java]
        setupRecyclerView()
        setupCategoryButtons()
        setupTabButtons()
        observeViewModel()
        loadData()
    }

    private fun setupTabButtons() {
        binding.tabServices?.setOnClickListener { showServicesMenu() }
        binding.tabBoxOffice?.setOnClickListener { showBoxOfficeMenu() }
        binding.tabInteractive?.setOnClickListener { startActivity(Intent(this, GamesActivity::class.java)) }

        val focusBlue = 0xFF2D6090.toInt()
        // TV GUIDE stays on sky_tab_selected when not focused; others stay on sky_header_blue
        binding.tabTvGuide?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.tabTvGuide?.setBackgroundColor(focusBlue)
            else binding.tabTvGuide?.setBackgroundResource(R.drawable.bg_tab_selected)
        }
        binding.tabBoxOffice?.setOnFocusChangeListener { _, hasFocus ->
            binding.tabBoxOffice?.setBackgroundColor(if (hasFocus) focusBlue else 0xFF1E3D72.toInt())
        }
        binding.tabServices?.setOnFocusChangeListener { _, hasFocus ->
            binding.tabServices?.setBackgroundColor(if (hasFocus) focusBlue else 0xFF1E3D72.toInt())
        }
        binding.tabInteractive?.setOnFocusChangeListener { _, hasFocus ->
            binding.tabInteractive?.setBackgroundColor(if (hasFocus) focusBlue else 0xFF1E3D72.toInt())
        }
    }

    private fun showBoxOfficeMenu() {
        val options = arrayOf("MOVIES", "SERIES", "FAVOURITES")
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_SkyRetro_Dialog)
            .setTitle("BOX OFFICE")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, VodActivity::class.java))
                    1 -> startActivity(Intent(this, SeriesActivity::class.java))
                    2 -> startActivity(Intent(this, FavouritesActivity::class.java))
                }
            }
            .show()
    }

    private fun showServicesMenu() {
        val useOriginal = PrefsManager.useOriginalCategories(this)
        val toggleText = if (useOriginal) "CATEGORIES: SERVER — TAP TO USE SKY" else "CATEGORIES: SKY — TAP TO USE SERVER"
        val currentPlayer = PrefsManager.getPlayerType(this)
        val playerLabel = "PLAYER: ${if (currentPlayer == PlayerType.EXTERNAL) "EXTERNAL APP" else "EXOPLAYER (BUILT-IN)"}"

        val cachedCount = EpgCache.getCachedCount(this)
        val cacheLabel = "REFRESH CACHE ($cachedCount CHANNELS CACHED)"
        val options = arrayOf("CHANGE SERVER / ADD NEW", toggleText, playerLabel, cacheLabel, "CLEAR ALL SAVED DATA", "CANCEL")
        val builder = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_SkyRetro_Dialog)
        builder.setTitle("SERVICES")
        builder.setItems(options) { _, which ->
            when (which) {
                0 -> {
                    val intent = Intent(this, LoginActivity::class.java)
                    intent.putExtra("skip_auto", true)
                    startActivity(intent)
                    finish()
                }
                1 -> {
                    PrefsManager.setUseOriginalCategories(this, !useOriginal)
                    loadData()
                }
                2 -> showPlayerPicker()
                3 -> refreshAllCaches()
                4 -> {
                    PrefsManager.clearCredentials(this)
                    finish()
                }
            }
        }
        builder.show()
    }

    private fun refreshAllCaches() {
        CoroutineScope(Dispatchers.Main).launch {
            EpgCache.clearAll(this@HomeActivity)
            ContentCache.clearAll(this@HomeActivity)
            android.widget.Toast.makeText(this@HomeActivity, "CACHE CLEARED — WILL REFRESH ON NEXT USE", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun showPlayerPicker() {
        val current = PrefsManager.getPlayerType(this)
        val labels = arrayOf(
            if (current == PlayerType.EXOPLAYER) "● EXOPLAYER (BUILT-IN)" else "○ EXOPLAYER (BUILT-IN)",
            if (current == PlayerType.EXTERNAL) "● EXTERNAL APP (VLC / MX PLAYER)" else "○ EXTERNAL APP (VLC / MX PLAYER)"
        )
        val builder = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_SkyRetro_Dialog)
        builder.setTitle("SELECT PLAYER")
        builder.setItems(labels) { _, which ->
            val type = if (which == 0) PlayerType.EXOPLAYER else PlayerType.EXTERNAL
            PrefsManager.setPlayerType(this, type)
            android.widget.Toast.makeText(this, "PLAYER SET TO: ${type.name}", android.widget.Toast.LENGTH_SHORT).show()
        }
        builder.show()
    }

    private fun setupRecyclerView() {
        channelAdapter = ChannelAdapter { stream -> onChannelSelected(stream) }
        binding.rvChannels.apply {
            adapter = channelAdapter
            layoutManager = LinearLayoutManager(this@HomeActivity)
        }
    }

    private fun launchEpgGuide() {
        val channels = viewModel.uiState.value?.channels?.take(25)
        if (channels.isNullOrEmpty()) return
        startActivity(Intent(this, EpgActivity::class.java).apply {
            putIntegerArrayListExtra(EpgActivity.EXTRA_STREAM_IDS, ArrayList(channels.map { it.streamId }))
            putStringArrayListExtra(EpgActivity.EXTRA_STREAM_NAMES, ArrayList(channels.map { it.name }))
        })
    }

    private fun setupCategoryButtons() {
        val focusBlue = 0xFF2D6090.toInt()
        val normalBlue = 0xFF1A3A6A.toInt()

        binding.headerTvListings.setOnClickListener { launchEpgGuide() }
        binding.headerTvListings.setOnFocusChangeListener { _, hasFocus ->
            binding.headerTvListings.setBackgroundColor(if (hasFocus) focusBlue else 0xFFFFCC00.toInt())
        }

        val categoryViews = listOf(
            binding.catEntertainment to SkyCategory.ENTERTAINMENT,
            binding.catMovies to SkyCategory.MOVIES,
            binding.catSports to SkyCategory.SPORTS,
            binding.catNews to SkyCategory.NEWS_DOCUMENTARIES,
            binding.catChildren to SkyCategory.CHILDREN,
            binding.catMusic to SkyCategory.MUSIC_SPECIALIST,
            binding.catOther to SkyCategory.OTHER_CHANNELS
        )

        categoryViews.forEach { (view, category) ->
            view?.setOnClickListener {
                viewModel.selectCategory(category)
                updateCategoryHighlight(category)
            }
            view?.setOnFocusChangeListener { _, hasFocus ->
                val isSelected = viewModel.uiState.value?.selectedSkyCategory == category
                if (!isSelected) {
                    view.setBackgroundColor(if (hasFocus) focusBlue else normalBlue)
                }
            }
        }
    }

    private fun updateCategoryHighlight(selected: SkyCategory) {
        // Restore Sky category buttons and hide original categories container
        binding.headerTvListings.visibility = View.VISIBLE
        binding.headerTvListings.text = "  1  TV GUIDE LISTINGS"
        binding.catEntertainment.visibility = View.VISIBLE
        binding.catMovies.visibility = View.VISIBLE
        binding.catSports.visibility = View.VISIBLE
        binding.catNews.visibility = View.VISIBLE
        binding.catChildren.visibility = View.VISIBLE
        binding.catMusic.visibility = View.VISIBLE
        binding.catOther.visibility = View.VISIBLE
        binding.originalCatContainer?.visibility = View.GONE
        binding.originalCatContainer?.removeAllViews()

        val allCatViews = mapOf(
            SkyCategory.ENTERTAINMENT to binding.catEntertainment,
            SkyCategory.MOVIES to binding.catMovies,
            SkyCategory.SPORTS to binding.catSports,
            SkyCategory.NEWS_DOCUMENTARIES to binding.catNews,
            SkyCategory.CHILDREN to binding.catChildren,
            SkyCategory.MUSIC_SPECIALIST to binding.catMusic,
            SkyCategory.OTHER_CHANNELS to binding.catOther
        )

        allCatViews.forEach { (cat, view) ->
            if (cat == selected) {
                view.setBackgroundColor(0xFFFFCC00.toInt())
                view.setTextColor(0xFF000080.toInt())
            } else {
                view.setBackgroundColor(0xFF1A3A6A.toInt())
                view.setTextColor(0xFFFFFFFF.toInt())
            }
        }
    }

    private fun observeViewModel() {
        viewModel.epgUpdate.observe(this) { streamId ->
            val channels = viewModel.uiState.value?.channels ?: return@observe
            val pos = channels.indexOfFirst { it.streamId == streamId }
            if (pos >= 0) channelAdapter.notifyItemChanged(pos)
        }

        viewModel.uiState.observe(this) { state ->
            binding.progressBar?.visibility = if (state.isLoading) View.VISIBLE else View.GONE

            state.error?.let { error ->
                binding.tvError?.text = error.uppercase()
                binding.tvError?.visibility = View.VISIBLE
            } ?: run {
                binding.tvError?.visibility = View.GONE
            }

            channelAdapter.submitList(state.channels)
            binding.tvChannelCount?.text = "${state.channels.size} CHANNELS"

            if (state.useOriginalCategories) {
                setupOriginalCategoryMenu(state.xtreamCategories, state.selectedXtreamCategory)
            } else {
                updateCategoryHighlight(state.selectedSkyCategory)
                binding.tvCurrentCategory?.text = state.selectedSkyCategory.displayName
            }
        }
    }

    private fun setupOriginalCategoryMenu(categories: List<LiveCategory>, selected: LiveCategory?) {
        // Hide Sky-specific category buttons (keeps them in hierarchy so they can be restored)
        binding.headerTvListings.text = "  1  CATEGORIES"
        binding.catEntertainment.visibility = View.GONE
        binding.catMovies.visibility = View.GONE
        binding.catSports.visibility = View.GONE
        binding.catNews.visibility = View.GONE
        binding.catChildren.visibility = View.GONE
        binding.catMusic.visibility = View.GONE
        binding.catOther.visibility = View.GONE

        // Populate the dedicated original categories container
        binding.originalCatContainer?.visibility = View.VISIBLE
        binding.originalCatContainer?.removeAllViews()

        val rowHeightPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, 38f, resources.displayMetrics
        ).toInt()
        val paddingPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics
        ).toInt()

        categories.forEachIndexed { index, category ->
            val tv = android.widget.TextView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, rowHeightPx
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(paddingPx, 0, 0, 0)
                text = "  ${index + 2}  ${category.categoryName.uppercase()}"
                textSize = 12f
                typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
                isClickable = true
                isFocusable = true

                val isSelected = category.categoryId == selected?.categoryId
                if (isSelected) {
                    setBackgroundColor(0xFFFFCC00.toInt())
                    setTextColor(0xFF000080.toInt())
                    binding.tvCurrentCategory?.text = category.categoryName.uppercase()
                } else {
                    setBackgroundColor(if (index % 2 == 0) 0xFF1A3A6A.toInt() else 0xFF0D1B35.toInt())
                    setTextColor(0xFFFFFFFF.toInt())
                }

                setOnFocusChangeListener { _, hasFocus ->
                    if (!isSelected) {
                        setBackgroundColor(
                            if (hasFocus) 0xFF2D6090.toInt()
                            else if (index % 2 == 0) 0xFF1A3A6A.toInt() else 0xFF0D1B35.toInt()
                        )
                    }
                }

                setOnClickListener { viewModel.selectXtreamCategory(category) }
            }
            binding.originalCatContainer?.addView(tv)
        }
    }

    private fun loadData() {
        val credentials = PrefsManager.getCredentials(this) ?: run {
            finish()
            return
        }
        val useOriginal = PrefsManager.useOriginalCategories(this)
        viewModel.loadData(credentials.serverUrl, credentials.username, credentials.password, useOriginal)
    }

    private fun onChannelSelected(stream: LiveStream) {
        val streamUrl = viewModel.buildStreamUrl(stream.streamId)
        if (PrefsManager.getPlayerType(this) == PlayerType.EXTERNAL) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(streamUrl), "video/*")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "NO EXTERNAL PLAYER FOUND", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_STREAM_URL, streamUrl)
                putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, stream.name)
                putExtra(PlayerActivity.EXTRA_STREAM_ID, stream.streamId)
                putExtra(PlayerActivity.EXTRA_IS_LIVE, true)
                stream.num?.let { putExtra(PlayerActivity.EXTRA_CHANNEL_NUM, it) }
            }
            startActivity(intent)
        }
    }
}
