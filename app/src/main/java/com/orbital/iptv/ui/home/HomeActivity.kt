package com.orbital.iptv.ui.home

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.bumptech.glide.Glide
import com.orbital.iptv.R
import com.orbital.iptv.data.api.ApiClient
import com.orbital.iptv.data.model.EpgListing
import com.orbital.iptv.data.model.LiveCategory
import com.orbital.iptv.data.model.LiveStream
import com.orbital.iptv.data.model.ServerProfile
import com.orbital.iptv.data.model.getDecodedDescription
import com.orbital.iptv.data.model.getDecodedTitle
import com.orbital.iptv.data.repository.XtreamRepository
import com.orbital.iptv.databinding.ActivityHomeBinding
import com.orbital.iptv.recording.*
import com.orbital.iptv.ui.epg.EpgRow
import com.orbital.iptv.ui.games.BubbleShooterActivity
import com.orbital.iptv.ui.games.GamesActivity
import com.orbital.iptv.ui.games.TeletextActivity
import com.orbital.iptv.ui.sports.SportsActivity
import com.orbital.iptv.ui.login.LoginActivity
import com.orbital.iptv.ui.player.PlayerActivity
import com.orbital.iptv.ui.series.SeriesActivity
import com.orbital.iptv.ui.favourites.FavouritesActivity
import com.orbital.iptv.ui.vod.VodActivity
import com.orbital.iptv.ui.emby.EmbyBrowserActivity
import com.orbital.iptv.ui.emby.EmbyLoginActivity
import com.orbital.iptv.ui.plex.PlexBrowserActivity
import com.orbital.iptv.ui.plex.PlexLoginActivity
import com.orbital.iptv.ui.search.GlobalSearchActivity
import com.orbital.iptv.ui.tv.TvModeActivity
import com.orbital.iptv.ui.tv.TvModeHolder
import com.orbital.iptv.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var viewModel: HomeViewModel
    private val repository = XtreamRepository()
    private var currentChannels: List<LiveStream> = emptyList()
    private var epgLoadingJob: Job? = null

    private val clockFmt = SimpleDateFormat("HH:mm", Locale.UK)
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            binding.tvTime?.text = clockFmt.format(Date())
            clockHandler.postDelayed(this, 30_000L)
        }
    }



    override fun onResume() {
        super.onResume()
        ReminderBus.register { r -> showReminderDialog(r) }
        clockRunnable.run()
    }

    override fun onPause() {
        super.onPause()
        ReminderBus.unregister()
        clockHandler.removeCallbacks(clockRunnable)
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
        setupEpgView()
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
        binding.tabInteractive?.setOnClickListener { showInteractiveMenu() }
        binding.tabRadio?.setOnClickListener { showRadioMenu() }

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

    private fun showRadioMenu() {
        val stations = com.orbital.iptv.ui.radio.RadioStations.load(this)
        if (stations.isEmpty()) {
            Toast.makeText(this, "NO RADIO STATIONS FOUND", Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("RADIO")
            .setItems(stations.map { it.name.uppercase() }.toTypedArray()) { _, which ->
                val station = stations[which]
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_STREAM_URL, station.url)
                    putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, station.name)
                    putExtra(PlayerActivity.EXTRA_IS_LIVE, true)
                })
            }
            .show()
    }

    private fun showInteractiveMenu() {
        data class MenuItem(val label: String, val action: () -> Unit)
        val items = mutableListOf(
            MenuItem("SPORTS")         { startActivity(Intent(this, SportsActivity::class.java)) },
            MenuItem("TELETEXT")       { startActivity(Intent(this, TeletextActivity::class.java)) },
            MenuItem("BUBBLE SHOOTER") { startActivity(Intent(this, BubbleShooterActivity::class.java)) },
        )
        val tickerOn = TickerManager.newsTickerEnabled
        items.add(MenuItem("NEWS TICKER: ${if (tickerOn) "ON" else "OFF"}") {
            TickerManager.newsTickerEnabled = !tickerOn
            TickerManager.sportHeadlines.clear()
            showInteractiveMenu()
        })
        items.add(MenuItem("TICKER SPORTS...") { showTickerSportPicker() })
        items.add(MenuItem("EMBY") {
            val dest = if (EmbyPrefsManager.getSession(this) != null) EmbyBrowserActivity::class.java else EmbyLoginActivity::class.java
            startActivity(Intent(this, dest))
        })
        items.add(MenuItem("PLEX") {
            val dest = if (PlexPrefsManager.getSession(this) != null) PlexBrowserActivity::class.java else PlexLoginActivity::class.java
            startActivity(Intent(this, dest))
        })

        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("INTERACTIVE")
            .setItems(items.map { it.label }.toTypedArray()) { _, i -> items[i].action() }
            .show()
    }

    private fun showTickerSportPicker() {
        val feeds = TickerManager.SPORT_FEEDS
        val selectedIds = TickerManager.getSelectedSportIds(this).toMutableSet()
        val checked = feeds.map { it.id in selectedIds }.toBooleanArray()
        val labels = feeds.map { "${it.emoji} ${it.name}" }.toTypedArray()

        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("SELECT SPORTS FOR NEWS TICKER")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) selectedIds.add(feeds[which].id)
                else selectedIds.remove(feeds[which].id)
            }
            .setPositiveButton("DONE") { _, _ ->
                TickerManager.setSelectedSportIds(this, selectedIds)
                if (selectedIds.isNotEmpty()) TickerManager.newsTickerEnabled = true
                TickerManager.sportHeadlines.clear()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showSettingsMenu() {
        val serverCount = PrefsManager.getProfiles(this).size
        val activeProfile = PrefsManager.getActiveProfile(this)
        val serverName    = activeProfile?.name?.uppercase() ?: "SERVER"
        val themeLabel = "THEME: ${ThemeManager.current.label}"

        data class Item(val label: String, val action: () -> Unit)
        val items = mutableListOf<Item>()
        val tvModeLabel = "TV MODE (TESTING): ${if (PrefsManager.isTvModeEnabled(this)) "ON" else "OFF"}"
        val pipLabel    = "PICTURE IN PICTURE: ${if (PrefsManager.isPipEnabled(this)) "ON" else "OFF"}"
        items += Item("SERVERS ($serverCount SAVED)")     { showServerManager() }
        items += Item("↺  REFRESH $serverName")          { refreshServer() }
        items += Item("MANAGE VISIBLE CATEGORIES")        { showManageServerCategoriesDialog() }
        items += Item(themeLabel)                         { showThemePicker() }
        items += Item(tvModeLabel)                        { toggleTvMode() }
        items += Item(pipLabel)                           { PrefsManager.setPipEnabled(this, !PrefsManager.isPipEnabled(this)) }
        val subKeyLabel = if (PrefsManager.getOpenSubsApiKey(this) != null)
            "OPENSUBTITLES: KEY SET" else "OPENSUBTITLES: NO KEY SET"
        items += Item(subKeyLabel)                        { showOpenSubsKeyDialog() }
        val tmdbKeyLabel = if (PrefsManager.getTmdbApiKey(this) != null)
            "TMDB: KEY SET" else "TMDB: NO KEY SET"
        items += Item(tmdbKeyLabel)                       { showTmdbKeyDialog() }
        val liveFormatLabel = "LIVE STREAM FORMAT: ${PrefsManager.getLiveFormat(this).uppercase()}"
        items += Item(liveFormatLabel) { toggleLiveFormat() }
        items += Item("PIN PROTECTED CATEGORIES")          { showPinProtectedCategories() }
        items += Item("CHANGE PIN")                       { showChangePinDialog() }
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

    private fun showTmdbKeyDialog() {
        val current = PrefsManager.getTmdbApiKey(this) ?: ""
        val et = android.widget.EditText(this).apply {
            setText(current)
            hint = "PASTE API KEY HERE"
            setPadding(48, 24, 48, 24)
        }
        val builder = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("TMDB API KEY")
            .setMessage("Get a free key at themoviedb.org → Settings → API. Used for EPG posters/synopsis.")
            .setView(et)
            .setPositiveButton("SAVE") { _, _ ->
                val key = et.text.toString().trim()
                if (key.isNotBlank()) PrefsManager.setTmdbApiKey(this, key)
            }
            .setNegativeButton("CANCEL", null)
        if (current.isNotBlank()) {
            builder.setNeutralButton("REMOVE KEY") { _, _ ->
                PrefsManager.setTmdbApiKey(this, "")
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

    private fun setupEpgView() {
        binding.epgView.onChannelSelected = { streamId ->
            currentChannels.find { it.streamId == streamId }?.let { onChannelSelected(it) }
        }
        binding.epgView.onChannelLongPress = { streamId ->
            currentChannels.find { it.streamId == streamId }?.let { onChannelLongPressed(it) }
        }
        binding.epgView.onProgrammeSelected = { streamId, channelName, listing ->
            val url = viewModel.buildStreamUrl(streamId)
            handleProgrammeTap(streamId, channelName, url, listing)
        }
        binding.epgView.onRequestFocusLeft = {
            val container = binding.originalCatContainer
            var focused = false
            if (container != null) {
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)
                    if (child.isFocusable) { child.requestFocus(); focused = true; break }
                }
            }
            if (!focused) binding.headerTvListings.requestFocus()
        }
    }

    private fun loadCategoryIntoEpg(channels: List<LiveStream>) {
        currentChannels = channels
        val rows = channels.map { EpgRow(streamId = it.streamId, channelName = it.name) }
        binding.epgView.setRows(rows)
        binding.epgView.scrollToNow()

        epgLoadingJob?.cancel()
        val creds = PrefsManager.getCredentials(this) ?: return
        epgLoadingJob = lifecycleScope.launch {
            channels.forEach { stream ->
                launch {
                    val cached = EpgCache.get(this@HomeActivity, stream.streamId, minCount = 50)
                    if (cached != null) {
                        binding.epgView.updateRow(stream.streamId, cached)
                    } else {
                        val result = repository.getFullChannelEpg(creds.serverUrl, creds.username, creds.password, stream.streamId)
                        result.onSuccess { epg ->
                            val listings = epg.listings ?: emptyList()
                            EpgCache.put(this@HomeActivity, stream.streamId, listings)
                            binding.epgView.updateRow(stream.streamId, listings)
                        }
                    }
                }
            }
        }
    }

    private fun launchEpgGuide() {
        binding.epgView.scrollToNow()
        binding.epgView.requestFocus()
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
        viewModel.uiState.observe(this) { state ->
            binding.progressBar?.visibility = if (state.isLoading) View.VISIBLE else View.GONE

            state.error?.let { error ->
                binding.tvError?.text = error.uppercase()
                binding.tvError?.visibility = View.VISIBLE
            } ?: run {
                binding.tvError?.visibility = View.GONE
            }

            loadCategoryIntoEpg(state.channels)
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
            nextFocusRightId = R.id.epg_view
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
                nextFocusRightId = R.id.epg_view
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

                setOnClickListener {
                    if (PinManager.isCategoryLocked(this@HomeActivity, category.categoryId)) {
                        promptPin("ENTER PIN TO VIEW CATEGORY") { viewModel.selectXtreamCategory(category) }
                    } else {
                        viewModel.selectXtreamCategory(category)
                    }
                }
            }
            binding.originalCatContainer?.addView(tv)
        }
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
        if (enabled) {
            startActivity(Intent(this, TvModeActivity::class.java))
        } else {
            android.widget.Toast.makeText(this, "TV MODE OFF", android.widget.Toast.LENGTH_SHORT).show()
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

    // ── EPG recording / reminder (mirrors EpgActivity) ────────────────────────

    private fun handleProgrammeTap(streamId: Int, channelName: String, url: String, listing: EpgListing) {
        val title   = listing.getDecodedTitle().ifBlank { "Recording" }
        val startMs = (listing.startTimestamp?.toLongOrNull() ?: 0L) * 1000L
        val endMs   = (listing.stopTimestamp?.toLongOrNull()  ?: 0L) * 1000L
        val nowMs   = System.currentTimeMillis()

        if (endMs > 0L && endMs <= nowMs) {
            AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
                .setTitle("CANNOT RECORD")
                .setMessage("'$title' has already finished.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val timeFmt = SimpleDateFormat("HH:mm", Locale.UK)
        val dateFmt = SimpleDateFormat("EEE dd MMM", Locale.UK)
        val availGb = RecordingRepository.availableGb(this)
        val timeStr = if (startMs > 0L) {
            val dateStr = dateFmt.format(Date(startMs))
            val endStr  = if (endMs > 0L) timeFmt.format(Date(endMs)) else "?"
            "$dateStr  ${timeFmt.format(Date(startMs))} — $endStr"
        } else "Time unknown"
        val label = if (startMs > nowMs) "SCHEDULE RECORDING" else "RECORD NOW (ongoing)"

        // The EPG listing's own description is the authoritative synopsis for THIS airing —
        // available synchronously, no catalog guesswork needed. The VOD/series lookup below is
        // now only used to fill in a poster + a short genre/year/rating line, and as a plot
        // fallback for the (rare) case where the broadcaster's EPG feed sends no description.
        val epgSynopsis = listing.getDecodedDescription().trim()

        fun infoBody(catalogHeader: String?, catalogPlotFallback: String?) = listOfNotNull(
            title, "$channelName\n$timeStr",
            catalogHeader?.takeIf { it.isNotBlank() },
            epgSynopsis.ifBlank { catalogPlotFallback ?: "" }.takeIf { it.isNotBlank() },
            "Available storage: ${"%.1f".format(availGb)} GB"
        ).joinToString("\n\n")

        val (posterView, posterImg, infoTv) = buildProgrammeInfoView()
        infoTv.text = infoBody(null, null)

        val builder = AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle(label)
            .setView(posterView)
            .setPositiveButton("RECORD") { _, _ ->
                if (endMs > 0L) {
                    scheduleRecording(streamId, channelName, url, title, startMs.coerceAtLeast(nowMs), endMs)
                } else {
                    askForDuration { durationMs ->
                        scheduleRecording(streamId, channelName, url, title, startMs.coerceAtLeast(nowMs), startMs.coerceAtLeast(nowMs) + durationMs)
                    }
                }
            }
            .setNegativeButton("CANCEL", null)
        if (startMs > nowMs) {
            builder.setNeutralButton("SET REMINDER") { _, _ ->
                scheduleReminder(channelName, title, startMs, url, streamId)
            }
        }
        builder.show()

        // Best-effort VOD/series catalog lookup for a poster (+ genre/year/rating line) — never
        // blocks the dialog, since it depends on the movies/series cache already being warm on disk.
        val durationMinutes = if (startMs > 0L && endMs > startMs) (endMs - startMs) / 60_000L else -1L
        lifecycleScope.launch {
            val creds = PrefsManager.getCredentials(this@HomeActivity) ?: return@launch
            val match = lookupShowPoster(creds.serverUrl, creds.username, creds.password, title, epgSynopsis, durationMinutes)
                ?: return@launch
            match.posterUrl?.takeIf { it.isNotBlank() }?.let { Glide.with(this@HomeActivity).load(it).into(posterImg) }
            infoTv.text = infoBody(match.header, match.plot)
        }
    }

    private enum class ShowKind { MOVIE, SERIES, UNKNOWN }

    /**
     * Xtream's EPG data has no explicit movie/series flag, so infer it from the listing text and
     * runtime: season/episode markers ("S3 E12", "Series 3", "Ep 4/6") are the strongest, least
     * ambiguous signal and always mean SERIES; failing that, a "(YYYY)" year suffix or a runtime
     * over ~75 minutes leans MOVIE, and a short runtime leans SERIES. Used to search the more
     * likely catalog first (and skip a wasted VOD-info lookup when we're confident it's a series).
     */
    private fun classifyShowKind(title: String, description: String, durationMinutes: Long): ShowKind {
        val combined = "$title $description"
        val seriesPattern = Regex(
            """\bS\d{1,2}\s?[:\-]?\s?E\d{1,3}\b|\bSeries\s?\d+\b|\bSeason\s?\d+\b|\bEp(?:isode)?\.?\s?\d+(\s?/\s?\d+)?\b""",
            RegexOption.IGNORE_CASE
        )
        val moviePattern = Regex("""\(\d{4}\)""")

        return when {
            seriesPattern.containsMatchIn(combined) -> ShowKind.SERIES
            moviePattern.containsMatchIn(title) -> ShowKind.MOVIE
            durationMinutes >= 75 -> ShowKind.MOVIE
            durationMinutes in 1 until 70 -> ShowKind.SERIES
            else -> ShowKind.UNKNOWN
        }
    }

    private data class ShowPosterMatch(val posterUrl: String?, val header: String, val plot: String?)

    /** Builds the poster (left) + info text (right) row used inside the programme-tap dialog. */
    private fun buildProgrammeInfoView(): Triple<View, ImageView, TextView> {
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        }
        val poster = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((90 * d).toInt(), (128 * d).toInt())
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF0A1628.toInt())
        }
        val infoTv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * d).toInt()
            }
            setTextColor(0xFFCCDDEE.toInt())
            textSize = 12f
            typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        }
        root.addView(poster)
        root.addView(infoTv)
        return Triple(root, poster, infoTv)
    }

    /**
     * Matches a live-TV programme title against TMDB (if an API key is set) or, failing that,
     * the cached VOD/series catalogs, to find a poster + synopsis.
     */
    private suspend fun lookupShowPoster(
        serverUrl: String, username: String, password: String,
        title: String, description: String, durationMinutes: Long
    ): ShowPosterMatch? = withContext(Dispatchers.IO) {
        val query = title.trim()
        if (query.isBlank()) return@withContext null
        val kind = classifyShowKind(title, description, durationMinutes)

        val tmdbKey = PrefsManager.getTmdbApiKey(this@HomeActivity)
        if (tmdbKey != null) {
            val preferType = when (kind) {
                ShowKind.MOVIE  -> "movie"
                ShowKind.SERIES -> "tv"
                ShowKind.UNKNOWN -> null
            }
            val candidates = com.orbital.iptv.data.tmdb.TmdbRepository.search(tmdbKey, query, preferType)
            val tmdb = candidates.firstOrNull { isGoodTitleMatch(query, it.title) }
            if (tmdb != null) {
                val header = listOfNotNull(
                    tmdb.year,
                    tmdb.voteAverage?.let { "★ %.1f".format(it) }
                ).joinToString("  •  ")
                return@withContext ShowPosterMatch(tmdb.posterUrl, header, tmdb.overview)
            }
        }

        fun header(genre: String?, releaseDate: String?, rating: String?): String = listOfNotNull(
            genre?.takeIf { it.isNotBlank() },
            releaseDate?.takeIf { it.isNotBlank() },
            rating?.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" }
        ).joinToString("  •  ")

        suspend fun trySeries(): ShowPosterMatch? {
            val matches = ContentCache.searchSeries(this@HomeActivity, serverUrl, query)
            val series = matches.firstOrNull { isGoodTitleMatch(query, it.name) } ?: return null
            return ShowPosterMatch(series.cover, header(series.genre, series.releaseDate, series.rating), series.plot)
        }

        suspend fun tryMovie(): ShowPosterMatch? {
            val matches = ContentCache.searchMovies(this@HomeActivity, serverUrl, query)
            val movie = matches.firstOrNull { isGoodTitleMatch(query, it.name) } ?: return null
            val info = repository.getVodInfo(serverUrl, username, password, movie.streamId).getOrNull()?.info
            val poster = info?.coverBig?.takeIf { it.isNotBlank() }
                ?: info?.movieImage?.takeIf { it.isNotBlank() }
                ?: movie.streamIcon
            val plot = info?.plot?.takeIf { it.isNotBlank() } ?: info?.description
            return ShowPosterMatch(poster, header(info?.genre, info?.releaseDate, info?.rating ?: movie.rating), plot)
        }

        when (kind) {
            ShowKind.MOVIE  -> tryMovie() ?: trySeries()
            ShowKind.SERIES -> trySeries() ?: tryMovie()
            ShowKind.UNKNOWN -> trySeries() ?: tryMovie()
        }
    }

    private fun normalizeTitle(s: String): String = s.lowercase()
        .replace(Regex("\\(\\d{4}\\)"), "")                              // "(2023)"
        .replace(Regex("s\\d{1,2}\\s?e\\d{1,3}", RegexOption.IGNORE_CASE), "")  // "S01E02"
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Requires an exact match after normalising noise (years, S01E02, punctuation) — no partial
     * or prefix matching. A word-boundary prefix rule was tried first but is unsafe for TV
     * titles: "Hacks" ⊂ "Hacksaw Ridge" (no boundary — already excluded) is one failure mode, but
     * "Prisoner" ⊂ "Prisoner: Cell Block H" (a genuinely different, unrelated show) DOES land on
     * a word boundary and was a real false-positive match. Titles are short enough, and wrong
     * posters bad enough, that only an exact match is worth showing.
     */
    private fun isGoodTitleMatch(epgTitle: String, candidateName: String): Boolean {
        val a = normalizeTitle(epgTitle)
        val b = normalizeTitle(candidateName)
        return a.isNotBlank() && a == b
    }

    private fun scheduleReminder(channelName: String, title: String, startMs: Long, streamUrl: String, streamId: Int) {
        val delayMs = (startMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val data = Data.Builder()
            .putString(ReminderWorker.KEY_TITLE,      title)
            .putString(ReminderWorker.KEY_CHANNEL,    channelName)
            .putString(ReminderWorker.KEY_STREAM_URL, streamUrl)
            .putInt(ReminderWorker.KEY_STREAM_ID,     streamId)
            .build()
        WorkManager.getInstance(this).enqueue(
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .addTag("reminder_${title.hashCode()}")
                .build()
        )
        val timeStr = SimpleDateFormat("HH:mm", Locale.UK).format(Date(startMs))
        Toast.makeText(this, "REMINDER SET: $title at $timeStr", Toast.LENGTH_SHORT).show()
    }

    private fun askForDuration(onChosen: (Long) -> Unit) {
        val options   = arrayOf("30 minutes", "1 hour", "1 hour 30 min", "2 hours", "3 hours")
        val durations = longArrayOf(30, 60, 90, 120, 180)
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("RECORDING DURATION")
            .setMessage("No end time found in EPG. How long should we record?")
            .setItems(options) { _, i -> onChosen(durations[i] * 60_000L) }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun scheduleRecording(streamId: Int, channelName: String, url: String, title: String, startMs: Long, endMs: Long) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val recording = RecordingEntity(
                    channelName    = channelName,
                    channelUrl     = url,
                    streamId       = streamId,
                    epgTitle       = title,
                    scheduledStart = startMs,
                    scheduledEnd   = endMs,
                    status         = RecordingStatus.SCHEDULED
                )
                val id = RecordingDatabase.get(this@HomeActivity).dao().insert(recording).toInt()
                val delayMs = startMs - System.currentTimeMillis()
                if (delayMs <= 0L) {
                    androidx.core.content.ContextCompat.startForegroundService(
                        this@HomeActivity,
                        Intent(this@HomeActivity, RecordingService::class.java).apply {
                            putExtra(RecordingService.EXTRA_RECORDING_ID, id)
                        }
                    )
                } else {
                    WorkManager.getInstance(this@HomeActivity).enqueue(
                        OneTimeWorkRequestBuilder<RecordingWorker>()
                            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                            .setInputData(workDataOf("recording_id" to id))
                            .addTag("rec_$id")
                            .build()
                    )
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@HomeActivity, "RECORDING SCHEDULED: $title", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@HomeActivity, "SCHEDULE FAILED: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
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
        launchChannel(stream)
    }

    private fun launchChannel(stream: LiveStream) {
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
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, streamUrl)
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, stream.name)
            putExtra(PlayerActivity.EXTRA_STREAM_ID, stream.streamId)
            putExtra(PlayerActivity.EXTRA_IS_LIVE, true)
            stream.num?.let { putExtra(PlayerActivity.EXTRA_CHANNEL_NUM, it) }
        }
        startActivity(intent)
    }

    // ── PIN helpers ───────────────────────────────────────────────────────────

    private fun pinEditText(hint: String) = android.widget.EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        this.hint = hint
        gravity = android.view.Gravity.CENTER
        textSize = 22f
        setPadding(48, 32, 48, 16)
    }

    private fun promptPin(title: String, onCorrect: () -> Unit) {
        val et = pinEditText("ENTER PIN")
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                if (et.text.toString() == PinManager.getPin(this)) onCorrect()
                else Toast.makeText(this, "INCORRECT PIN", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showPinProtectedCategories() {
        promptPin("ENTER PIN TO MANAGE LOCKED CATEGORIES") { showLockedCategoryPicker() }
    }

    private fun showLockedCategoryPicker() {
        val categories = viewModel.uiState.value?.xtreamCategories ?: emptyList()
        if (categories.isEmpty()) {
            Toast.makeText(this, "NO CATEGORIES LOADED", Toast.LENGTH_SHORT).show()
            return
        }
        val lockedIds = PinManager.getLockedCategoryIds(this).toMutableSet()
        val labels  = categories.map { it.categoryName.uppercase() }.toTypedArray()
        val checked = categories.map { it.categoryId in lockedIds }.toBooleanArray()
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("PIN PROTECTED CATEGORIES")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) lockedIds.add(categories[which].categoryId)
                else           lockedIds.remove(categories[which].categoryId)
            }
            .setPositiveButton("SAVE") { _, _ ->
                PinManager.setLockedCategoryIds(this, lockedIds)
                Toast.makeText(this, "SAVED", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showChangePinDialog() {
        promptPin("ENTER CURRENT PIN") { promptNewPin() }
    }

    private fun promptNewPin() {
        val et = pinEditText("ENTER NEW PIN")
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("ENTER NEW PIN")
            .setView(et)
            .setPositiveButton("NEXT") { _, _ ->
                val pin = et.text.toString()
                if (pin.length == 4) confirmNewPin(pin)
                else Toast.makeText(this, "PIN MUST BE 4 DIGITS", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun confirmNewPin(newPin: String) {
        val et = pinEditText("CONFIRM NEW PIN")
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("CONFIRM NEW PIN")
            .setView(et)
            .setPositiveButton("SAVE") { _, _ ->
                if (et.text.toString() == newPin) {
                    PinManager.setPin(this, newPin)
                    Toast.makeText(this, "PIN CHANGED", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "PINS DO NOT MATCH", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }
}
