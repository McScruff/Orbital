package com.orbital.iptv.ui.tv

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.orbital.iptv.ui.player.PcmOnlyRenderersFactory
import com.orbital.iptv.R
import com.orbital.iptv.data.api.ApiClient
import com.orbital.iptv.data.model.LiveCategory
import com.orbital.iptv.data.model.LiveStream
import com.orbital.iptv.data.model.ServerProfile
import com.orbital.iptv.data.model.getDecodedTitle
import com.orbital.iptv.data.repository.XtreamRepository
import com.orbital.iptv.utils.CategoryPrefs
import com.orbital.iptv.utils.ContentCache
import com.orbital.iptv.utils.EpgCache
import com.orbital.iptv.utils.UpdateChecker
import com.orbital.iptv.utils.UpdateInfo
import com.orbital.iptv.databinding.ActivityTvModeBinding
import com.orbital.iptv.recording.RecordingService
import com.orbital.iptv.recording.RecordingState
import com.orbital.iptv.ui.catchup.CatchupActivity
import com.orbital.iptv.ui.favourites.FavouritesActivity
import com.orbital.iptv.ui.emby.EmbyBrowserActivity
import com.orbital.iptv.ui.emby.EmbyLoginActivity
import com.orbital.iptv.ui.games.BubbleShooterActivity
import com.orbital.iptv.ui.games.TeletextActivity
import com.orbital.iptv.ui.home.HomeActivity
import com.orbital.iptv.ui.login.LoginActivity
import com.orbital.iptv.ui.radio.RadioStations
import com.orbital.iptv.ui.plex.PlexBrowserActivity
import com.orbital.iptv.ui.plex.PlexLoginActivity
import com.orbital.iptv.utils.EmbyPrefsManager
import com.orbital.iptv.utils.PlexPrefsManager
import com.orbital.iptv.ui.player.PlayerActivity
import com.orbital.iptv.ui.series.SeriesActivity
import com.orbital.iptv.ui.sports.SportsActivity
import com.orbital.iptv.ui.vod.VodActivity
import com.orbital.iptv.utils.FavouritesManager
import com.orbital.iptv.utils.PinManager
import com.orbital.iptv.utils.PrefsManager
import com.orbital.iptv.utils.ThemeManager
import com.orbital.iptv.utils.TickerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Static holder so the channel/category lists survive intent navigation. */
object TvModeHolder {
    var serverUrl: String = ""
    var allChannels: List<LiveStream> = emptyList()
    var categories: List<LiveCategory> = emptyList()

    fun invalidateIfServerChanged(url: String) {
        if (url != serverUrl) {
            serverUrl = url
            allChannels = emptyList()
            categories = emptyList()
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvModeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL   = "tv_stream_url"
        const val EXTRA_CHANNEL_NAME = "tv_channel_name"
        const val EXTRA_STREAM_ID    = "tv_stream_id"
        const val EXTRA_CATEGORY_ID  = "tv_category_id"
        private const val FAV_CATEGORY_ID = "__favourites__"
    }

    // Three-level left-panel navigation.
    // NONE      – full-screen TV, no panel visible
    // CHANNELS  – channel list for the current category
    // CATEGORIES– category picker
    // MAIN_MENU – top-level menu (Live TV / Box Office / Radio / Interactive / Settings)
    private enum class PanelState { NONE, CHANNELS, CATEGORIES, MAIN_MENU }

    private lateinit var binding: ActivityTvModeBinding
    private var player: ExoPlayer? = null
    private var panelState = PanelState.NONE
    private var activeDialog: AlertDialog? = null

    private var currentStreamUrl   = ""
    private var currentChannelName = ""
    private var currentStreamId    = -1
    private var currentCategoryId  = ""
    private var categoryChannels: List<LiveStream> = emptyList()

    private var epgLoadingJob: Job? = null
    private var channelPanelAdapter: NowNextAdapter? = null

    private val hudHandler  = Handler(Looper.getMainLooper())
    private val hideHud     = Runnable { hideHudOverlay() }
    private val zapHandler  = Handler(Looper.getMainLooper())
    private val hideZap     = Runnable { hideZapBar() }
    private val repository  = XtreamRepository()
    private var isRecording = false

    private val tickerHttp = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private val tickerHandler = Handler(Looper.getMainLooper())
    private val newsHandler   = Handler(Looper.getMainLooper())
    private var pendingTickerText: String? = null
    private var pendingNewsText:   String? = null
    private var tickerScrollAnim: ValueAnimator? = null
    private var newsScrollAnim:   ValueAnimator? = null
    private var tickerShowingPlaceholder = false
    private var newsShowingPlaceholder   = false
    private val tickerRunnable = object : Runnable {
        override fun run() {
            fetchTickerScores()
            val hasLive = TickerManager.liveScores.any { it.state == "in" }
            tickerHandler.postDelayed(this, if (hasLive) 30_000L else 60_000L)
        }
    }
    private val newsRunnable = object : Runnable {
        override fun run() {
            fetchNewsHeadlines()
            newsHandler.postDelayed(this, 300_000L)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityTvModeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.load(this)

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        currentStreamUrl   = intent.getStringExtra(EXTRA_STREAM_URL)   ?: PrefsManager.getLastTvChannelUrl(this) ?: ""
        currentChannelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: PrefsManager.getLastTvChannelName(this) ?: ""
        currentStreamId    = intent.getIntExtra(EXTRA_STREAM_ID, -1).takeIf { it >= 0 } ?: PrefsManager.getLastTvStreamId(this)
        currentCategoryId  = intent.getStringExtra(EXTRA_CATEGORY_ID)  ?: PrefsManager.getLastTvCategoryId(this)

        initPlayer()
        setupButtons()
        setupHudButtons()
        updateScoresButton()
        if (TickerManager.tickerEnabled) startTicker()
        updateNewsButton()
        if (TickerManager.newsTickerEnabled) startNewsTicker()

        TvModeHolder.invalidateIfServerChanged(
            PrefsManager.getCredentials(this)?.serverUrl ?: ""
        )
        if (TvModeHolder.allChannels.isEmpty()) loadChannelsInBackground()
        else refreshCategoryChannels()
    }

    private fun initPlayer() {
        val httpFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(
                PcmOnlyRenderersFactory(this)
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build().also { exo ->
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setPreferredAudioMimeTypes(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AC3)
                .build()
            exo.repeatMode = Player.REPEAT_MODE_OFF
            exo.setVideoSurfaceView(binding.surfaceView)
            if (currentStreamUrl.isNotBlank()) {
                exo.setMediaItem(MediaItem.fromUri(currentStreamUrl))
                exo.prepare()
                exo.play()
                updateChannelInfo(currentChannelName)
                loadEpgForCurrentChannel()
            }
        }
    }

    private fun playChannel(url: String, name: String) {
        val exo = player ?: return
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.play()
        showInfoBar(name)
        loadEpgForCurrentChannel()
    }

    private fun setupButtons() {
        val p = ThemeManager.palette()
        binding.surfaceView.setOnClickListener { showHudOverlay() }

        fun menuItem(view: android.widget.TextView, action: () -> Unit) {
            view.setOnClickListener { action() }
            view.setOnFocusChangeListener { _, hasFocus ->
                val base = view.tag as? Int ?: 0xFF0A1628.toInt()
                view.setBackgroundColor(if (hasFocus) p.focus else base)
            }
        }
        binding.menuItemLiveTv.tag      = 0xFF0A1628.toInt()
        binding.menuItemBoxOffice.tag   = 0xFF0D1E38.toInt()
        binding.menuItemRadio.tag       = 0xFF0A1628.toInt()
        binding.menuItemInteractive.tag = 0xFF0D1E38.toInt()
        binding.menuItemSettings.tag    = 0xFF0A1628.toInt()

        menuItem(binding.menuItemLiveTv)      { hidePanel() }
        menuItem(binding.menuItemBoxOffice)   { showBoxOfficeMenu() }
        menuItem(binding.menuItemRadio)       { showRadioMenu() }
        menuItem(binding.menuItemInteractive) { showInteractiveMenu() }
        menuItem(binding.menuItemSettings)    { showTvSettingsMenu() }
    }

    private fun setupHudButtons() {
        val p = ThemeManager.palette()

        fun timerReset(v: View) {
            v.setOnFocusChangeListener { _, h ->
                if (h) { hudHandler.removeCallbacks(hideHud); hudHandler.postDelayed(hideHud, 5000L) }
            }
        }

        binding.btnHudMenu.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java))
        }
        timerReset(binding.btnHudMenu)

        binding.btnHudAudio.setOnClickListener { showAudioPicker() }
        timerReset(binding.btnHudAudio)

        updateRecordButton()
        binding.btnHudRecord.setOnFocusChangeListener { _, hasFocus ->
            val baseColor = if (isRecording) 0xFFCC0000.toInt() else 0xFF8B0000.toInt()
            binding.btnHudRecord.setBackgroundColor(if (hasFocus) p.focus else baseColor)
            if (hasFocus) { hudHandler.removeCallbacks(hideHud); hudHandler.postDelayed(hideHud, 5000L) }
        }
        binding.btnHudRecord.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        binding.btnHudScores.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.btnHudScores.setBackgroundColor(p.focus)
                hudHandler.removeCallbacks(hideHud); hudHandler.postDelayed(hideHud, 5000L)
            } else {
                binding.btnHudScores.setBackgroundResource(
                    if (TickerManager.tickerEnabled) R.drawable.bg_btn_scores_on else R.drawable.bg_btn_hud
                )
            }
        }
        binding.btnHudScores.setOnClickListener { toggleTicker() }

        binding.btnHudNews.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.btnHudNews.setBackgroundColor(p.focus)
                hudHandler.removeCallbacks(hideHud); hudHandler.postDelayed(hideHud, 5000L)
            } else {
                binding.btnHudNews.setBackgroundResource(
                    if (TickerManager.newsTickerEnabled) R.drawable.bg_btn_scores_on else R.drawable.bg_btn_hud
                )
            }
        }
        binding.btnHudNews.setOnClickListener { toggleNewsTicker() }
    }

    private fun showHudOverlay() {
        zapHandler.removeCallbacks(hideZap)
        updateClock()
        binding.hudTop.visibility    = View.VISIBLE
        binding.hudBottom.visibility = View.VISIBLE
        hudHandler.removeCallbacks(hideHud)
        hudHandler.postDelayed(hideHud, 5000L)
        binding.btnHudMenu.requestFocus()
    }

    private fun hideHudOverlay() {
        binding.hudTop.visibility    = View.GONE
        binding.hudBottom.visibility = View.GONE
        if (panelState == PanelState.NONE) binding.surfaceView.requestFocus()
    }

    private fun updateChannelInfo(name: String) {
        binding.tvChannelName.text    = name.uppercase()
        binding.tvHudChannelInfo.text = name.uppercase()
    }

    private fun showInfoBar(name: String) {
        updateChannelInfo(name)
        if (panelState == PanelState.NONE) showHudOverlay()
    }

    private fun showZapBar(name: String) {
        updateChannelInfo(name)
        updateClock()
        // Always force top HUD hidden and cancel its timer — only bottom info bar shows during zap
        hudHandler.removeCallbacks(hideHud)
        binding.hudTop.visibility    = View.GONE
        binding.hudBottom.visibility = View.VISIBLE
        zapHandler.removeCallbacks(hideZap)
        zapHandler.postDelayed(hideZap, 3000L)
        loadEpgForCurrentChannel()
    }

    private fun hideZapBar() {
        zapHandler.removeCallbacks(hideZap)
        // Only collapse the bottom bar if the full HUD (top) is not currently shown
        if (binding.hudTop.visibility != View.VISIBLE) {
            binding.hudBottom.visibility = View.GONE
        }
    }

    private fun loadEpgForCurrentChannel() {
        if (currentStreamId < 0) return
        val creds = PrefsManager.getCredentials(this) ?: return
        val streamIdSnapshot = currentStreamId
        binding.tvEpgNow.text = ""
        binding.tvEpgNextTitle.text = ""
        binding.tvEpgNextTime.text = ""
        binding.liveNextRow.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val cached = EpgCache.get(this@TvModeActivity, streamIdSnapshot, minCount = 2)
                val listings = if (cached != null) {
                    cached
                } else {
                    val result = repository.getFullChannelEpg(
                        creds.serverUrl, creds.username, creds.password, streamIdSnapshot
                    )
                    result.getOrNull()?.listings?.also { l ->
                        EpgCache.put(this@TvModeActivity, streamIdSnapshot, l)
                    } ?: emptyList()
                }
                if (currentStreamId != streamIdSnapshot) return@launch
                val nowSec = System.currentTimeMillis() / 1000
                val sorted = listings.sortedBy { it.startTimestamp?.toLongOrNull() ?: 0L }
                val currentIdx = sorted.indexOfFirst { l ->
                    val start = l.startTimestamp?.toLongOrNull() ?: return@indexOfFirst false
                    val end   = l.stopTimestamp?.toLongOrNull()  ?: return@indexOfFirst false
                    nowSec in start..end
                }
                val current = if (currentIdx >= 0) sorted[currentIdx] else sorted.firstOrNull()
                val next    = if (currentIdx >= 0 && currentIdx + 1 < sorted.size) sorted[currentIdx + 1] else null

                current?.getDecodedTitle().takeIf { !it.isNullOrBlank() }?.let {
                    binding.tvEpgNow.text = it
                }
                next?.let { n ->
                    n.getDecodedTitle().takeIf { it.isNotBlank() }?.let { title ->
                        binding.tvEpgNextTitle.text = title
                        val startSec = n.startTimestamp?.toLongOrNull()
                        if (startSec != null) {
                            val cal = java.util.Calendar.getInstance().apply { timeInMillis = startSec * 1000 }
                            val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
                            val m = cal.get(java.util.Calendar.MINUTE)
                            val ampm = if (h < 12) "am" else "pm"
                            val h12 = if (h % 12 == 0) 12 else h % 12
                            binding.tvEpgNextTime.text = "%d:%02d%s".format(h12, m, ampm)
                        }
                        binding.liveNextRow.visibility = View.VISIBLE
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun updateClock() {
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val ampm = if (h < 12) "am" else "pm"
        val h12 = if (h % 12 == 0) 12 else h % 12
        val date = SimpleDateFormat("EEE dd MMM", Locale.UK).format(cal.time)
        binding.tvClock.text = "%d:%02d%s  %s".format(h12, m, ampm, date.uppercase())
    }

    private fun updateRecordButton() {
        isRecording = RecordingState.activeRecordNowUrl == currentStreamUrl
        binding.btnHudRecord.text = if (isRecording) "■ STOP REC" else "● REC"
        binding.btnHudRecord.setBackgroundColor(if (isRecording) 0xFFCC0000.toInt() else 0xFF8B0000.toInt())
    }

    private fun startRecording() {
        val epgTitle = binding.tvEpgNow.text.toString().ifBlank { currentChannelName }
        activeDialog = AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("● START RECORDING")
            .setMessage("Channel: $currentChannelName\nShow: $epgTitle\n\n⚠ Uses 2 connections.")
            .setPositiveButton("RECORD") { _, _ ->
                androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java).apply {
                    putExtra(RecordingService.EXTRA_CHANNEL_NAME,  currentChannelName)
                    putExtra(RecordingService.EXTRA_CHANNEL_URL,   currentStreamUrl)
                    putExtra(RecordingService.EXTRA_STREAM_ID,     currentStreamId)
                    putExtra(RecordingService.EXTRA_EPG_TITLE,     epgTitle)
                    putExtra(RecordingService.EXTRA_SCHEDULED_END, 0L)
                })
                isRecording = true
                updateRecordButton()
                showHudOverlay()
            }
            .setNegativeButton("CANCEL") { _, _ -> showHudOverlay() }
            .setOnDismissListener { activeDialog = null }
            .show()
    }

    private fun stopRecording() {
        startService(Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        })
        RecordingState.activeRecordNowUrl = null
        isRecording = false
        updateRecordButton()
        showHudOverlay()
    }

    private fun showAudioPicker() {
        val exo = player ?: run { showHudOverlay(); return }
        val audioGroups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isEmpty()) {
            Toast.makeText(this, "NO AUDIO TRACKS FOUND", Toast.LENGTH_SHORT).show()
            showHudOverlay()
            return
        }
        data class Entry(val groupIdx: Int, val trackIdx: Int, val label: String, val selected: Boolean)
        val entries = mutableListOf<Entry>()
        audioGroups.forEachIndexed { gi, group ->
            for (ti in 0 until group.length) {
                val fmt  = group.getTrackFormat(ti)
                val lang = fmt.language?.uppercase() ?: ""
                val lbl  = fmt.label?.uppercase() ?: ""
                val name = listOfNotNull(lang.takeIf { it.isNotBlank() }, lbl.takeIf { it.isNotBlank() })
                    .joinToString(" ").ifBlank { "TRACK ${gi + 1}" }
                entries.add(Entry(gi, ti, name, group.isSelected && group.isTrackSelected(ti)))
            }
        }
        val labels = entries.map { "${if (it.selected) "●" else "○"}  ${it.label}" }
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("AUDIO LANGUAGE")
            .setItems(labels.toTypedArray()) { _, which ->
                val e = entries[which]
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .addOverride(TrackSelectionOverride(audioGroups[e.groupIdx].mediaTrackGroup, e.trackIdx))
                    .build()
            }
            .setOnDismissListener { activeDialog = null; showHudOverlay() }
            .show().also { activeDialog = it }
    }

    // ── Sports scores ticker ──────────────────────────────────────────────────

    private fun updateScoresButton() {
        val on = TickerManager.tickerEnabled
        binding.btnHudScores.text = if (on) "SCORES ON" else "SCORES"
        binding.btnHudScores.setBackgroundResource(
            if (on) R.drawable.bg_btn_scores_on else R.drawable.bg_btn_hud
        )
    }

    private fun toggleTicker() {
        TickerManager.tickerEnabled = !TickerManager.tickerEnabled
        updateScoresButton()
        if (TickerManager.tickerEnabled) startTicker() else stopTicker()
        showHudOverlay()
    }

    private fun startTicker() {
        binding.tickerRow.visibility = View.VISIBLE
        val hasSelected = TickerManager.getSelected(this).isNotEmpty()
        if (TickerManager.liveScores.isEmpty() && hasSelected) {
            tickerShowingPlaceholder = true
            binding.tvTicker.text = "  LOADING SCORES...  "
        } else {
            tickerShowingPlaceholder = false
            binding.tvTicker.text = TickerManager.buildTickerText()
        }
        tickerHandler.removeCallbacks(tickerRunnable)
        tickerHandler.post(tickerRunnable)
        binding.tvTicker.post { loopTickerScroll() }
    }

    private fun stopTicker() {
        tickerHandler.removeCallbacks(tickerRunnable)
        tickerScrollAnim?.cancel(); tickerScrollAnim = null
        pendingTickerText = null
        binding.tickerRow.visibility = View.GONE
    }

    private fun loopTickerScroll() {
        val tv = binding.tvTicker
        if (binding.tickerRow.visibility != View.VISIBLE || !TickerManager.tickerEnabled) return
        pendingTickerText?.let { tv.text = it; pendingTickerText = null }
        val containerWidth = (tv.parent as View).width.toFloat()
        tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val textWidth = tv.measuredWidth.toFloat()
        if (containerWidth <= 0f || textWidth <= 0f) { tv.post { loopTickerScroll() }; return }
        val pxPerSec = 60f * resources.displayMetrics.density
        val duration = ((containerWidth + textWidth) / pxPerSec * 1000f).toLong()
        tv.translationX = containerWidth
        tickerScrollAnim = ValueAnimator.ofFloat(containerWidth, -textWidth).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { tv.translationX = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { if (!cancelled) loopTickerScroll() }
            })
            start()
        }
    }

    private fun fetchTickerScores() {
        val selected = TickerManager.getSelected(this)
        if (selected.isEmpty()) { TickerManager.liveScores = emptyList(); updateTickerText(); return }
        val byLeague = selected.groupBy { it.leagueId }
        val selectedIds = selected.map { it.id }.toSet()
        lifecycleScope.launch {
            try {
                val scores = mutableListOf<TickerManager.LiveScore>()
                withContext(Dispatchers.IO) {
                    byLeague.keys.forEach { leagueId ->
                        val url = "https://site.api.espn.com/apis/site/v2/sports/soccer/$leagueId/scoreboard"
                        val json = tickerHttp.newCall(
                            Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                        ).execute().use { it.body?.string() ?: "" }
                        scores.addAll(parseTickerScores(json, selectedIds))
                    }
                }
                TickerManager.liveScores = scores
                updateTickerText()
            } catch (_: Exception) {}
        }
    }

    private fun parseTickerScores(json: String, ids: Set<String>): List<TickerManager.LiveScore> {
        val out = mutableListOf<TickerManager.LiveScore>()
        try {
            val events = JSONObject(json).optJSONArray("events") ?: return out
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                val id = event.optString("id"); if (id !in ids) continue
                val comp = event.optJSONArray("competitions")?.getJSONObject(0) ?: continue
                val competitors = comp.optJSONArray("competitors") ?: continue
                val statusType = comp.optJSONObject("status")?.optJSONObject("type") ?: continue
                val state      = statusType.optString("state", "pre")
                val detail     = statusType.optString("shortDetail", "")
                val statusDesc = statusType.optString("description", "")
                val note = run {
                    val notes = comp.optJSONArray("notes")
                    if (notes != null) {
                        (0 until notes.length())
                            .mapNotNull { notes.optJSONObject(it)?.optString("headline", "")?.takeIf { h -> h.isNotBlank() } }
                            .firstOrNull()
                    } else null
                } ?: when {
                    statusDesc.contains("penalt", ignoreCase = true) -> "AET (Pens)"
                    statusDesc.contains("aet", ignoreCase = true) || statusDesc.contains("extra time", ignoreCase = true) -> "AET"
                    else -> ""
                }
                var home: JSONObject? = null; var away: JSONObject? = null
                for (j in 0 until competitors.length()) {
                    val c = competitors.getJSONObject(j)
                    if (c.optString("homeAway") == "home") home = c else away = c
                }
                if (home == null || away == null) continue
                val ht = home.optJSONObject("team") ?: continue
                val at = away.optJSONObject("team") ?: continue
                out.add(TickerManager.LiveScore(
                    gameId    = id,
                    homeTeam  = ht.optString("shortDisplayName").ifEmpty { ht.optString("displayName") },
                    awayTeam  = at.optString("shortDisplayName").ifEmpty { at.optString("displayName") },
                    homeScore = home.optString("score", ""),
                    awayScore = away.optString("score", ""),
                    state = state, detail = detail, note = note
                ))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun updateTickerText() {
        if (!TickerManager.tickerEnabled) return
        val newText = TickerManager.buildTickerText()
        if (tickerShowingPlaceholder) {
            tickerShowingPlaceholder = false
            tickerScrollAnim?.cancel(); tickerScrollAnim = null
            binding.tvTicker.text = newText
            loopTickerScroll()
        } else { pendingTickerText = newText }
    }

    // ── News headlines ticker ─────────────────────────────────────────────────

    private fun updateNewsButton() {
        val on = TickerManager.newsTickerEnabled
        binding.btnHudNews.text = if (on) "NEWS ON" else "NEWS"
        binding.btnHudNews.setBackgroundResource(
            if (on) R.drawable.bg_btn_scores_on else R.drawable.bg_btn_hud
        )
    }

    private fun toggleNewsTicker() {
        TickerManager.newsTickerEnabled = !TickerManager.newsTickerEnabled
        updateNewsButton()
        if (TickerManager.newsTickerEnabled) startNewsTicker() else stopNewsTicker()
        showHudOverlay()
    }

    private fun startNewsTicker() {
        binding.newsTickerRow.visibility = View.VISIBLE
        newsShowingPlaceholder = TickerManager.sportHeadlines.isEmpty()
        binding.tvNewsTicker.text = TickerManager.buildNewsText()
        newsHandler.removeCallbacks(newsRunnable)
        newsHandler.post(newsRunnable)
        binding.tvNewsTicker.post { loopNewsScroll() }
    }

    private fun stopNewsTicker() {
        newsHandler.removeCallbacks(newsRunnable)
        newsScrollAnim?.cancel(); newsScrollAnim = null
        pendingNewsText = null
        binding.newsTickerRow.visibility = View.GONE
    }

    private fun loopNewsScroll() {
        val tv = binding.tvNewsTicker
        if (binding.newsTickerRow.visibility != View.VISIBLE || !TickerManager.newsTickerEnabled) return
        pendingNewsText?.let { tv.text = it; pendingNewsText = null }
        val containerWidth = (tv.parent as View).width.toFloat()
        tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val textWidth = tv.measuredWidth.toFloat()
        if (containerWidth <= 0f || textWidth <= 0f) { tv.post { loopNewsScroll() }; return }
        val pxPerSec = 60f * resources.displayMetrics.density
        val duration = ((containerWidth + textWidth) / pxPerSec * 1000f).toLong()
        tv.translationX = containerWidth
        newsScrollAnim = ValueAnimator.ofFloat(containerWidth, -textWidth).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { tv.translationX = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { if (!cancelled) loopNewsScroll() }
            })
            start()
        }
    }

    private fun fetchNewsHeadlines() {
        val selectedSports = TickerManager.getSelectedSports(this)
        if (selectedSports.isEmpty()) return
        lifecycleScope.launch {
            val results = LinkedHashMap<String, List<String>>()
            selectedSports.forEach { feed ->
                try {
                    val xml = withContext(Dispatchers.IO) {
                        tickerHttp.newCall(
                            Request.Builder().url(feed.rssUrl).header("User-Agent", "Mozilla/5.0").build()
                        ).execute().use { it.body?.string() ?: "" }
                    }
                    val titles = parseRssTitles(xml)
                    if (titles.isNotEmpty()) results[feed.id] = titles
                } catch (_: Exception) {}
            }
            if (results.isNotEmpty()) { TickerManager.sportHeadlines = results; updateNewsTickerText() }
        }
    }

    private fun parseRssTitles(xml: String): List<String> {
        val titles = mutableListOf<String>()
        try {
            val parser = android.util.Xml.newPullParser()
            parser.setInput(xml.reader())
            var inItem = false; var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && titles.size < 12) {
                when {
                    event == XmlPullParser.START_TAG && parser.name == "item" -> inItem = true
                    event == XmlPullParser.END_TAG   && parser.name == "item" -> inItem = false
                    inItem && event == XmlPullParser.START_TAG && parser.name == "title" ->
                        titles.add(parser.nextText().trim())
                }
                event = parser.next()
            }
        } catch (_: Exception) {}
        return titles
    }

    private fun updateNewsTickerText() {
        if (!TickerManager.newsTickerEnabled) return
        val newText = TickerManager.buildNewsText()
        if (newsShowingPlaceholder) {
            newsShowingPlaceholder = false
            newsScrollAnim?.cancel(); newsScrollAnim = null
            binding.tvNewsTicker.text = newText
            loopNewsScroll()
        } else { pendingNewsText = newText }
    }

    // ── Left-panel navigation state machine ───────────────────────────────────

    /** Open the CHANNELS panel (channel list for the current category). */
    private fun showChannelPanel() {
        hideHudOverlay()
        zapHandler.removeCallbacks(hideZap)
        panelState = PanelState.CHANNELS
        binding.leftPanel.visibility         = View.VISIBLE
        binding.rvPanelChannels.visibility   = View.VISIBLE
        binding.rvPanelCategories.visibility = View.GONE
        binding.panelMainMenu.visibility     = View.GONE

        val catName = when {
            currentCategoryId == FAV_CATEGORY_ID -> "★  FAVOURITES"
            currentCategoryId.isBlank() -> "ALL CHANNELS"
            else -> TvModeHolder.categories
                .find { it.categoryId == currentCategoryId }
                ?.categoryName?.uppercase() ?: "CHANNELS"
        }
        binding.panelHeader.text = catName

        refreshCategoryChannels()
        loadChannelPanel(categoryChannels)
    }

    /** Open the CATEGORIES panel (category picker). */
    private fun showCategoryPanel() {
        panelState = PanelState.CATEGORIES
        binding.leftPanel.visibility         = View.VISIBLE
        binding.rvPanelChannels.visibility   = View.GONE
        binding.rvPanelCategories.visibility = View.VISIBLE
        binding.panelMainMenu.visibility     = View.GONE
        binding.panelHeader.text             = "CATEGORIES"
        loadCategoryPanel()
    }

    /** Open the MAIN_MENU panel (Live TV / Box Office / Radio / Interactive / Settings). */
    private fun showMainMenuPanel() {
        panelState = PanelState.MAIN_MENU
        binding.leftPanel.visibility         = View.VISIBLE
        binding.rvPanelChannels.visibility   = View.GONE
        binding.rvPanelCategories.visibility = View.GONE
        binding.panelMainMenu.visibility     = View.VISIBLE
        binding.panelHeader.text             = "ORBITAL"
        binding.panelMainMenu.post { binding.menuItemLiveTv.requestFocus() }
    }

    /** Close all panels and return to full-screen TV. */
    private fun hidePanel() {
        epgLoadingJob?.cancel()
        panelState = PanelState.NONE
        binding.leftPanel.visibility = View.GONE
        binding.surfaceView.requestFocus()
    }

    // ── Channel panel ─────────────────────────────────────────────────────────

    private fun loadChannelPanel(channels: List<LiveStream>) {
        val items = channels.map { ch -> NowNextItem(ch.streamId, ch.name) }.toMutableList()

        channelPanelAdapter = NowNextAdapter(items, currentStreamId) { streamId ->
            val ch    = channels.find { it.streamId == streamId } ?: return@NowNextAdapter
            val creds = PrefsManager.getCredentials(this) ?: return@NowNextAdapter
            val url   = repository.buildStreamUrl(creds.serverUrl, creds.username, creds.password, ch.streamId)
            currentStreamId    = ch.streamId
            currentStreamUrl   = url
            currentChannelName = ch.name
            PrefsManager.setLastTvChannel(this, url, ch.name, ch.streamId, currentCategoryId)
            playChannel(url, ch.name)
            // Panel stays open — update the highlight
            channelPanelAdapter?.setCurrentId(currentStreamId)
        }

        binding.rvPanelChannels.layoutManager = TvLayoutManager(this)
        binding.rvPanelChannels.itemAnimator  = null
        binding.rvPanelChannels.adapter       = channelPanelAdapter

        val idx = channels.indexOfFirst { it.streamId == currentStreamId }.coerceAtLeast(0)
        (binding.rvPanelChannels.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(idx, 0)

        // Focus current channel row after layout
        binding.rvPanelChannels.post {
            (binding.rvPanelChannels.findViewHolderForAdapterPosition(idx)?.itemView
                ?: binding.rvPanelChannels.getChildAt(0))?.requestFocus()
        }

        // Load EPG for all channels in the background
        epgLoadingJob?.cancel()
        val creds = PrefsManager.getCredentials(this) ?: return
        epgLoadingJob = lifecycleScope.launch {
            channels.forEach { stream ->
                launch {
                    val nowSec = System.currentTimeMillis() / 1000
                    val cached = EpgCache.get(this@TvModeActivity, stream.streamId, minCount = 2)
                    val listings = if (cached != null) cached else {
                        repository.getFullChannelEpg(
                            creds.serverUrl, creds.username, creds.password, stream.streamId
                        ).getOrNull()?.listings?.also { l ->
                            EpgCache.put(this@TvModeActivity, stream.streamId, l)
                        } ?: emptyList()
                    }
                    val sorted = listings.sortedBy { it.startTimestamp?.toLongOrNull() ?: 0L }
                    val curIdx = sorted.indexOfFirst { l ->
                        val s = l.startTimestamp?.toLongOrNull() ?: return@indexOfFirst false
                        val e = l.stopTimestamp?.toLongOrNull()  ?: return@indexOfFirst false
                        nowSec in s..e
                    }
                    val cur  = if (curIdx >= 0) sorted[curIdx] else null
                    val next = if (curIdx >= 0 && curIdx + 1 < sorted.size) sorted[curIdx + 1] else null
                    val nowTitle  = cur?.getDecodedTitle().orEmpty()
                    val nextTitle = next?.getDecodedTitle().orEmpty()
                    val nextTime  = next?.startTimestamp?.toLongOrNull()?.let { sec ->
                        val cal = Calendar.getInstance().apply { timeInMillis = sec * 1000 }
                        val h   = cal.get(Calendar.HOUR_OF_DAY)
                        val m   = cal.get(Calendar.MINUTE)
                        "%d:%02d%s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "am" else "pm")
                    } ?: ""
                    channelPanelAdapter?.updateEpg(stream.streamId, nowTitle, nextTitle, nextTime)
                }
            }
        }
    }

    // ── Category panel ────────────────────────────────────────────────────────

    private fun loadCategoryPanel() {
        val favCat  = LiveCategory(FAV_CATEGORY_ID, "★  FAVOURITES", 0)
        val allCats = listOf(favCat) + visibleCategories()
        val countMap = TvModeHolder.allChannels.groupingBy { it.categoryId }.eachCount()

        val adapter = CategoryPanelAdapter(allCats, currentCategoryId, countMap.mapKeys { it.key ?: "" }) { cat ->
            fun openCategory() {
                currentCategoryId = cat.categoryId
                refreshCategoryChannels()
                showChannelPanel()
            }
            if (PinManager.isCategoryLocked(this, cat.categoryId)) {
                tvPromptPin("ENTER PIN TO VIEW CATEGORY") { openCategory() }
            } else {
                openCategory()
            }
        }
        binding.rvPanelCategories.layoutManager = TvLayoutManager(this)
        binding.rvPanelCategories.itemAnimator  = null
        binding.rvPanelCategories.adapter       = adapter

        val idx = allCats.indexOfFirst { it.categoryId == currentCategoryId }.coerceAtLeast(0)
        (binding.rvPanelCategories.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(idx, 0)
        binding.rvPanelCategories.post {
            (binding.rvPanelCategories.findViewHolderForAdapterPosition(idx)?.itemView
                ?: binding.rvPanelCategories.getChildAt(0))?.requestFocus()
        }
    }

    private fun visibleCategories(): List<LiveCategory> {
        val hidden = CategoryPrefs.getHiddenServerCatIds(this)
        return TvModeHolder.categories.filter { it.categoryId !in hidden }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showExitDialog() {
        showTvDialog(AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("EXIT ORBITAL?")
            .setPositiveButton("EXIT") { _, _ -> finishAffinity() }
            .setNegativeButton("CANCEL", null))
    }

    private fun showTvDialog(builder: AlertDialog.Builder): AlertDialog {
        val dlg = builder.setOnDismissListener { activeDialog = null }.show()
        dlg.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                dlg.dismiss(); true
            } else false
        }
        activeDialog = dlg
        return dlg
    }

    // ── PIN helpers ───────────────────────────────────────────────────────────

    private fun tvPinEditText(hint: String) = android.widget.EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        this.hint = hint
        gravity = Gravity.CENTER
        textSize = 22f
        setPadding(48, 32, 48, 16)
    }

    private fun tvPromptPin(title: String, onCorrect: () -> Unit) {
        val et = tvPinEditText("ENTER PIN")
        showTvDialog(AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                if (et.text.toString() == PinManager.getPin(this)) onCorrect()
                else Toast.makeText(this, "INCORRECT PIN", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null))
    }

    private fun showPinProtectedCategories() {
        tvPromptPin("ENTER PIN TO MANAGE LOCKED CATEGORIES") { showLockedCategoryPicker() }
    }

    private fun showLockedCategoryPicker() {
        val categories = TvModeHolder.categories
        if (categories.isEmpty()) {
            Toast.makeText(this, "NO CATEGORIES LOADED", Toast.LENGTH_SHORT).show()
            return
        }
        val lockedIds = PinManager.getLockedCategoryIds(this).toMutableSet()
        val labels  = categories.map { it.categoryName.uppercase() }.toTypedArray()
        val checked = categories.map { it.categoryId in lockedIds }.toBooleanArray()
        showTvDialog(AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("PIN PROTECTED CATEGORIES")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) lockedIds.add(categories[which].categoryId)
                else           lockedIds.remove(categories[which].categoryId)
            }
            .setPositiveButton("SAVE") { _, _ ->
                PinManager.setLockedCategoryIds(this, lockedIds)
                Toast.makeText(this, "SAVED", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null))
    }

    private fun showChangePinDialog() {
        tvPromptPin("ENTER CURRENT PIN") { tvPromptNewPin() }
    }

    private fun tvPromptNewPin() {
        val et = tvPinEditText("ENTER NEW PIN")
        showTvDialog(AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("ENTER NEW PIN")
            .setView(et)
            .setPositiveButton("NEXT") { _, _ ->
                val pin = et.text.toString()
                if (pin.length == 4) tvConfirmNewPin(pin)
                else Toast.makeText(this, "PIN MUST BE 4 DIGITS", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null))
    }

    private fun tvConfirmNewPin(newPin: String) {
        val et = tvPinEditText("CONFIRM NEW PIN")
        showTvDialog(AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
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
            .setNegativeButton("CANCEL", null))
    }

    private fun showBoxOfficeMenu() {
        data class Item(val label: String, val action: () -> Unit)
        val items = listOf(
            Item("MOVIES")                         { startActivity(Intent(this, VodActivity::class.java)) },
            Item("SERIES")                         { startActivity(Intent(this, SeriesActivity::class.java)) },
            Item("CATCHUP")                        { startActivity(Intent(this, CatchupActivity::class.java)) },
            Item("CONTINUE WATCHING / FAVOURITES") { startActivity(Intent(this, FavouritesActivity::class.java)) }
        )
        showTvDialog(AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("BOX OFFICE")
            .setItems(items.map { it.label }.toTypedArray()) { _, which -> items[which].action() })
    }

    private fun showRadioMenu() {
        val stations = RadioStations.load(this)
        if (stations.isEmpty()) {
            Toast.makeText(this, "NO RADIO STATIONS FOUND", Toast.LENGTH_SHORT).show()
            return
        }
        showTvDialog(AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("RADIO")
            .setItems(stations.map { it.name.uppercase() }.toTypedArray()) { _, which ->
                val station = stations[which]
                currentStreamId    = -1
                currentStreamUrl   = station.url
                currentChannelName = station.name
                binding.tvEpgNow.text = ""
                binding.tvEpgNextTitle.text = ""
                binding.tvEpgNextTime.text = ""
                binding.liveNextRow.visibility = View.GONE
                playChannel(station.url, station.name)
                hidePanel()
            })
    }

    private fun showInteractiveMenu() {
        data class Item(val label: String, val action: () -> Unit)
        val items = mutableListOf(
            Item("SPORTS")         { startActivity(Intent(this, SportsActivity::class.java)) },
            Item("TELETEXT")       { startActivity(Intent(this, TeletextActivity::class.java)) },
            Item("BUBBLE SHOOTER") { startActivity(Intent(this, BubbleShooterActivity::class.java)) }
        )
        val tickerOn = TickerManager.newsTickerEnabled
        items += Item("NEWS TICKER: ${if (tickerOn) "ON" else "OFF"}") {
            TickerManager.newsTickerEnabled = !tickerOn
            TickerManager.sportHeadlines.clear()
            if (TickerManager.newsTickerEnabled) startNewsTicker() else stopNewsTicker()
        }
        items += Item("EMBY") {
            val dest = if (EmbyPrefsManager.getSession(this) != null) EmbyBrowserActivity::class.java else EmbyLoginActivity::class.java
            startActivity(Intent(this, dest))
        }
        items += Item("PLEX") {
            val dest = if (PlexPrefsManager.getSession(this) != null) PlexBrowserActivity::class.java else PlexLoginActivity::class.java
            startActivity(Intent(this, dest))
        }
        showTvDialog(AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("INTERACTIVE")
            .setItems(items.map { it.label }.toTypedArray()) { _, which -> items[which].action() })
    }

    private fun showTvSettingsMenu() {
        val serverCount   = PrefsManager.getProfiles(this).size
        val activeProfile = PrefsManager.getActiveProfile(this)
        val serverName    = activeProfile?.name?.uppercase() ?: "SERVER"

        data class Item(val label: String, val action: () -> Unit)
        val items = mutableListOf<Item>()
        items += Item("SERVERS ($serverCount SAVED)")  { showServerManager() }
        items += Item("↺  REFRESH $serverName")        { refreshServer() }
        items += Item("MANAGE VISIBLE CATEGORIES")     { showManageCategoriesDialog() }
        items += Item("THEME: ${ThemeManager.current.label}") { showThemePicker() }
        items += Item("TV MODE (TESTING): ${if (PrefsManager.isTvModeEnabled(this)) "ON" else "OFF"}") { toggleTvMode() }
        items += Item("PICTURE IN PICTURE: ${if (PrefsManager.isPipEnabled(this)) "ON" else "OFF"}") {
            PrefsManager.setPipEnabled(this, !PrefsManager.isPipEnabled(this))
        }
        items += Item(if (PrefsManager.getOpenSubsApiKey(this) != null) "OPENSUBTITLES: KEY SET" else "OPENSUBTITLES: NO KEY SET") {
            showOpenSubsKeyDialog()
        }
        items += Item("LIVE STREAM FORMAT: ${PrefsManager.getLiveFormat(this).uppercase()}") { toggleLiveFormat() }
        items += Item("PIN PROTECTED CATEGORIES")       { showPinProtectedCategories() }
        items += Item("CHANGE PIN")                    { showChangePinDialog() }
        items += Item("CHECK FOR UPDATES")             { checkForUpdatesManually() }
        items += Item("CLEAR ALL SAVED DATA")          { confirmClearAllData() }
        val versionName = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        items += Item("ORBITAL  v$versionName")        { }

        showTvDialog(AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("SETTINGS")
            .setItems(items.map { it.label }.toTypedArray()) { _, which -> items[which].action() })
    }

    private fun showServerManager() {
        val profiles = PrefsManager.getProfiles(this)
        val activeId = PrefsManager.getActiveProfileId(this)

        val labels = profiles.map { p ->
            "${if (p.id == activeId) "●" else "○"}  ${p.name.uppercase()}  —  ${p.serverUrl}"
        }.toMutableList()
        labels.add("＋  ADD NEW SERVER")
        labels.add("✕  REMOVE A SERVER")

        showTvDialog(AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("SERVERS")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    profiles.size -> {
                        startActivity(Intent(this, LoginActivity::class.java).putExtra("skip_auto", true))
                        finish()
                    }
                    profiles.size + 1 -> showDeleteServerPicker(profiles, activeId)
                    else -> {
                        val selected = profiles[which]
                        if (selected.id != activeId) {
                            PrefsManager.setActiveProfile(this, selected.id)
                            PrefsManager.setUseOriginalCategories(this, false)
                            switchServerAndReload()
                        }
                    }
                }
            })
    }

    private fun showDeleteServerPicker(profiles: List<ServerProfile>, activeId: String?) {
        if (profiles.isEmpty()) return
        val labels = profiles.map { "✕  ${it.name.uppercase()}" }.toTypedArray()
        showTvDialog(AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("REMOVE SERVER")
            .setItems(labels) { _, which ->
                val toDelete = profiles[which]
                showTvDialog(AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
                    .setTitle("REMOVE ${toDelete.name.uppercase()}?")
                    .setMessage("THIS CANNOT BE UNDONE.")
                    .setPositiveButton("REMOVE") { _, _ ->
                        PrefsManager.deleteProfile(this, toDelete.id)
                        val remaining = PrefsManager.getProfiles(this)
                        if (remaining.isEmpty()) {
                            PrefsManager.clearCredentials(this)
                            startActivity(Intent(this, LoginActivity::class.java))
                            finishAffinity()
                        } else if (toDelete.id == activeId) {
                            PrefsManager.setActiveProfile(this, remaining.first().id)
                            switchServerAndReload()
                        }
                    }
                    .setNegativeButton("CANCEL", null))
            })
    }

    private fun switchServerAndReload() {
        player?.stop()
        currentStreamId    = -1
        currentStreamUrl   = ""
        currentChannelName = ""
        currentCategoryId  = ""
        lifecycleScope.launch {
            EpgCache.clearAll(this@TvModeActivity)
            ContentCache.clearAll(this@TvModeActivity)
            TvModeHolder.serverUrl   = PrefsManager.getCredentials(this@TvModeActivity)?.serverUrl ?: ""
            TvModeHolder.allChannels = emptyList()
            TvModeHolder.categories  = emptyList()
            loadChannelsInBackground { showCategoryPanel() }
        }
    }

    private fun refreshServer() {
        val name = PrefsManager.getActiveProfile(this)?.name?.uppercase() ?: "SERVER"
        Toast.makeText(this, "REFRESHING $name...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            EpgCache.clearAll(this@TvModeActivity)
            ContentCache.clearAll(this@TvModeActivity)
            TvModeHolder.allChannels = emptyList()
            TvModeHolder.categories  = emptyList()
            loadChannelsInBackground {
                when (panelState) {
                    PanelState.CHANNELS    -> showChannelPanel()
                    PanelState.CATEGORIES  -> showCategoryPanel()
                    else                   -> { /* main menu / none — nothing to refresh */ }
                }
            }
        }
    }

    private fun showManageCategoriesDialog() {
        val categories = TvModeHolder.categories
        if (categories.isEmpty()) {
            Toast.makeText(this, "NO SERVER CATEGORIES LOADED", Toast.LENGTH_SHORT).show()
            return
        }
        val hiddenIds = CategoryPrefs.getHiddenServerCatIds(this).toMutableSet()
        val labels  = categories.map { it.categoryName.uppercase() }.toTypedArray()
        val checked = categories.map { it.categoryId !in hiddenIds }.toBooleanArray()

        showTvDialog(AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("SHOW / HIDE CATEGORIES")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) hiddenIds.remove(categories[which].categoryId)
                else           hiddenIds.add(categories[which].categoryId)
            }
            .setPositiveButton("SAVE") { _, _ ->
                CategoryPrefs.setHiddenServerCatIds(this, hiddenIds)
                if (panelState == PanelState.CATEGORIES) loadCategoryPanel()
            }
            .setNegativeButton("CANCEL", null))
    }

    private fun showThemePicker() {
        val themes = ThemeManager.allThemes
        val labels = themes.map { t ->
            if (t == ThemeManager.current) "● ${t.label}" else "○ ${t.label}"
        }.toTypedArray()
        showTvDialog(AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("SELECT THEME")
            .setItems(labels) { _, which ->
                ThemeManager.set(this, themes[which])
                recreate()
            })
    }

    private fun toggleTvMode() {
        val enabled = !PrefsManager.isTvModeEnabled(this)
        PrefsManager.setTvModeEnabled(this, enabled)
        if (!enabled) {
            Toast.makeText(this, "TV MODE OFF", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }
    }

    private fun showOpenSubsKeyDialog() {
        val current = PrefsManager.getOpenSubsApiKey(this) ?: ""
        val et = android.widget.EditText(this).apply {
            setText(current)
            hint = "PASTE API KEY HERE"
            setPadding(48, 24, 48, 24)
        }
        val builder = AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
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
        showTvDialog(builder)
    }

    private fun toggleLiveFormat() {
        val next = if (PrefsManager.getLiveFormat(this) == "ts") "m3u8" else "ts"
        PrefsManager.setLiveFormat(this, next)
        ApiClient.liveFormat = next
        val label = if (next == "m3u8") "HLS (.m3u8) — try this if TS shows black screen" else "MPEG-TS (.ts) — standard format"
        Toast.makeText(this, "LIVE FORMAT SET TO: $label", Toast.LENGTH_LONG).show()
    }

    private fun checkForUpdatesManually() {
        Toast.makeText(this, "CHECKING FOR UPDATES...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val update = UpdateChecker.check(this@TvModeActivity)
            if (update != null) showUpdateDialog(update)
            else Toast.makeText(this@TvModeActivity, "ORBITAL IS UP TO DATE", Toast.LENGTH_LONG).show()
        }
    }

    private fun showUpdateDialog(update: UpdateInfo) {
        showTvDialog(AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("UPDATE AVAILABLE — v${update.versionName}")
            .setMessage(update.releaseNotes.uppercase().ifEmpty { "A NEW VERSION OF ORBITAL IS AVAILABLE." })
            .setPositiveButton("DOWNLOAD & INSTALL") { _, _ -> startUpdateDownload(update) }
            .setNegativeButton("LATER", null))
    }

    private fun startUpdateDownload(update: UpdateInfo) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }
        val tvStatus = TextView(this).apply {
            text = "CONNECTING..."
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 24 }
            max = 100
            progress = 0
        }
        val tvBytes = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 8 }
            setTextColor(0xFFAABBCC.toInt())
            textSize = 10f
            typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            gravity = Gravity.END
        }
        container.addView(tvStatus)
        container.addView(progressBar)
        container.addView(tvBytes)

        val dialog = AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("DOWNLOADING v${update.versionName}")
            .setView(container)
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleScope.launch {
            val file = UpdateChecker.downloadWithProgress(this@TvModeActivity, update.downloadUrl) { pct, done, total ->
                if (pct >= 0) {
                    progressBar.progress = pct
                    tvStatus.text = "DOWNLOADING...  $pct%"
                } else {
                    tvStatus.text = "DOWNLOADING..."
                }
                if (total > 0) tvBytes.text = "%.1f / %.1f MB".format(done / 1_048_576.0, total / 1_048_576.0)
            }
            dialog.dismiss()
            if (file != null) {
                UpdateChecker.installApk(this@TvModeActivity, file)
                finish()
            } else {
                Toast.makeText(this@TvModeActivity, "DOWNLOAD FAILED — CHECK CONNECTION", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmClearAllData() {
        showTvDialog(AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("CLEAR ALL SAVED DATA")
            .setMessage("This will remove all server profiles, favourites, and cached data. You will need to log in again. Are you sure?")
            .setPositiveButton("CLEAR ALL") { _, _ ->
                PrefsManager.clearCredentials(this)
                startActivity(Intent(this, LoginActivity::class.java))
                finishAffinity()
            }
            .setNegativeButton("CANCEL", null))
    }

    private fun refreshCategoryChannels() {
        categoryChannels = when {
            currentCategoryId == FAV_CATEGORY_ID -> {
                val favIds = FavouritesManager.getLiveChannels(this).map { it.streamId }.toSet()
                TvModeHolder.allChannels.filter { it.streamId in favIds }
            }
            currentCategoryId.isBlank() -> TvModeHolder.allChannels
            else -> TvModeHolder.allChannels.filter { it.categoryId == currentCategoryId }
        }
    }

    private fun loadChannelsInBackground(onLoaded: (() -> Unit)? = null) {
        val creds = PrefsManager.getCredentials(this) ?: return
        lifecycleScope.launch {
            val streams = withContext(Dispatchers.IO) {
                repository.getLiveStreams(creds.serverUrl, creds.username, creds.password).getOrNull()
            }
            val cats = withContext(Dispatchers.IO) {
                repository.getLiveCategories(creds.serverUrl, creds.username, creds.password).getOrNull()
            }
            if (streams != null) TvModeHolder.allChannels = streams
            if (cats   != null) TvModeHolder.categories   = cats
            refreshCategoryChannels()
            onLoaded?.invoke()
        }
    }

    // ── Key dispatch ──────────────────────────────────────────────────────────
    //
    // Navigation summary:
    //   NONE        + LEFT  → CHANNELS panel
    //   CHANNELS    + LEFT  → CATEGORIES panel
    //   CHANNELS    + RIGHT → close panel (NONE)
    //   CATEGORIES  + LEFT  → MAIN_MENU panel
    //   CATEGORIES  + RIGHT → CHANNELS panel (current category)
    //   MAIN_MENU   + RIGHT → CATEGORIES panel
    //   BACK (any)          → close panel / exit dialog

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val hudVisible = binding.hudTop.visibility == View.VISIBLE
            when (event.keyCode) {

                KeyEvent.KEYCODE_BACK -> {
                    if (event.repeatCount > 0) return true
                    val d = activeDialog
                    if (d != null && d.isShowing) { d.dismiss(); return true }
                    if (panelState != PanelState.NONE) { hidePanel(); return true }
                    if (hudVisible) { hideHudOverlay(); return true }
                    showExitDialog(); return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (!hudVisible && panelState == PanelState.NONE) {
                        showHudOverlay(); return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (panelState == PanelState.NONE) {
                        changeTvChannel(-1); return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (panelState == PanelState.NONE) {
                        changeTvChannel(+1); return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    when (panelState) {
                        PanelState.NONE -> {
                            val hudNavFocused = currentFocus?.let { f ->
                                f == binding.btnHudAudio || f == binding.btnHudRecord ||
                                f == binding.btnHudScores || f == binding.btnHudNews
                            } ?: false
                            if (!hudNavFocused) {
                                hudHandler.removeCallbacks(hideHud)
                                hideHudOverlay()
                                showChannelPanel()
                                return true
                            }
                        }
                        PanelState.CHANNELS   -> { showCategoryPanel(); return true }
                        PanelState.CATEGORIES -> { showMainMenuPanel(); return true }
                        PanelState.MAIN_MENU  -> return true  // already at leftmost — swallow
                    }
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    when (panelState) {
                        PanelState.CHANNELS   -> { hidePanel(); return true }
                        PanelState.CATEGORIES -> { showChannelPanel(); return true }
                        PanelState.MAIN_MENU  -> { showCategoryPanel(); return true }
                        PanelState.NONE       -> { /* normal right navigation */ }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun changeTvChannel(delta: Int) {
        if (categoryChannels.isEmpty()) return
        val idx = categoryChannels.indexOfFirst { it.streamId == currentStreamId }
        val base = if (idx < 0) 0 else idx
        val newIdx = (base + delta + categoryChannels.size) % categoryChannels.size
        val stream = categoryChannels[newIdx]
        val creds = PrefsManager.getCredentials(this) ?: return
        val url = repository.buildStreamUrl(creds.serverUrl, creds.username, creds.password, stream.streamId)
        currentStreamId    = stream.streamId
        currentStreamUrl   = url
        currentChannelName = stream.name
        PrefsManager.setLastTvChannel(this, url, stream.name, stream.streamId, currentCategoryId)
        // Play without opening the full HUD — show lightweight zap bar instead
        player?.setMediaItem(MediaItem.fromUri(url))
        player?.prepare()
        player?.play()
        showZapBar(stream.name)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        player?.play()
        updateRecordButton()
        if (binding.hudTop.visibility != View.VISIBLE && panelState == PanelState.NONE) {
            binding.surfaceView.requestFocus()
        }
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        epgLoadingJob?.cancel()
        hudHandler.removeCallbacksAndMessages(null)
        tickerHandler.removeCallbacksAndMessages(null)
        newsHandler.removeCallbacksAndMessages(null)
        tickerScrollAnim?.cancel()
        newsScrollAnim?.cancel()
        player?.release()
        player = null
    }
}

// ── Now & Next data model ─────────────────────────────────────────────────────

data class NowNextItem(
    val streamId: Int,
    val channelName: String,
    var nowTitle: String  = "",
    var nextTitle: String = "",
    var nextTime: String  = ""
)

// ── Channel panel adapter (channel name + now/next EPG) ───────────────────────

class NowNextAdapter(
    private val items: MutableList<NowNextItem>,
    private var currentId: Int,
    private val onSelect: (Int) -> Unit
) : RecyclerView.Adapter<NowNextAdapter.VH>() {

    inner class VH(root: LinearLayout, val chanTv: TextView, val nowTv: TextView, val nextTv: TextView)
        : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val d = parent.resources.displayMetrics.density
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (58 * d).toInt())
            isFocusable = true; isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val chanTv = TextView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams((170 * d).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * d).toInt(), 0, (8 * d).toInt(), 0)
            textSize = 11f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 2
        }
        val epgCol = LinearLayout(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
        }
        val nowTv = TextView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            textSize = 10f
            typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            setTextColor(0xFFCCDDEE.toInt())
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val nextTv = TextView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            textSize = 9f
            typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            setTextColor(0xFF7799BB.toInt())
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        epgCol.addView(nowTv)
        epgCol.addView(nextTv)
        root.addView(chanTv)
        root.addView(epgCol)
        return VH(root, chanTv, nowTv, nextTv)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item      = items[position]
        val p         = ThemeManager.palette()
        val d         = holder.itemView.resources.displayMetrics.density
        val isPlaying = item.streamId == currentId
        val normalBg  = if (isPlaying) p.highlight else if (position % 2 == 0) 0xFF0A1628.toInt() else 0xFF0D1E38.toInt()

        holder.chanTv.text = item.channelName
        holder.chanTv.setTextColor(if (isPlaying) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        holder.nowTv.text  = if (item.nowTitle.isBlank())  "" else "▶  ${item.nowTitle}"
        holder.nowTv.setTextColor(if (isPlaying) 0xFF333333.toInt() else 0xFFCCDDEE.toInt())
        holder.nextTv.text = when {
            item.nextTitle.isBlank() -> ""
            item.nextTime.isNotBlank() -> "${item.nextTime}  ${item.nextTitle}"
            else -> item.nextTitle
        }
        holder.nextTv.setTextColor(if (isPlaying) 0xFF555555.toInt() else 0xFF7799BB.toInt())

        holder.itemView.background = ThemeManager.roundedBg(normalBg, d)
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (!isPlaying) holder.itemView.background = ThemeManager.roundedBg(if (hasFocus) p.focus else normalBg, d)
        }
        holder.itemView.setOnClickListener { onSelect(item.streamId) }
    }

    fun updateEpg(streamId: Int, nowTitle: String, nextTitle: String, nextTime: String) {
        val idx = items.indexOfFirst { it.streamId == streamId }
        if (idx >= 0) {
            items[idx].nowTitle  = nowTitle
            items[idx].nextTitle = nextTitle
            items[idx].nextTime  = nextTime
            notifyItemChanged(idx)
        }
    }

    /** Update the currently-playing highlight without closing the panel. */
    fun setCurrentId(id: Int) {
        currentId = id
        notifyDataSetChanged()
    }
}

// ── Category panel adapter ────────────────────────────────────────────────────

class CategoryPanelAdapter(
    private val items: List<LiveCategory>,
    private val currentCategoryId: String,
    private val countMap: Map<String, Int>,
    private val onSelect: (LiveCategory) -> Unit
) : RecyclerView.Adapter<CategoryPanelAdapter.VH>() {

    inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val d   = parent.resources.displayMetrics.density
        val tv  = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * d).toInt())
            setPadding((14 * d).toInt(), 0, (10 * d).toInt(), 0)
            gravity = Gravity.CENTER_VERTICAL
            textSize = 12f
            typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            isFocusable = true; isClickable = true
        }
        return VH(tv)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cat       = items[position]
        val p         = ThemeManager.palette()
        val d         = holder.tv.resources.displayMetrics.density
        val isCurrent = cat.categoryId == currentCategoryId
        val normalBg  = if (isCurrent) p.highlight else if (position % 2 == 0) 0xFF0A1628.toInt() else 0xFF0D1E38.toInt()

        val count = if (cat.categoryId == "__favourites__") null
                    else countMap[cat.categoryId]
        holder.tv.text = if (count != null) "${cat.categoryName.uppercase()}  ($count)"
                         else cat.categoryName.uppercase()
        holder.tv.setTextColor(if (isCurrent) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        holder.tv.background = ThemeManager.roundedBg(normalBg, d)
        holder.tv.setOnFocusChangeListener { _, hasFocus ->
            if (!isCurrent) holder.tv.background = ThemeManager.roundedBg(if (hasFocus) p.focus else normalBg, d)
        }
        holder.tv.setOnClickListener { onSelect(cat) }
    }
}

// ── TV-friendly RecyclerView LayoutManager ────────────────────────────────────

private class TvLayoutManager(ctx: android.content.Context) : LinearLayoutManager(ctx) {
    override fun onRequestChildFocus(
        parent: RecyclerView, state: RecyclerView.State,
        child: android.view.View, focused: android.view.View?
    ): Boolean {
        val pos          = getPosition(child)
        val firstComplete = findFirstCompletelyVisibleItemPosition()
        val lastComplete  = findLastCompletelyVisibleItemPosition()
        when {
            pos < firstComplete -> {
                // Scroll only enough to reveal the top of the item — avoids snapping to y=0 when item is partially visible
                val dy = child.top
                if (dy < 0) parent.scrollBy(0, dy) else scrollToPositionWithOffset(pos, 0)
            }
            pos > lastComplete  -> scrollToPositionWithOffset(pos, (parent.height - child.height).coerceAtLeast(0))
        }
        return true
    }
}
