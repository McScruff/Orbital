package com.orbital.iptv.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.orbital.iptv.R
import com.orbital.iptv.data.api.ApiClient
import com.orbital.iptv.data.model.LiveCategory
import com.orbital.iptv.data.model.LiveStream
import com.orbital.iptv.data.model.ServerProfile
import com.orbital.iptv.databinding.ActivityHomeBinding
import com.orbital.iptv.ui.login.LoginActivity
import com.orbital.iptv.ui.epg.EpgActivity
import com.orbital.iptv.ui.player.PlayerActivity
import com.orbital.iptv.ui.games.GamesActivity
import com.orbital.iptv.ui.series.SeriesActivity
import com.orbital.iptv.ui.favourites.FavouritesActivity
import com.orbital.iptv.ui.vod.VodActivity
import com.orbital.iptv.utils.CategoryPrefs
import com.orbital.iptv.utils.ChannelQueue
import com.orbital.iptv.utils.ContentCache
import com.orbital.iptv.utils.EpgCache
import com.orbital.iptv.utils.FavouritesManager
import com.orbital.iptv.utils.PlayerType
import com.orbital.iptv.utils.PrefsManager
import com.orbital.iptv.utils.EmbyPrefsManager
import com.orbital.iptv.utils.PlexPrefsManager
import com.orbital.iptv.utils.ThemeManager
import com.orbital.iptv.utils.PlayerLauncher
import com.orbital.iptv.utils.ReminderBus
import com.orbital.iptv.utils.UpdateChecker
import com.orbital.iptv.ui.emby.EmbyBrowserActivity
import com.orbital.iptv.ui.emby.EmbyLoginActivity
import com.orbital.iptv.ui.plex.PlexBrowserActivity
import com.orbital.iptv.ui.plex.PlexLoginActivity
import com.orbital.iptv.ui.search.GlobalSearchActivity
import com.orbital.iptv.ui.tv.TvModeActivity
import com.orbital.iptv.ui.tv.TvModeHolder
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var viewModel: HomeViewModel
    private lateinit var channelAdapter: ChannelAdapter
    private var lastNavMs = 0L



    override fun onResume() {
        super.onResume()
        ReminderBus.register { r -> showReminderDialog(r) }
    }

    override fun onPause() {
        super.onPause()
        ReminderBus.unregister()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) &&
            binding.rvChannels.hasFocus()
        ) {
            val now = System.currentTimeMillis()
            if (now - lastNavMs < 80L) return true  // absorb the event, don't pass to RecyclerView
            lastNavMs = now
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showReminderDialog(r: ReminderBus.Reminder) {
        val msg = buildString {
            append(r.title)
            if (r.channelName.isNotBlank()) append("\n${r.channelName}")
            append("\n\nThis programme is starting now.")
        }
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("📺  PROGRAMME STARTING")
            .setMessage(msg)
            .setPositiveButton("WATCH NOW") { _, _ ->
                if (r.streamUrl.isNotBlank()) {
                    PlayerLauncher.launch(
                        activity  = this,
                        streamUrl = r.streamUrl,
                        title     = r.channelName,
                        streamId  = r.streamId,
                        isLive    = true
                    )
                }
            }
            .setNegativeButton("DISMISS", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ThemeManager.load(this)
        ApiClient.liveFormat = PrefsManager.getLiveFormat(this)
        viewModel = ViewModelProvider(this)[HomeViewModel::class.java]
        setupRecyclerView()
        setupTabButtons()
        applyTheme()
        observeViewModel()
        loadData()
        checkForUpdates()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (PrefsManager.isTvModeEnabled(this@HomeActivity)) {
                    val url = PrefsManager.getLastTvChannelUrl(this@HomeActivity)
                    if (url != null) {
                        startActivity(Intent(this@HomeActivity, TvModeActivity::class.java).apply {
                            putExtra(TvModeActivity.EXTRA_STREAM_URL,   url)
                            putExtra(TvModeActivity.EXTRA_CHANNEL_NAME, PrefsManager.getLastTvChannelName(this@HomeActivity) ?: "")
                            putExtra(TvModeActivity.EXTRA_STREAM_ID,    PrefsManager.getLastTvStreamId(this@HomeActivity))
                            putExtra(TvModeActivity.EXTRA_CATEGORY_ID,  PrefsManager.getLastTvCategoryId(this@HomeActivity))
                        })
                        return
                    }
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    private fun setupTabButtons() {
        binding.tabServices?.setOnClickListener { showSettingsMenu() }
        binding.tabBoxOffice?.setOnClickListener { showBoxOfficeMenu() }
        binding.tabInteractive?.setOnClickListener { startActivity(Intent(this, GamesActivity::class.java)) }
        binding.tabRadio?.setOnClickListener { startActivity(Intent(this, com.orbital.iptv.ui.radio.RadioActivity::class.java)) }

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
        data class MenuItem(val label: String, val action: () -> Unit)
        val items = mutableListOf(
            MenuItem("MOVIES")     { startActivity(Intent(this, VodActivity::class.java)) },
            MenuItem("SERIES")     { startActivity(Intent(this, SeriesActivity::class.java)) },
            MenuItem("CATCHUP")    { startActivity(Intent(this, com.orbital.iptv.ui.catchup.CatchupActivity::class.java)) },
            MenuItem("CONTINUE WATCHING / FAVOURITES") { startActivity(Intent(this, FavouritesActivity::class.java)) }
        )
        if (EmbyPrefsManager.getSession(this) != null)
            items.add(MenuItem("EMBY") { startActivity(Intent(this, EmbyBrowserActivity::class.java)) })
        if (PlexPrefsManager.getSession(this) != null)
            items.add(MenuItem("PLEX") { startActivity(Intent(this, PlexBrowserActivity::class.java)) })
        items.add(MenuItem("🔍 SEARCH ALL") { startActivity(Intent(this, GlobalSearchActivity::class.java)) })
        items.add(MenuItem("⏺ RECORDINGS") { startActivity(Intent(this, com.orbital.iptv.recording.RecordingsActivity::class.java)) })

        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("BOX OFFICE")
            .setItems(items.map { it.label }.toTypedArray()) { _, i -> items[i].action() }
            .show()
    }

    private fun showSettingsMenu() {
        val currentPlayer = PrefsManager.getPlayerType(this)
        val playerLabel = "PLAYER: ${if (currentPlayer == PlayerType.EXTERNAL) "EXTERNAL APP" else "EXOPLAYER (BUILT-IN)"}"
        val serverCount = PrefsManager.getProfiles(this).size
        val activeProfile = PrefsManager.getActiveProfile(this)
        val serverName    = activeProfile?.name?.uppercase() ?: "SERVER"
        val themeLabel = "THEME: ${ThemeManager.current.label}"

        data class Item(val label: String, val action: () -> Unit)
        val items = mutableListOf<Item>()
        val tvModeLabel = "TV MODE: ${if (PrefsManager.isTvModeEnabled(this)) "ON" else "OFF"}"
        val pipLabel    = "PICTURE IN PICTURE: ${if (PrefsManager.isPipEnabled(this)) "ON" else "OFF"}"
        items += Item("SERVERS ($serverCount SAVED)")     { showServerManager() }
        items += Item(playerLabel)                        { showPlayerPicker() }
        items += Item("↺  REFRESH $serverName")          { refreshServer() }
        items += Item("MANAGE VISIBLE CATEGORIES")        { showManageServerCategoriesDialog() }
        items += Item(themeLabel)                         { showThemePicker() }
        items += Item(tvModeLabel)                        { toggleTvMode() }
        items += Item(pipLabel)                           { PrefsManager.setPipEnabled(this, !PrefsManager.isPipEnabled(this)) }
        val subKeyLabel = if (PrefsManager.getOpenSubsApiKey(this) != null)
            "OPENSUBTITLES: KEY SET" else "OPENSUBTITLES: NO KEY SET"
        items += Item(subKeyLabel)                        { showOpenSubsKeyDialog() }
        val liveFormatLabel = "LIVE STREAM FORMAT: ${PrefsManager.getLiveFormat(this).uppercase()}"
        items += Item(liveFormatLabel) { toggleLiveFormat() }
        val decodeLabel = "DECODE MODE: ${if (PrefsManager.isCompatibleDecode(this)) "COMPATIBLE" else "HARDWARE (FAST)"}"
        items += Item(decodeLabel) { toggleCompatibleDecode() }
        items += Item("CHECK FOR UPDATES")                { checkForUpdatesManually() }
        items += Item("CLEAR ALL SAVED DATA")             { confirmClearAllData() }
        val versionName = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        items += Item("ORBITAL  v$versionName")           { }

        val labels = items.map { it.label }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("SETTINGS")
            .setItems(labels) { _, which -> items[which].action() }
            .show()
    }

    private fun showManageServerCategoriesDialog() {
        val categories = viewModel.uiState.value?.xtreamCategories ?: emptyList()
        if (categories.isEmpty()) {
            android.widget.Toast.makeText(this, "NO SERVER CATEGORIES LOADED", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val hiddenIds = CategoryPrefs.getHiddenServerCatIds(this).toMutableSet()
        val labels  = categories.map { it.categoryName.uppercase() }.toTypedArray()
        val checked = categories.map { it.categoryId !in hiddenIds }.toBooleanArray()

        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("SHOW / HIDE CATEGORIES")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) hiddenIds.remove(categories[which].categoryId)
                else           hiddenIds.add(categories[which].categoryId)
            }
            .setPositiveButton("SAVE") { _, _ ->
                CategoryPrefs.setHiddenServerCatIds(this, hiddenIds)
                loadData()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }


    private fun showOpenSubsKeyDialog() {
        val current = PrefsManager.getOpenSubsApiKey(this) ?: ""
        val et = android.widget.EditText(this).apply {
            setText(current)
            hint = "PASTE API KEY HERE"
            setPadding(48, 24, 48, 24)
        }
        val builder = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("OPENSUBTITLES API KEY")
            .setMessage("Get a free key at opensubtitles.com → Consumers")
            .setView(et)
            .setPositiveButton("SAVE") { _, _ ->
                val key = et.text.toString().trim()
                if (key.isNotBlank()) PrefsManager.setOpenSubsApiKey(this, key)
            }
            .setNegativeButton("CANCEL", null)
        if (current.isNotBlank()) {
            builder.setNeutralButton("REMOVE KEY") { _, _ ->
                PrefsManager.setOpenSubsApiKey(this, "")
            }
        }
        builder.show()
    }

    private fun showThemePicker() {
        val themes = ThemeManager.allThemes
        val labels = themes.map { t ->
            if (t == ThemeManager.current) "● ${t.label}" else "○ ${t.label}"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
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

        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
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
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("REMOVE SERVER")
            .setItems(labels) { _, which ->
                val toDelete = profiles[which]
                androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
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

    private fun refreshServer() {
        val name = PrefsManager.getActiveProfile(this)?.name?.uppercase() ?: "SERVER"
        android.widget.Toast.makeText(this, "REFRESHING $name...", android.widget.Toast.LENGTH_SHORT).show()
        TvModeHolder.allChannels = emptyList()
        TvModeHolder.categories  = emptyList()
        viewModel.refreshServer()
    }

    private fun confirmClearAllData() {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("CLEAR ALL SAVED DATA")
            .setMessage("This will remove all server profiles, favourites, and cached data. You will need to log in again. Are you sure?")
            .setPositiveButton("CLEAR ALL") { _, _ ->
                PrefsManager.clearCredentials(this)
                finish()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showPlayerPicker() {
        val current = PrefsManager.getPlayerType(this)
        val labels = arrayOf(
            if (current == PlayerType.EXOPLAYER) "● EXOPLAYER (BUILT-IN)" else "○ EXOPLAYER (BUILT-IN)",
            if (current == PlayerType.EXTERNAL) "● EXTERNAL APP (VLC / MX PLAYER)" else "○ EXTERNAL APP (VLC / MX PLAYER)"
        )
        val builder = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
        builder.setTitle("SELECT PLAYER")
        builder.setItems(labels) { _, which ->
            val type = if (which == 0) PlayerType.EXOPLAYER else PlayerType.EXTERNAL
            PrefsManager.setPlayerType(this, type)
            android.widget.Toast.makeText(this, "PLAYER SET TO: ${type.name}", android.widget.Toast.LENGTH_SHORT).show()
        }
        builder.show()
    }

    private fun setupRecyclerView() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { stream -> onChannelSelected(stream) },
            onChannelLongClick = { stream -> onChannelLongPressed(stream) }
        )
        binding.rvChannels.apply {
            adapter = channelAdapter
            layoutManager = LinearLayoutManager(this@HomeActivity)
            itemAnimator = null  // prevent animation-based focus loss during rapid D-pad scroll
            // Add spacing between cards when the theme requests it
            addItemDecoration(object : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: android.graphics.Rect, view: View,
                    parent: androidx.recyclerview.widget.RecyclerView,
                    state: androidx.recyclerview.widget.RecyclerView.State
                ) {
                    val m = (ThemeManager.palette().itemMarginDp * resources.displayMetrics.density).toInt()
                    outRect.top    = m / 2
                    outRect.bottom = m / 2
                    outRect.left   = m
                    outRect.right  = m
                }
            })
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

            setupCategoryMenu(state.xtreamCategories, state.selectedXtreamCategory)
        }
    }

    private fun setupCategoryMenu(categories: List<LiveCategory>, selected: LiveCategory?) {
        val hiddenIds = CategoryPrefs.getHiddenServerCatIds(this)
        val visibleCategories = categories.filter { it.categoryId !in hiddenIds }

        binding.headerTvListings.setOnClickListener { launchEpgGuide() }
        binding.headerTvListings.setOnFocusChangeListener { _, hasFocus ->
            val p = ThemeManager.palette()
            binding.headerTvListings.setBackgroundColor(if (hasFocus) p.focus else p.highlight)
        }
        binding.headerTvListings.text = "  1  TV GUIDE"

        // Hide the fixed Sky category buttons permanently
        binding.catEntertainment.visibility = View.GONE
        binding.catMovies.visibility = View.GONE
        binding.catSports.visibility = View.GONE
        binding.catNews.visibility = View.GONE
        binding.catChildren.visibility = View.GONE
        binding.catMusic.visibility = View.GONE
        binding.catOther.visibility = View.GONE

        binding.originalCatContainer?.visibility = View.VISIBLE
        binding.originalCatContainer?.removeAllViews()

        val rowHeightPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, 38f, resources.displayMetrics
        ).toInt()
        val paddingPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics
        ).toInt()

        val p = ThemeManager.palette()
        val density = resources.displayMetrics.density
        val marginPx = (p.itemMarginDp * density).toInt()

        // Favourites virtual category — always shown at top
        val isFavSelected = selected?.categoryId == HomeViewModel.FAV_CATEGORY_ID
        val favTv = android.widget.TextView(this).apply {
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, rowHeightPx
            )
            if (marginPx > 0) lp.setMargins(marginPx, marginPx / 2, marginPx, marginPx / 2)
            layoutParams = lp
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(paddingPx, 0, 0, 0)
            text = "  ★  FAVOURITES"
            textSize = 12f
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
            isClickable = true
            isFocusable = true
            if (p.cardElevation > 0f) elevation = p.cardElevation * density
            clipToOutline = true
            if (isFavSelected) {
                background = ThemeManager.roundedBg(p.highlight, density)
                setTextColor(0xFF000000.toInt())
                binding.tvCurrentCategory?.text = "FAVOURITES"
            } else {
                background = ThemeManager.roundedBg(p.bgMid, density)
                setTextColor(0xFFFFFFFF.toInt())
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (!isFavSelected) background = ThemeManager.roundedBg(if (hasFocus) p.focus else p.bgMid, density)
            }
            setOnClickListener {
                val ids = FavouritesManager.getLiveChannels(this@HomeActivity).map { it.streamId }.toSet()
                viewModel.selectFavouriteChannels(ids)
            }
        }
        binding.originalCatContainer?.addView(favTv)

        visibleCategories.forEachIndexed { index, category ->
            val normalBg = if (index % 2 == 0) p.bgMid else p.bgPrimary
            val tv = android.widget.TextView(this).apply {
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, rowHeightPx
                )
                if (marginPx > 0) lp.setMargins(marginPx, marginPx / 2, marginPx, marginPx / 2)
                layoutParams = lp
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(paddingPx, 0, 0, 0)
                text = "  ${index + 2}  ${category.categoryName.uppercase()}"
                textSize = 12f
                typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
                isClickable = true
                isFocusable = true
                if (p.cardElevation > 0f) elevation = p.cardElevation * density
                clipToOutline = true

                val isSelected = category.categoryId == selected?.categoryId
                if (isSelected) {
                    background = ThemeManager.roundedBg(p.highlight, density)
                    setTextColor(0xFF000000.toInt())
                    binding.tvCurrentCategory?.text = category.categoryName.uppercase()
                } else {
                    background = ThemeManager.roundedBg(normalBg, density)
                    setTextColor(0xFFFFFFFF.toInt())
                }

                setOnFocusChangeListener { _, hasFocus ->
                    if (!isSelected) background = ThemeManager.roundedBg(if (hasFocus) p.focus else normalBg, density)
                }

                setOnClickListener { viewModel.selectXtreamCategory(category) }
            }
            binding.originalCatContainer?.addView(tv)
        }
    }

    private fun toggleCompatibleDecode() {
        val next = !PrefsManager.isCompatibleDecode(this)
        PrefsManager.setCompatibleDecode(this, next)
        val msg = if (next) "COMPATIBLE DECODE ON — restart the app for this to take effect"
                  else "HARDWARE DECODE ON — restart the app for this to take effect"
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun toggleLiveFormat() {
        val current = PrefsManager.getLiveFormat(this)
        val next = if (current == "ts") "m3u8" else "ts"
        PrefsManager.setLiveFormat(this, next)
        ApiClient.liveFormat = next
        val label = if (next == "m3u8") "HLS (.m3u8) — try this if TS shows black screen" else "MPEG-TS (.ts) — standard format"
        android.widget.Toast.makeText(this, "LIVE FORMAT SET TO: $label", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun toggleTvMode() {
        val enabled = !PrefsManager.isTvModeEnabled(this)
        PrefsManager.setTvModeEnabled(this, enabled)
        val msg = if (enabled) "TV MODE ON — LIVE CHANNELS OPEN FULL SCREEN" else "TV MODE OFF"
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
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
                android.widget.Toast.makeText(this@HomeActivity, "ORBITAL IS UP TO DATE", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showUpdateDialog(update: com.orbital.iptv.utils.UpdateInfo) {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("UPDATE AVAILABLE — v${update.versionName}")
            .setMessage(update.releaseNotes.uppercase().ifEmpty { "A NEW VERSION OF ORBITAL IS AVAILABLE." })
            .setPositiveButton("DOWNLOAD & INSTALL") { _, _ -> startUpdateDownload(update) }
            .setNegativeButton("LATER", null)
            .show()
    }

    private fun startUpdateDownload(update: com.orbital.iptv.utils.UpdateInfo) {
        // Build progress dialog programmatically
        val ctx = this
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }
        val tvStatus = android.widget.TextView(ctx).apply {
            text = "CONNECTING..."
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
        }
        val progressBar = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 24 }
            max = 100
            progress = 0
        }
        val tvBytes = android.widget.TextView(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 8 }
            text = ""
            setTextColor(0xFFAABBCC.toInt())
            textSize = 10f
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
            gravity = android.view.Gravity.END
        }
        container.addView(tvStatus)
        container.addView(progressBar)
        container.addView(tvBytes)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("DOWNLOADING v${update.versionName}")
            .setView(container)
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleScope.launch {
            val file = UpdateChecker.downloadWithProgress(this@HomeActivity, update.downloadUrl) { pct, done, total ->
                if (pct >= 0) {
                    progressBar.progress = pct
                    tvStatus.text = "DOWNLOADING...  $pct%"
                } else {
                    tvStatus.text = "DOWNLOADING..."
                }
                if (total > 0) {
                    tvBytes.text = "${formatMb(done)} / ${formatMb(total)} MB"
                }
            }

            dialog.dismiss()

            if (file != null) {
                tvStatus.text = "INSTALLING..."
                UpdateChecker.installApk(this@HomeActivity, file)
                finish()
            } else {
                android.widget.Toast.makeText(
                    this@HomeActivity, "DOWNLOAD FAILED — CHECK CONNECTION", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun formatMb(bytes: Long) = "%.1f".format(bytes / 1_048_576.0)

    private fun loadData() {
        val credentials = PrefsManager.getCredentials(this) ?: run {
            finish()
            return
        }
        viewModel.loadData(credentials.serverUrl, credentials.username, credentials.password)
    }

    private fun onChannelLongPressed(stream: LiveStream) {
        val inFavourites = viewModel.uiState.value?.selectedXtreamCategory?.categoryId == HomeViewModel.FAV_CATEGORY_ID
        if (inFavourites) {
            androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
                .setTitle(stream.name.uppercase())
                .setItems(arrayOf("REMOVE FROM FAVOURITES", "CANCEL")) { _, which ->
                    if (which == 0) {
                        FavouritesManager.removeLive(this, stream.streamId)
                        val ids = FavouritesManager.getLiveChannels(this).map { it.streamId }.toSet()
                        viewModel.selectFavouriteChannels(ids)
                    }
                }
                .show()
        } else {
            val streamUrl = viewModel.buildStreamUrl(stream.streamId)
            FavouritesManager.addLiveChannel(this, stream.name, stream.streamId, streamUrl, stream.streamIcon)
            android.widget.Toast.makeText(this, "ADDED TO FAVOURITES", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun onChannelSelected(stream: LiveStream) {
        if (PrefsManager.isTvModeEnabled(this)) {
            val streamUrl = viewModel.buildStreamUrl(stream.streamId)
            val catId = viewModel.uiState.value?.selectedXtreamCategory?.categoryId
                ?.takeIf { it != HomeViewModel.FAV_CATEGORY_ID } ?: ""
            TvModeHolder.allChannels = viewModel.getAllStreams()
            TvModeHolder.categories  = viewModel.uiState.value?.xtreamCategories ?: emptyList()
            PrefsManager.setLastTvChannel(this, streamUrl, stream.name, stream.streamId, catId)
            startActivity(Intent(this, TvModeActivity::class.java).apply {
                putExtra(TvModeActivity.EXTRA_STREAM_URL,   streamUrl)
                putExtra(TvModeActivity.EXTRA_CHANNEL_NAME, stream.name)
                putExtra(TvModeActivity.EXTRA_STREAM_ID,    stream.streamId)
                putExtra(TvModeActivity.EXTRA_CATEGORY_ID,  catId)
            })
            return
        }

        val channels = viewModel.uiState.value?.channels ?: emptyList()
        ChannelQueue.entries = channels.map { ch ->
            ChannelQueue.Entry(streamId = ch.streamId, name = ch.name, num = ch.num ?: -1)
        }
        ChannelQueue.currentIndex = channels.indexOf(stream).coerceAtLeast(0)

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
