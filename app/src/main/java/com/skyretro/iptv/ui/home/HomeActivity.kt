package com.skyretro.iptv.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.skyretro.iptv.R
import com.skyretro.iptv.data.model.LiveCategory
import com.skyretro.iptv.data.model.LiveStream
import com.skyretro.iptv.data.model.ServerProfile
import com.skyretro.iptv.data.model.SkyCategory
import com.skyretro.iptv.databinding.ActivityHomeBinding
import com.skyretro.iptv.ui.login.LoginActivity
import com.skyretro.iptv.ui.epg.EpgActivity
import com.skyretro.iptv.ui.player.PlayerActivity
import com.skyretro.iptv.ui.games.GamesActivity
import com.skyretro.iptv.ui.series.SeriesActivity
import com.skyretro.iptv.ui.favourites.FavouritesActivity
import com.skyretro.iptv.ui.vod.VodActivity
import com.skyretro.iptv.ui.settings.CategoryEditorActivity
import com.skyretro.iptv.utils.CategoryPrefs
import com.skyretro.iptv.utils.ContentCache
import com.skyretro.iptv.utils.EpgCache
import com.skyretro.iptv.utils.PlayerType
import com.skyretro.iptv.utils.PrefsManager
import com.skyretro.iptv.utils.ThemeManager
import com.skyretro.iptv.utils.UpdateChecker
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var viewModel: HomeViewModel
    private lateinit var channelAdapter: ChannelAdapter

    private val categoryEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            refreshCategoryLabels()
            loadData()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ThemeManager.load(this)
        viewModel = ViewModelProvider(this)[HomeViewModel::class.java]
        setupRecyclerView()
        setupCategoryButtons()
        setupTabButtons()
        applyTheme()
        refreshCategoryLabels()
        observeViewModel()
        loadData()
        checkForUpdates()
    }

    private fun setupTabButtons() {
        binding.tabServices?.setOnClickListener { showSettingsMenu() }
        binding.tabBoxOffice?.setOnClickListener { showBoxOfficeMenu() }
        binding.tabInteractive?.setOnClickListener { startActivity(Intent(this, GamesActivity::class.java)) }
        binding.tabRadio?.setOnClickListener { startActivity(Intent(this, com.skyretro.iptv.ui.radio.RadioActivity::class.java)) }

        val p = ThemeManager.palette()
        binding.tabTvGuide?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.tabTvGuide?.setBackgroundColor(p.focus)
            else binding.tabTvGuide?.setBackgroundColor(p.tabSelected)
        }
        listOf(binding.tabBoxOffice, binding.tabRadio, binding.tabInteractive, binding.tabServices).forEach { tab ->
            tab?.setOnFocusChangeListener { _, hasFocus ->
                tab.setBackgroundColor(if (hasFocus) p.focus else p.bgHeader)
            }
        }
    }

    private fun showBoxOfficeMenu() {
        val options = arrayOf("MOVIES", "SERIES", "CATCHUP", "FAVOURITES")
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_SkyRetro_Dialog)
            .setTitle("BOX OFFICE")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, VodActivity::class.java))
                    1 -> startActivity(Intent(this, SeriesActivity::class.java))
                    2 -> startActivity(Intent(this, com.skyretro.iptv.ui.catchup.CatchupActivity::class.java))
                    3 -> startActivity(Intent(this, FavouritesActivity::class.java))
                }
            }
            .show()
    }

    private fun showSettingsMenu() {
        val useOriginal = PrefsManager.useOriginalCategories(this)
        val toggleText = if (useOriginal) "CATEGORIES: SERVER — TAP TO USE SKY" else "CATEGORIES: SKY — TAP TO USE SERVER"
        val currentPlayer = PrefsManager.getPlayerType(this)
        val playerLabel = "PLAYER: ${if (currentPlayer == PlayerType.EXTERNAL) "EXTERNAL APP" else "EXOPLAYER (BUILT-IN)"}"
        val serverCount = PrefsManager.getProfiles(this).size
        val cachedCount = EpgCache.getCachedCount(this)
        val cacheLabel = "REFRESH CACHE ($cachedCount CHANNELS CACHED)"
        val themeLabel = "THEME: ${ThemeManager.current.label}"

        val options = arrayOf(
            "SERVERS ($serverCount SAVED)",
            toggleText,
            playerLabel,
            cacheLabel,
            "CATEGORY EDITOR",
            themeLabel,
            "CHECK FOR UPDATES",
            "CLEAR ALL SAVED DATA",
            "CANCEL"
        )
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_SkyRetro_Dialog)
            .setTitle("SETTINGS")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showServerManager()
                    1 -> { PrefsManager.setUseOriginalCategories(this, !useOriginal); loadData() }
                    2 -> showPlayerPicker()
                    3 -> refreshAllCaches()
                    4 -> launchCategoryEditor()
                    5 -> showThemePicker()
                    6 -> checkForUpdatesManually()
                    7 -> { PrefsManager.clearCredentials(this); finish() }
                }
            }
            .show()
    }

    private fun launchCategoryEditor() {
        val categories = viewModel.uiState.value?.xtreamCategories ?: emptyList()
        val intent = Intent(this, CategoryEditorActivity::class.java).apply {
            putStringArrayListExtra(CategoryEditorActivity.EXTRA_CAT_IDS,   ArrayList(categories.map { it.categoryId }))
            putStringArrayListExtra(CategoryEditorActivity.EXTRA_CAT_NAMES, ArrayList(categories.map { it.categoryName }))
        }
        categoryEditorLauncher.launch(intent)
    }

    private fun refreshCategoryLabels() {
        val catViews = listOf(
            SkyCategory.ENTERTAINMENT      to binding.catEntertainment,
            SkyCategory.MOVIES             to binding.catMovies,
            SkyCategory.SPORTS             to binding.catSports,
            SkyCategory.NEWS_DOCUMENTARIES to binding.catNews,
            SkyCategory.CHILDREN           to binding.catChildren,
            SkyCategory.MUSIC_SPECIALIST   to binding.catMusic,
            SkyCategory.OTHER_CHANNELS     to binding.catOther
        )
        catViews.forEach { (sky, view) ->
            view?.text = "  ${sky.number}  ${CategoryPrefs.getCategoryName(this, sky)}"
        }
    }

    private fun showThemePicker() {
        val themes = ThemeManager.allThemes
        val labels = themes.map { t ->
            if (t == ThemeManager.current) "● ${t.label}" else "○ ${t.label}"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_SkyRetro_Dialog)
            .setTitle("SELECT THEME")
            .setItems(labels) { _, which ->
                ThemeManager.set(this, themes[which])
                recreate()
            }
            .show()
    }

    private fun showServerManager() {
        val profiles = PrefsManager.getProfiles(this)
        val activeId = PrefsManager.getActiveProfileId(this)

        val labels = profiles.map { p ->
            "${if (p.id == activeId) "●" else "○"}  ${p.name.uppercase()}  —  ${p.serverUrl}"
        }.toMutableList<String>()
        labels.add("＋  ADD NEW SERVER")
        labels.add("✕  REMOVE A SERVER")

        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_SkyRetro_Dialog)
            .setTitle("SERVERS")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    profiles.size -> {
                        val intent = Intent(this, LoginActivity::class.java)
                        intent.putExtra("skip_auto", true)
                        startActivity(intent)
                        finish()
                    }
                    profiles.size + 1 -> showDeleteServerPicker(profiles, activeId)
                    else -> {
                        val selected = profiles[which]
                        if (selected.id != activeId) {
                            PrefsManager.setActiveProfile(this, selected.id)
                            PrefsManager.setUseOriginalCategories(this, false)
                            lifecycleScope.launch {
                                EpgCache.clearAll(this@HomeActivity)
                                ContentCache.clearAll(this@HomeActivity)
                                loadData()
                            }
                        }
                    }
                }
            }
            .show()
    }

    private fun showDeleteServerPicker(profiles: List<ServerProfile>, activeId: String?) {
        if (profiles.isEmpty()) return
        val labels = profiles.map { "✕  ${it.name.uppercase()}" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_SkyRetro_Dialog)
            .setTitle("REMOVE SERVER")
            .setItems(labels) { _, which ->
                val toDelete = profiles[which]
                androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_SkyRetro_Dialog)
                    .setTitle("REMOVE ${toDelete.name.uppercase()}?")
                    .setMessage("THIS CANNOT BE UNDONE.")
                    .setPositiveButton("REMOVE") { _, _ ->
                        PrefsManager.deleteProfile(this, toDelete.id)
                        val remaining = PrefsManager.getProfiles(this)
                        if (remaining.isEmpty()) {
                            PrefsManager.clearCredentials(this)
                            finish()
                        } else if (toDelete.id == activeId) {
                            PrefsManager.setActiveProfile(this, remaining.first().id)
                            lifecycleScope.launch {
                                EpgCache.clearAll(this@HomeActivity)
                                ContentCache.clearAll(this@HomeActivity)
                                loadData()
                            }
                        }
                    }
                    .setNegativeButton("CANCEL", null)
                    .show()
            }
            .show()
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

    private fun applyTheme() {
        val p = ThemeManager.palette()
        binding.root.setBackgroundColor(p.bgPrimary)
        binding.layoutNavBar?.setBackgroundColor(p.bgHeader)
        binding.viewTopAccent?.setBackgroundColor(p.accent)
        binding.viewVerticalDivider?.setBackgroundColor(p.accent)
        binding.tabTvGuide?.setBackgroundColor(p.tabSelected)
        listOf(binding.tabBoxOffice, binding.tabRadio, binding.tabInteractive, binding.tabServices).forEach {
            it?.setBackgroundColor(p.bgHeader)
        }
        binding.headerTvListings.setBackgroundColor(p.highlight)
        listOf(binding.catEntertainment, binding.catMovies, binding.catSports,
               binding.catNews, binding.catChildren, binding.catMusic, binding.catOther)
            .forEachIndexed { i, v ->
                v?.setBackgroundColor(if (i % 2 == 0) p.bgMid else p.bgPrimary)
            }
    }

    private fun setupCategoryButtons() {
        val p = ThemeManager.palette()

        binding.headerTvListings.setOnClickListener { launchEpgGuide() }
        binding.headerTvListings.setOnFocusChangeListener { _, hasFocus ->
            binding.headerTvListings.setBackgroundColor(if (hasFocus) p.focus else p.highlight)
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

        categoryViews.forEachIndexed { idx, (view, category) ->
            val normalBg = if (idx % 2 == 0) p.bgMid else p.bgPrimary
            view?.setOnClickListener {
                viewModel.selectCategory(category)
                updateCategoryHighlight(category)
            }
            view?.setOnFocusChangeListener { _, hasFocus ->
                val isSelected = viewModel.uiState.value?.selectedSkyCategory == category
                if (!isSelected) view.setBackgroundColor(if (hasFocus) p.focus else normalBg)
            }
        }
    }

    private fun updateCategoryHighlight(selected: SkyCategory) {
        val p = ThemeManager.palette()
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

        val allCatViews = listOf(
            SkyCategory.ENTERTAINMENT to binding.catEntertainment,
            SkyCategory.MOVIES        to binding.catMovies,
            SkyCategory.SPORTS        to binding.catSports,
            SkyCategory.NEWS_DOCUMENTARIES to binding.catNews,
            SkyCategory.CHILDREN      to binding.catChildren,
            SkyCategory.MUSIC_SPECIALIST   to binding.catMusic,
            SkyCategory.OTHER_CHANNELS     to binding.catOther
        )

        allCatViews.forEachIndexed { idx, (cat, view) ->
            if (cat == selected) {
                view.setBackgroundColor(p.highlight)
                view.setTextColor(0xFF000000.toInt())
            } else {
                view.setBackgroundColor(if (idx % 2 == 0) p.bgMid else p.bgPrimary)
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
                binding.tvCurrentCategory?.text = CategoryPrefs.getCategoryName(this, state.selectedSkyCategory)
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

        val p = ThemeManager.palette()
        categories.forEachIndexed { index, category ->
            val normalBg = if (index % 2 == 0) p.bgMid else p.bgPrimary
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
                    setBackgroundColor(p.highlight)
                    setTextColor(0xFF000000.toInt())
                    binding.tvCurrentCategory?.text = category.categoryName.uppercase()
                } else {
                    setBackgroundColor(normalBg)
                    setTextColor(0xFFFFFFFF.toInt())
                }

                setOnFocusChangeListener { _, hasFocus ->
                    if (!isSelected) setBackgroundColor(if (hasFocus) p.focus else normalBg)
                }

                setOnClickListener { viewModel.selectXtreamCategory(category) }
            }
            binding.originalCatContainer?.addView(tv)
        }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            val update = UpdateChecker.check(this@HomeActivity) ?: return@launch
            showUpdateDialog(update)
        }
    }

    private fun checkForUpdatesManually() {
        android.widget.Toast.makeText(this, "CHECKING FOR UPDATES...", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val update = UpdateChecker.check(this@HomeActivity)
            if (update != null) {
                showUpdateDialog(update)
            } else {
                android.widget.Toast.makeText(this@HomeActivity, "SKYRETRO IS UP TO DATE", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showUpdateDialog(update: com.skyretro.iptv.utils.UpdateInfo) {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_SkyRetro_Dialog)
            .setTitle("UPDATE AVAILABLE — v${update.versionName}")
            .setMessage(update.releaseNotes.uppercase().ifEmpty { "A NEW VERSION OF SKYRETRO IS AVAILABLE." })
            .setPositiveButton("DOWNLOAD & INSTALL") { _, _ ->
                UpdateChecker.downloadAndInstall(this, update.downloadUrl)
            }
            .setNegativeButton("LATER", null)
            .show()
    }

    private fun loadData() {
        val credentials = PrefsManager.getCredentials(this) ?: run {
            finish()
            return
        }
        val useOriginal = PrefsManager.useOriginalCategories(this)
        val customMapping = CategoryPrefs.getCustomMapping(this)
        viewModel.loadData(credentials.serverUrl, credentials.username, credentials.password, useOriginal, customMapping)
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
