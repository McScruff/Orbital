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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bumptech.glide.Glide
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.orbital.iptv.ui.player.PcmOnlyRenderersFactory
import com.orbital.iptv.ui.player.StallWatchdog
import com.orbital.iptv.R
import com.orbital.iptv.data.api.ApiClient
import com.orbital.iptv.data.model.EpgListing
import com.orbital.iptv.data.model.LiveCategory
import com.orbital.iptv.data.model.LiveStream
import com.orbital.iptv.data.model.ServerProfile
import com.orbital.iptv.data.model.getDecodedTitle
import com.orbital.iptv.data.model.getDecodedDescription
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
import com.orbital.iptv.ui.epg.EpgRow
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        private const val MAX_IO_RETRIES = 5
        private const val MAX_STALL_RETRIES = 5
    }

    // Three-level left-panel navigation.
    // NONE      – full-screen TV, no panel visible
    // CHANNELS  – channel list + live EPG column for focused channel
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
    private var focusedChannelStreamId = -1
    private var inlineEpgCurrentIdx = 0
    private val inlineEpgHandler = Handler(Looper.getMainLooper())

    // Previous channel — for RIGHT-key "last channel" toggle
    private var prevStreamId    = -1
    private var prevStreamUrl   = ""
    private var prevChannelName = ""
    private var prevCategoryId  = ""

    // Full EPG guide overlay
    private var guideCategoryId = ""
    private var guideLoadJob: Job? = null

    private val hudHandler  = Handler(Looper.getMainLooper())
    private val hideHud     = Runnable { hideHudOverlay() }
    private val zapHandler  = Handler(Looper.getMainLooper())
    private val hideZap     = Runnable { hideZapBar() }
    private val repository  = XtreamRepository()
    private var isRecording = false

    private var tvIoRetryCount = 0
    private var tvStallRetryCount = 0
    private val tvRetryHandler = Handler(Looper.getMainLooper())
    private val stallWatchdog = StallWatchdog(
        getPlayer = { player },
        onStall = { handleTvStall() }
    )
    private val tvPlayerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                tvIoRetryCount = 0
                tvStallRetryCount = 0
            }
        }

        // Player.Listener was never attached in TV Mode before — a dropped connection, bad
        // HTTP status, or decoder failure just left the last decoded frame frozen on screen
        // forever with no retry and no indication anything was wrong. Mirrors PlayerActivity's
        // capped, backed-off IO retry (TV Mode is always live, so none of the VOD-only branches
        // apply here).
        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.w("OrbitalTvPlayer", "onPlayerError code=${error.errorCode} name=${error.errorCodeName} msg=${error.message}")
            val exo = player ?: return
            if (tvIoRetryCount >= MAX_IO_RETRIES) {
                Toast.makeText(this@TvModeActivity, "CHANNEL UNAVAILABLE — ${currentChannelName.uppercase()}", Toast.LENGTH_LONG).show()
                return
            }
            tvIoRetryCount++
            tvRetryHandler.removeCallbacksAndMessages(null)
            tvRetryHandler.postDelayed({
                switchStream(exo, currentStreamUrl, isRetry = true)
            }, 1000L * tvIoRetryCount)
        }
    }

    /**
     * Some live streams stall silently — ExoPlayer never calls onPlayerError, it just stops
     * advancing (see StallWatchdog doc comment). Force a reconnect, capped like the IO-error
     * retry above so a truly dead channel still surfaces feedback instead of retrying forever.
     */
    private fun handleTvStall() {
        val exo = player ?: return
        android.util.Log.w("OrbitalTvPlayer", "stall watchdog fired (retry ${tvStallRetryCount + 1}/$MAX_STALL_RETRIES)")
        if (tvStallRetryCount >= MAX_STALL_RETRIES) {
            Toast.makeText(this, "CHANNEL STALLED — ${currentChannelName.uppercase()}", Toast.LENGTH_LONG).show()
            return
        }
        tvStallRetryCount++
        switchStream(exo, currentStreamUrl, isRetry = true)
    }

    private val tickerHttp = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private val tickerHandler = Handler(Looper.getMainLooper())
    private val newsHandler   = Handler(Looper.getMainLooper())
    private var pendingTickerText: String? = null
    private var tickerScrollAnim: ValueAnimator? = null
    private var tickerShowingPlaceholder = false
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
        // Load before inflating — EpgView (in guide_overlay) reads ThemeManager.palette() once
        // in its constructor, so a cold start would otherwise bake in the default ORBITAL
        // palette regardless of the user's saved theme (see HomeActivity.onCreate for the same fix).
        ThemeManager.load(this)
        binding = ActivityTvModeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyTvTheme()

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
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(
                PcmOnlyRenderersFactory(this, PrefsManager.isSurroundEnabled(this))
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(com.orbital.iptv.ui.player.LiveLoadControl.build())
            .build().also { exo ->
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setPreferredAudioMimeTypes(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AC3)
                .build()
            exo.repeatMode = Player.REPEAT_MODE_OFF
            exo.addListener(tvPlayerListener)
            exo.setVideoSurfaceView(binding.surfaceView)
            if (currentStreamUrl.isNotBlank()) {
                exo.setMediaItem(MediaItem.fromUri(currentStreamUrl))
                exo.prepare()
                exo.play()
                updateChannelInfo(currentChannelName)
                loadEpgForCurrentChannel()
                stallWatchdog.start()
            }
        }
    }

    /** Toggles PcmOnlyRenderersFactory's stereo-only lock — see its doc comment for the tradeoff. */
    private fun toggleSurroundSound() {
        if (!hasSurroundAudioTrack()) {
            Toast.makeText(this, "NO 5.1/SURROUND AUDIO TRACK ON THIS CHANNEL", Toast.LENGTH_SHORT).show()
            return
        }
        val newState = !PrefsManager.isSurroundEnabled(this)
        PrefsManager.setSurroundEnabled(this, newState)
        updateSurroundButton()
        // RenderersFactory is baked in at ExoPlayer construction, so the only way to apply the
        // new capability setting is to release and rebuild the player against the same channel.
        // initPlayer() reattaches via exo.setVideoSurfaceView(), which (unlike a hand-rolled
        // SurfaceHolder.Callback — see PlayerActivity.initExoPlayer()) attaches immediately when
        // the surface is already valid, so no extra surface handling is needed here.
        player?.clearVideoSurface()
        player?.removeListener(tvPlayerListener)
        player?.release()
        initPlayer()
        Toast.makeText(
            this,
            if (newState) "SURROUND: ON — testing real device audio capabilities" else "SURROUND: OFF — forced stereo",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateSurroundButton() {
        val on = PrefsManager.isSurroundEnabled(this)
        binding.btnHudSurround.text = if (on) "5.1 ✓" else "5.1"
        val available = hasSurroundAudioTrack()
        binding.btnHudSurround.isEnabled = available
        binding.btnHudSurround.visibility = if (available) View.VISIBLE else View.GONE
    }

    /**
     * True if the audio track actually SELECTED for playback right now is 5.1/7.1+ (channel
     * count) or explicitly labelled surround — not just any track the stream happens to offer.
     * Many IPTV channels bundle a stereo AAC track alongside a 5.1 AC3 alternate, and
     * setPreferredAudioMimeTypes() picks AAC first, so checking "any available track" let the
     * button light up even while a plain stereo track was the one actually playing.
     */
    private fun hasSurroundAudioTrack(): Boolean {
        val exo = player ?: return false
        return exo.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_AUDIO && (0 until group.length).any { i ->
                group.isTrackSelected(i) && run {
                    val fmt = group.getTrackFormat(i)
                    fmt.channelCount >= 6 ||
                        fmt.label?.contains("5.1", ignoreCase = true) == true ||
                        fmt.label?.contains("surround", ignoreCase = true) == true
                }
            }
        }
    }

    private fun playChannel(url: String, name: String) {
        val exo = player ?: return
        switchStream(exo, url)
        showInfoBar(name)
        loadEpgForCurrentChannel()
        recordRecentChannel(currentStreamId, name, url)
    }

    /** Radio (currentStreamId == -1) is deliberately excluded — "recently watched" means live TV. */
    private fun recordRecentChannel(streamId: Int, name: String, url: String) {
        if (streamId < 0) return
        val icon = TvModeHolder.allChannels.find { it.streamId == streamId }?.streamIcon
        com.orbital.iptv.utils.RecentChannelsManager.record(this, name, streamId, url, icon)
    }

    /**
     * Fully tears down the current playlist/decoders before loading [url]. A seamless
     * setMediaItem() while playing can leave the video renderer holding its last decoded
     * frame (stale picture) while the audio renderer moves on to the new stream — stop()
     * alone doesn't release decoders, so clearMediaItems() is required to force ExoPlayer
     * to rebuild fresh MediaCodec instances on the next prepare().
     */
    private fun switchStream(exo: ExoPlayer, url: String, isRetry: Boolean = false) {
        if (!isRetry) {
            tvIoRetryCount = 0
            tvStallRetryCount = 0
            tvRetryHandler.removeCallbacksAndMessages(null)
        }
        stallWatchdog.reset()
        exo.stop()
        exo.clearMediaItems()
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.play()
    }

    /**
     * Recolours every static chrome view (panels, HUD, tickers) from the current
     * [ThemeManager] palette. These were originally hardcoded to a fixed dark-blue XML
     * colour, so switching themes (e.g. to BLACK & WHITE) had no visible effect on them.
     * Called once from onCreate — a theme change always goes through showThemePicker()'s
     * recreate(), so onCreate (and this) reruns with the freshly-selected palette.
     */
    /** 0 (fully opaque) – 255 (fully see-through), derived from the TRANSPARENCY setting. */
    private fun panelAlpha(): Int = 255 - (PrefsManager.getTvPanelTransparency(this) * 255 / 100)

    private fun applyTvTheme() {
        val p = ThemeManager.palette()
        val density = resources.displayMetrics.density

        // System-drawn, not a View — normally hidden by the immersive flags below, but still
        // worth matching in case it flashes during a system-UI transition.
        window.statusBarColor = p.bgPrimary
        window.navigationBarColor = p.bgPrimary

        binding.leftPanel.setBackgroundColor(ThemeManager.withAlpha(p.bgHeader, panelAlpha()))
        binding.panelHeader.setBackgroundColor(p.bgHeader)
        binding.panelHeader.setTextColor(p.accent)
        binding.dividerPanelHeader.setBackgroundColor(p.accent)
        binding.dividerChannelsSplit.setBackgroundColor(p.accent)
        val menuDividerColor = ThemeManager.withAlpha(p.accent, 0x40)
        listOf(
            binding.dividerMenu1, binding.dividerMenu2, binding.dividerMenu3,
            binding.dividerMenu4, binding.dividerMenu5
        ).forEach { it.setBackgroundColor(menuDividerColor) }

        binding.guideOverlay.setBackgroundColor(p.bgPrimary)
        binding.guideHeader.setBackgroundColor(p.bgHeader)
        binding.guideHeader.setTextColor(p.accent)
        binding.dividerGuideHeader.setBackgroundColor(p.accent)
        binding.dividerGuideSplit.setBackgroundColor(p.accent)

        binding.hudTop.setBackgroundColor(ThemeManager.withAlpha(p.bgHeader, panelAlpha()))
        binding.tvChannelName.setTextColor(p.accent)
        binding.btnHudMenu.background = ThemeManager.hudButtonDrawable(density, withAccentStroke = false)
        listOf(binding.btnHudAudio, binding.btnHudSurround, binding.btnHudScores, binding.btnHudNews).forEach {
            it.background = ThemeManager.hudButtonDrawable(density)
        }

        binding.hudBottom.setBackgroundColor(p.bgHeader)
        binding.dividerHudBottom.setBackgroundColor(p.accent)
        binding.rowHudChannelInfo.setBackgroundColor(p.bgPrimary)
        binding.rowHudNow.setBackgroundColor(p.bgMid)
        binding.labelHudNow.setTextColor(p.accent)
        binding.liveNextRow.setBackgroundColor(p.bgPrimary)
        binding.tvEpgNextTime.setTextColor(p.accent)

        binding.newsTickerRow.setBackgroundColor(ThemeManager.withAlpha(p.bgPrimary, panelAlpha()))
        binding.tickerRow.setBackgroundColor(ThemeManager.withAlpha(p.bgPrimary, panelAlpha()))
        binding.tvTicker.setTextColor(p.accent)
        binding.tvNewsTicker.textColor = p.accent
    }

    private fun setupButtons() {
        val p = ThemeManager.palette()
        binding.surfaceView.setOnClickListener { showHudOverlay() }

        fun menuItem(view: android.widget.TextView, bg: Int, action: () -> Unit) {
            val bgA = ThemeManager.withAlpha(bg, panelAlpha())
            view.tag = bgA
            view.setBackgroundColor(bgA)
            view.setOnClickListener { action() }
            view.setOnFocusChangeListener { _, hasFocus ->
                view.setBackgroundColor(if (hasFocus) p.focus else bgA)
            }
        }

        menuItem(binding.menuItemLiveTv, p.rowEven)      { hidePanel() }
        menuItem(binding.menuItemGuide, p.rowOdd)        { showGuideOverlay() }
        menuItem(binding.menuItemBoxOffice, p.rowEven)   { showBoxOfficeMenu() }
        menuItem(binding.menuItemRadio, p.rowOdd)        { showRadioMenu() }
        menuItem(binding.menuItemInteractive, p.rowEven) { showInteractiveMenu() }
        menuItem(binding.menuItemSettings, p.rowOdd)     { showTvSettingsMenu() }
    }

    private fun setupHudButtons() {
        val p = ThemeManager.palette()
        val density = resources.displayMetrics.density

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

        updateSurroundButton()
        binding.btnHudSurround.setOnClickListener { toggleSurroundSound() }
        timerReset(binding.btnHudSurround)

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
            } else if (TickerManager.tickerEnabled) {
                binding.btnHudScores.setBackgroundResource(R.drawable.bg_btn_scores_on)
            } else {
                binding.btnHudScores.background = ThemeManager.hudButtonDrawable(density)
            }
        }
        binding.btnHudScores.setOnClickListener { toggleTicker() }

        binding.btnHudNews.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.btnHudNews.setBackgroundColor(p.focus)
                hudHandler.removeCallbacks(hideHud); hudHandler.postDelayed(hideHud, 5000L)
            } else if (TickerManager.newsTickerEnabled) {
                binding.btnHudNews.setBackgroundResource(R.drawable.bg_btn_scores_on)
            } else {
                binding.btnHudNews.background = ThemeManager.hudButtonDrawable(density)
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
        updateSurroundButton()
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
        activeDialog = AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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
        AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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
        if (on) binding.btnHudScores.setBackgroundResource(R.drawable.bg_btn_scores_on)
        else binding.btnHudScores.background = ThemeManager.hudButtonDrawable(resources.displayMetrics.density)
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
        if (on) binding.btnHudNews.setBackgroundResource(R.drawable.bg_btn_scores_on)
        else binding.btnHudNews.background = ThemeManager.hudButtonDrawable(resources.displayMetrics.density)
    }

    private fun toggleNewsTicker() {
        TickerManager.newsTickerEnabled = !TickerManager.newsTickerEnabled
        updateNewsButton()
        if (TickerManager.newsTickerEnabled) startNewsTicker() else stopNewsTicker()
        showHudOverlay()
    }

    private fun startNewsTicker() {
        binding.newsTickerRow.visibility = View.VISIBLE
        binding.tvNewsTicker.text = TickerManager.buildNewsText()
        binding.tvNewsTicker.start()
        newsHandler.removeCallbacks(newsRunnable)
        newsHandler.post(newsRunnable)
    }

    private fun stopNewsTicker() {
        newsHandler.removeCallbacks(newsRunnable)
        binding.tvNewsTicker.stop()
        binding.newsTickerRow.visibility = View.GONE
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
        binding.tvNewsTicker.text = TickerManager.buildNewsText()
    }

    // ── Left-panel navigation state machine ───────────────────────────────────

    /** Open the CHANNELS panel (channel list + inline EPG column). */
    private fun showChannelPanel() {
        hideHudOverlay()
        zapHandler.removeCallbacks(hideZap)
        panelState = PanelState.CHANNELS
        binding.leftPanel.visibility           = View.VISIBLE
        binding.layoutChannelsSplit.visibility = View.VISIBLE
        binding.rvPanelCategories.visibility   = View.GONE
        binding.panelMainMenu.visibility       = View.GONE

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
        binding.leftPanel.visibility           = View.VISIBLE
        binding.layoutChannelsSplit.visibility = View.GONE
        binding.rvPanelCategories.visibility   = View.VISIBLE
        binding.panelMainMenu.visibility       = View.GONE
        binding.panelHeader.text               = "CATEGORIES"
        loadCategoryPanel()
    }

    /** Open the MAIN_MENU panel (Live TV / Box Office / Radio / Interactive / Settings). */
    private fun showMainMenuPanel() {
        panelState = PanelState.MAIN_MENU
        binding.leftPanel.visibility           = View.VISIBLE
        binding.layoutChannelsSplit.visibility = View.GONE
        binding.rvPanelCategories.visibility   = View.GONE
        binding.panelMainMenu.visibility       = View.VISIBLE
        binding.panelHeader.text               = "ORBITAL"
        binding.panelMainMenu.post { binding.menuItemLiveTv.requestFocus() }
    }

    /** Load EPG for [streamId] into the inline EPG column, debounced to avoid thrash while scrolling. */
    private fun scheduleInlineEpg(streamId: Int) {
        inlineEpgHandler.removeCallbacksAndMessages(null)
        inlineEpgHandler.postDelayed({ loadInlineEpg(streamId) }, 250)
    }

    private fun loadInlineEpg(streamId: Int) {
        epgLoadingJob?.cancel()
        val creds = PrefsManager.getCredentials(this) ?: return
        epgLoadingJob = lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) {
                EpgCache.get(this@TvModeActivity, streamId, minCount = 50)
            }
            val listings = cached ?: withContext(Dispatchers.IO) {
                repository.getFullChannelEpg(
                    creds.serverUrl, creds.username, creds.password, streamId
                ).getOrNull()?.listings?.also { l ->
                    EpgCache.put(this@TvModeActivity, streamId, l)
                } ?: emptyList()
            }

            val nowSec = System.currentTimeMillis() / 1000
            val horizonSec = nowSec + 24 * 3600
            val sorted = listings
                .sortedBy { it.startTimestamp?.toLongOrNull() ?: 0L }
                .filter { (it.startTimestamp?.toLongOrNull() ?: 0L) < horizonSec }
            val currentIdx = sorted.indexOfFirst { l ->
                val start = l.startTimestamp?.toLongOrNull() ?: return@indexOfFirst false
                val end   = l.stopTimestamp?.toLongOrNull()  ?: return@indexOfFirst false
                nowSec in start..end
            }

            if (binding.layoutChannelsSplit.visibility != View.VISIBLE) return@launch
            inlineEpgCurrentIdx = currentIdx.coerceAtLeast(0)
            binding.rvChannelEpg.layoutManager = LinearLayoutManager(this@TvModeActivity)
            binding.rvChannelEpg.itemAnimator  = null
            binding.rvChannelEpg.adapter       = EpgListAdapter(sorted, currentIdx, nowSec, panelAlpha()) { listing, isCurrent ->
                if (isCurrent) {
                    selectChannelFromPanel(streamId)
                } else {
                    val channelName = categoryChannels.find { it.streamId == streamId }?.name
                        ?: TvModeHolder.allChannels.find { it.streamId == streamId }?.name ?: ""
                    val url = repository.buildStreamUrl(creds.serverUrl, creds.username, creds.password, streamId)
                    handleProgrammeTap(streamId, channelName, url, listing)
                }
            }
            (binding.rvChannelEpg.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(currentIdx.coerceAtLeast(0), 0)
        }
    }

    /** Close all panels and return to full-screen TV. */
    private fun hidePanel() {
        epgLoadingJob?.cancel()
        panelState = PanelState.NONE
        binding.leftPanel.visibility = View.GONE
        binding.surfaceView.requestFocus()
    }

    // ── Channel panel ─────────────────────────────────────────────────────────

    /** True if the currently focused view is [ancestor] or one of its descendants. */
    private fun isFocusWithin(ancestor: View): Boolean {
        var v: View? = currentFocus
        while (v != null) {
            if (v === ancestor) return true
            v = v.parent as? View
        }
        return false
    }

    /** Tunes to [streamId] from the channel panel or the inline EPG column's "now playing" row. */
    private fun selectChannelFromPanel(streamId: Int) {
        val ch    = categoryChannels.find { it.streamId == streamId } ?: return
        val creds = PrefsManager.getCredentials(this) ?: return
        val url   = repository.buildStreamUrl(creds.serverUrl, creds.username, creds.password, ch.streamId)
        prevStreamId    = currentStreamId
        prevStreamUrl   = currentStreamUrl
        prevChannelName = currentChannelName
        prevCategoryId  = currentCategoryId
        currentStreamId    = ch.streamId
        currentStreamUrl   = url
        currentChannelName = ch.name
        PrefsManager.setLastTvChannel(this, url, ch.name, ch.streamId, currentCategoryId)
        playChannel(url, ch.name)
        channelPanelAdapter?.setCurrentId(currentStreamId)
    }

    /** Moves focus from the channel list into the inline EPG column, landing on the
     *  currently-airing row so the user can arrow up/down to browse other times. */
    private fun focusEpgColumn() {
        binding.rvChannelEpg.post {
            (binding.rvChannelEpg.findViewHolderForAdapterPosition(inlineEpgCurrentIdx)?.itemView
                ?: binding.rvChannelEpg.getChildAt(0))?.requestFocus()
        }
    }

    /** Moves focus from the inline EPG column back to the channel list, landing on whichever
     *  channel row the EPG column is currently showing. */
    private fun focusChannelColumn() {
        val id  = if (focusedChannelStreamId >= 0) focusedChannelStreamId else currentStreamId
        val idx = categoryChannels.indexOfFirst { it.streamId == id }.coerceAtLeast(0)
        binding.rvPanelChannels.post {
            (binding.rvPanelChannels.findViewHolderForAdapterPosition(idx)?.itemView
                ?: binding.rvPanelChannels.getChildAt(0))?.requestFocus()
        }
    }

    private fun loadChannelPanel(channels: List<LiveStream>) {
        val items = channels.map { ch -> NowNextItem(ch.streamId, ch.name) }.toMutableList()

        channelPanelAdapter = NowNextAdapter(items, currentStreamId, panelAlpha(), onFocus = { id ->
            focusedChannelStreamId = id
            scheduleInlineEpg(id)
        }) { streamId -> selectChannelFromPanel(streamId) }

        binding.rvPanelChannels.layoutManager = TvLayoutManager(this)
        binding.rvPanelChannels.itemAnimator  = null
        binding.rvPanelChannels.adapter       = channelPanelAdapter

        val idx = channels.indexOfFirst { it.streamId == currentStreamId }.coerceAtLeast(0)
        (binding.rvPanelChannels.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(idx, 0)

        binding.rvPanelChannels.post {
            (binding.rvPanelChannels.findViewHolderForAdapterPosition(idx)?.itemView
                ?: binding.rvPanelChannels.getChildAt(0))?.requestFocus()
        }

        // Seed the EPG column with the current channel immediately
        scheduleInlineEpg(currentStreamId)
    }

    // ── Category panel ────────────────────────────────────────────────────────

    private fun loadCategoryPanel() {
        val favCat  = LiveCategory(FAV_CATEGORY_ID, "★  FAVOURITES", 0)
        val allCats = listOf(favCat) + visibleCategories()
        val countMap = TvModeHolder.allChannels.groupingBy { it.categoryId }.eachCount()

        val adapter = CategoryPanelAdapter(allCats, currentCategoryId, countMap.mapKeys { it.key ?: "" }, panelAlpha()) { cat ->
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

    // ── Full EPG guide overlay ──────────────────────────────────────────────────

    private fun showGuideOverlay() {
        hidePanel()
        binding.guideOverlay.visibility = View.VISIBLE
        guideCategoryId = currentCategoryId.ifBlank { visibleCategories().firstOrNull()?.categoryId ?: "" }
        loadGuideCategories()
        loadGuideEpg(guideCategoryId)
    }

    private fun hideGuideOverlay() {
        guideLoadJob?.cancel()
        binding.guideOverlay.visibility = View.GONE
        binding.surfaceView.requestFocus()
    }

    private fun loadGuideCategories() {
        val favCat  = LiveCategory(FAV_CATEGORY_ID, "★  FAVOURITES", 0)
        val allCats = listOf(favCat) + visibleCategories()
        val countMap = TvModeHolder.allChannels.groupingBy { it.categoryId }.eachCount()

        val adapter = CategoryPanelAdapter(allCats, guideCategoryId, countMap.mapKeys { it.key ?: "" }) { cat ->
            fun openCategory() {
                guideCategoryId = cat.categoryId
                loadGuideCategories()
                loadGuideEpg(guideCategoryId)
            }
            if (PinManager.isCategoryLocked(this, cat.categoryId)) {
                tvPromptPin("ENTER PIN TO VIEW CATEGORY") { openCategory() }
            } else {
                openCategory()
            }
        }
        binding.rvGuideCategories.layoutManager = TvLayoutManager(this)
        binding.rvGuideCategories.itemAnimator  = null
        binding.rvGuideCategories.adapter       = adapter

        val idx = allCats.indexOfFirst { it.categoryId == guideCategoryId }.coerceAtLeast(0)
        (binding.rvGuideCategories.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(idx, 0)
    }

    private fun loadGuideEpg(categoryId: String, onComplete: (() -> Unit)? = null) {
        guideLoadJob?.cancel()
        val creds = PrefsManager.getCredentials(this) ?: return

        val channels = when {
            categoryId == FAV_CATEGORY_ID -> favouriteChannelsList()
            categoryId.isBlank() -> TvModeHolder.allChannels
            else -> TvModeHolder.allChannels.filter { it.categoryId == categoryId }
        }

        val catName = when (categoryId) {
            FAV_CATEGORY_ID -> "FAVOURITES"
            else -> visibleCategories().find { it.categoryId == categoryId }?.categoryName?.uppercase() ?: "GUIDE"
        }
        binding.guideHeader.text = "GUIDE — $catName"

        val rows = channels.map { EpgRow(streamId = it.streamId, channelName = it.name) }
        binding.epgViewGuide.setRows(rows)
        binding.epgViewGuide.onRequestFocusLeft = { binding.rvGuideCategories.requestFocus() }
        binding.epgViewGuide.onChannelSelected   = { streamId -> tuneFromGuide(streamId) }
        binding.epgViewGuide.onProgrammeSelected = onProg@{ streamId, channelName, listing ->
            val nowSec = System.currentTimeMillis() / 1000
            val start  = listing.startTimestamp?.toLongOrNull()
            val end    = listing.stopTimestamp?.toLongOrNull()
            val isCurrent = start != null && end != null && nowSec in start..end
            if (isCurrent) {
                tuneFromGuide(streamId)
            } else {
                val gCreds = PrefsManager.getCredentials(this) ?: return@onProg
                val url = repository.buildStreamUrl(gCreds.serverUrl, gCreds.username, gCreds.password, streamId)
                handleProgrammeTap(streamId, channelName, url, listing)
            }
        }

        // Capped concurrency: firing one request per channel at once (large categories can be
        // 100+ channels) floods the Xtream server, which throttles/truncates the burst — that's
        // what made a freshly-cleared cache come back showing only "now" or nothing at all
        // instead of the full multi-day guide. A shared semaphore keeps only a handful in flight.
        val fetchLimiter = Semaphore(6)
        guideLoadJob = lifecycleScope.launch {
            // coroutineScope waits for every per-channel launch{} below to finish before this
            // block completes, so onComplete (e.g. the "refresh done" toast) fires only once the
            // whole category has actually loaded rather than right after kicking off the fetches.
            coroutineScope {
                rows.forEach { row ->
                    launch {
                        val cached = withContext(Dispatchers.IO) {
                            EpgCache.get(this@TvModeActivity, row.streamId, minCount = 50)
                        }
                        val listings = cached ?: withContext(Dispatchers.IO) {
                            fetchLimiter.withPermit {
                                repository.getFullChannelEpg(
                                    creds.serverUrl, creds.username, creds.password, row.streamId
                                )
                            }.getOrNull()?.listings?.also { l ->
                                EpgCache.put(this@TvModeActivity, row.streamId, l)
                            } ?: emptyList()
                        }
                        binding.epgViewGuide.updateRow(row.streamId, listings)
                    }
                }
            }
            onComplete?.invoke()
        }

        binding.epgViewGuide.post { binding.epgViewGuide.requestFocus() }
    }

    private fun tuneFromGuide(streamId: Int) {
        val stream = TvModeHolder.allChannels.find { it.streamId == streamId } ?: return
        val creds  = PrefsManager.getCredentials(this) ?: return
        val url    = repository.buildStreamUrl(creds.serverUrl, creds.username, creds.password, stream.streamId)
        prevStreamId    = currentStreamId
        prevStreamUrl   = currentStreamUrl
        prevChannelName = currentChannelName
        prevCategoryId  = currentCategoryId
        currentStreamId    = stream.streamId
        currentStreamUrl   = url
        currentChannelName = stream.name
        currentCategoryId  = stream.categoryId ?: currentCategoryId
        PrefsManager.setLastTvChannel(this, url, stream.name, stream.streamId, currentCategoryId)
        hideGuideOverlay()
        playChannel(url, stream.name)
    }

    // ── EPG recording / reminder (mirrors HomeActivity/EpgActivity) ────────────

    /** Info/record/reminder popup for a non-current programme, from either the guide overlay or
     *  the channel panel's inline EPG column — the current airing tunes the channel instead. */
    private fun handleProgrammeTap(streamId: Int, channelName: String, url: String, listing: EpgListing) {
        val title   = listing.getDecodedTitle().ifBlank { "Recording" }
        val startMs = (listing.startTimestamp?.toLongOrNull() ?: 0L) * 1000L
        val endMs   = (listing.stopTimestamp?.toLongOrNull()  ?: 0L) * 1000L
        val nowMs   = System.currentTimeMillis()

        if (endMs > 0L && endMs <= nowMs) {
            showTvDialog(AlertDialog.Builder(this, ThemeManager.dialogStyle())
                .setTitle("CANNOT RECORD")
                .setMessage("'$title' has already finished.")
                .setPositiveButton("OK", null))
            return
        }

        val timeFmt = SimpleDateFormat("HH:mm", Locale.UK)
        val dateFmt = SimpleDateFormat("EEE dd MMM", Locale.UK)
        val availGb = com.orbital.iptv.recording.RecordingRepository.availableGb(this)
        val timeStr = if (startMs > 0L) {
            val dateStr = dateFmt.format(java.util.Date(startMs))
            val endStr  = if (endMs > 0L) timeFmt.format(java.util.Date(endMs)) else "?"
            "$dateStr  ${timeFmt.format(java.util.Date(startMs))} — $endStr"
        } else "Time unknown"

        // The EPG listing's own description is the authoritative synopsis for THIS airing —
        // available synchronously. The VOD/series lookup below only fills in a poster + a
        // short genre/year/rating line, and is a plot fallback if the broadcaster sends none.
        val epgSynopsis = listing.getDecodedDescription().trim()

        fun infoBody(catalogHeader: String?, catalogPlotFallback: String?) = listOfNotNull(
            "$channelName\n$timeStr",
            catalogHeader?.takeIf { it.isNotBlank() },
            epgSynopsis.ifBlank { catalogPlotFallback ?: "" }.takeIf { it.isNotBlank() },
            "Available storage: ${"%.1f".format(availGb)} GB"
        ).joinToString("\n\n")

        val (posterView, posterImg, infoTv) = buildProgrammeInfoView()
        infoTv.text = infoBody(null, null)

        val label = if (startMs > nowMs) "SCHEDULE RECORDING" else "RECORD NOW (ongoing)"
        val builder = AlertDialog.Builder(this, ThemeManager.dialogStyle())
            .setTitle("$title — $label")
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
        showTvDialog(builder)

        // Best-effort VOD/series catalog lookup for a poster (+ genre/year/rating line) — never
        // blocks the dialog, depends on the movies/series cache already being warm on disk.
        val durationMinutes = if (startMs > 0L && endMs > startMs) (endMs - startMs) / 60_000L else -1L
        lifecycleScope.launch {
            val creds = PrefsManager.getCredentials(this@TvModeActivity) ?: return@launch
            val match = lookupShowPoster(creds.serverUrl, creds.username, creds.password, title, epgSynopsis, durationMinutes)
                ?: return@launch
            match.posterUrl?.takeIf { it.isNotBlank() }?.let { Glide.with(this@TvModeActivity).load(it).into(posterImg) }
            infoTv.text = infoBody(match.header, match.plot)
        }
    }

    private enum class ShowKind { MOVIE, SERIES, UNKNOWN }

    /**
     * Xtream's EPG data has no explicit movie/series flag, so infer it from the listing text and
     * runtime: season/episode markers ("S3 E12", "Series 3", "Ep 4/6") are the strongest, least
     * ambiguous signal and always mean SERIES; failing that, a "(YYYY)" year suffix or a runtime
     * over ~75 minutes leans MOVIE, and a short runtime leans SERIES.
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

        val tmdbKey = PrefsManager.getTmdbApiKey(this@TvModeActivity)
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
            val matches = ContentCache.searchSeries(this@TvModeActivity, serverUrl, query)
            val series = matches.firstOrNull { isGoodTitleMatch(query, it.name) } ?: return null
            return ShowPosterMatch(series.cover, header(series.genre, series.releaseDate, series.rating), series.plot)
        }

        suspend fun tryMovie(): ShowPosterMatch? {
            val matches = ContentCache.searchMovies(this@TvModeActivity, serverUrl, query)
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
        .replace(Regex("\\(\\d{4}\\)"), "")
        .replace(Regex("s\\d{1,2}\\s?e\\d{1,3}", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Requires an exact match after normalising noise (years, S01E02, punctuation) — see
     *  HomeActivity.isGoodTitleMatch for why partial/prefix matching is unsafe for TV titles. */
    private fun isGoodTitleMatch(epgTitle: String, candidateName: String): Boolean {
        val a = normalizeTitle(epgTitle)
        val b = normalizeTitle(candidateName)
        return a.isNotBlank() && a == b
    }

    private fun scheduleReminder(channelName: String, title: String, startMs: Long, streamUrl: String, streamId: Int) {
        val delayMs = (startMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val data = androidx.work.Data.Builder()
            .putString(com.orbital.iptv.recording.ReminderWorker.KEY_TITLE,      title)
            .putString(com.orbital.iptv.recording.ReminderWorker.KEY_CHANNEL,    channelName)
            .putString(com.orbital.iptv.recording.ReminderWorker.KEY_STREAM_URL, streamUrl)
            .putInt(com.orbital.iptv.recording.ReminderWorker.KEY_STREAM_ID,     streamId)
            .build()
        val request = androidx.work.OneTimeWorkRequestBuilder<com.orbital.iptv.recording.ReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag("reminder_${title.hashCode()}")
            .build()
        androidx.work.WorkManager.getInstance(this).enqueue(request)
        val timeStr = SimpleDateFormat("HH:mm", Locale.UK).format(java.util.Date(startMs))
        Toast.makeText(this, "REMINDER SET: $title at $timeStr", Toast.LENGTH_SHORT).show()
    }

    private fun askForDuration(onChosen: (Long) -> Unit) {
        val options   = arrayOf("30 minutes", "1 hour", "1 hour 30 min", "2 hours", "3 hours")
        val durations = longArrayOf(30, 60, 90, 120, 180)
        showTvDialog(AlertDialog.Builder(this, ThemeManager.dialogStyle())
            .setTitle("RECORDING DURATION")
            .setMessage("No end time found in EPG. How long should we record?")
            .setItems(options) { _, i -> onChosen(durations[i] * 60_000L) }
            .setNegativeButton("CANCEL", null))
    }

    private fun scheduleRecording(streamId: Int, channelName: String, url: String, title: String, startMs: Long, endMs: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val recording = com.orbital.iptv.recording.RecordingEntity(
                    channelName    = channelName,
                    channelUrl     = url,
                    streamId       = streamId,
                    epgTitle       = title,
                    scheduledStart = startMs,
                    scheduledEnd   = endMs,
                    status         = com.orbital.iptv.recording.RecordingStatus.SCHEDULED
                )
                val id = com.orbital.iptv.recording.RecordingDatabase.get(this@TvModeActivity).dao().insert(recording).toInt()

                val delayMs = startMs - System.currentTimeMillis()
                if (delayMs <= 0L) {
                    androidx.core.content.ContextCompat.startForegroundService(
                        this@TvModeActivity,
                        Intent(this@TvModeActivity, RecordingService::class.java).apply {
                            putExtra(com.orbital.iptv.recording.RecordingService.EXTRA_RECORDING_ID, id)
                        }
                    )
                } else {
                    androidx.work.WorkManager.getInstance(this@TvModeActivity).enqueue(
                        androidx.work.OneTimeWorkRequestBuilder<com.orbital.iptv.recording.RecordingWorker>()
                            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                            .setInputData(androidx.work.workDataOf("recording_id" to id))
                            .addTag("rec_$id")
                            .build()
                    )
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TvModeActivity, "RECORDING SCHEDULED: $title", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TvModeActivity, "SCHEDULE FAILED: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showExitDialog() {
        showTvDialog(AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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
        showTvDialog(AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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
        showTvDialog(AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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
        showTvDialog(AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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
        showTvDialog(AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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
        val items = mutableListOf(
            Item("MOVIES")                         { startActivity(Intent(this, VodActivity::class.java)) },
            Item("SERIES")                         { startActivity(Intent(this, SeriesActivity::class.java)) },
            Item("CATCHUP")                        { startActivity(Intent(this, CatchupActivity::class.java)) },
            Item("CONTINUE WATCHING / FAVOURITES") { startActivity(Intent(this, FavouritesActivity::class.java)) },
            Item("⏺ RECORDINGS")                    { startActivity(Intent(this, com.orbital.iptv.recording.RecordingsActivity::class.java)) }
        )
        if (com.orbital.iptv.utils.TorboxPrefsManager.getApiKey(this) != null)
            items += Item("TORBOX") { startActivity(Intent(this, com.orbital.iptv.ui.torbox.TorboxBrowserActivity::class.java)) }
        showTvDialog(AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
            .setTitle("BOX OFFICE")
            .setItems(items.map { it.label }.toTypedArray()) { _, which -> items[which].action() })
    }

    private fun showRadioMenu() {
        val stations = RadioStations.load(this)
        if (stations.isEmpty()) {
            Toast.makeText(this, "NO RADIO STATIONS FOUND", Toast.LENGTH_SHORT).show()
            return
        }
        showTvDialog(AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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
        items += Item("TORBOX") { showTorboxKeyDialog() }
        showTvDialog(AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
            .setTitle("INTERACTIVE")
            .setItems(items.map { it.label }.toTypedArray()) { _, which -> items[which].action() })
    }

    private fun showTorboxKeyDialog() {
        val current = com.orbital.iptv.utils.TorboxPrefsManager.getApiKey(this) ?: ""
        val et = android.widget.EditText(this).apply {
            setText(current)
            hint = "PASTE TORBOX API KEY HERE"
            setPadding(48, 24, 48, 24)
        }
        val builder = AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
            .setTitle("TORBOX API KEY")
            .setMessage("Get your key at torbox.app → Settings → API")
            .setView(et)
            .setPositiveButton("SAVE") { _, _ ->
                val key = et.text.toString().trim()
                if (key.isNotBlank()) {
                    com.orbital.iptv.utils.TorboxPrefsManager.saveApiKey(this, key)
                    Toast.makeText(this, "TORBOX KEY SAVED", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL", null)
        if (current.isNotBlank()) {
            builder.setNeutralButton("REMOVE KEY") { _, _ ->
                com.orbital.iptv.utils.TorboxPrefsManager.clear(this)
                Toast.makeText(this, "TORBOX KEY REMOVED", Toast.LENGTH_SHORT).show()
            }
        }
        showTvDialog(builder)
    }

    private fun showTvSettingsMenu() {
        val serverCount   = PrefsManager.getProfiles(this).size
        val activeProfile = PrefsManager.getActiveProfile(this)
        val serverName    = activeProfile?.name?.uppercase() ?: "SERVER"

        data class Item(val label: String, val action: () -> Unit)
        val items = mutableListOf<Item>()
        items += Item("SERVERS ($serverCount SAVED)")  { showServerManager() }
        items += Item("↺  REFRESH $serverName")        { refreshServer() }
        items += Item("↺  FULL EPG REFRESH")           { confirmFullEpgRefresh() }
        items += Item("MANAGE VISIBLE CATEGORIES")     { showManageCategoriesDialog() }
        items += Item("THEME: ${ThemeManager.current.label}") { showThemePicker() }
        items += Item("TRANSPARENCY: ${PrefsManager.getTvPanelTransparency(this)}%") { showTransparencyPicker() }
        items += Item("TV MODE (TESTING): ${if (PrefsManager.isTvModeEnabled(this)) "ON" else "OFF"}") { toggleTvMode() }
        items += Item("PICTURE IN PICTURE: ${if (PrefsManager.isPipEnabled(this)) "ON" else "OFF"}") {
            PrefsManager.setPipEnabled(this, !PrefsManager.isPipEnabled(this))
        }
        items += Item(if (PrefsManager.getOpenSubsApiKey(this) != null) "OPENSUBTITLES: KEY SET" else "OPENSUBTITLES: NO KEY SET") {
            showOpenSubsKeyDialog()
        }
        items += Item(if (PrefsManager.getTmdbApiKey(this) != null) "TMDB: KEY SET" else "TMDB: NO KEY SET") {
            showTmdbKeyDialog()
        }
        items += Item("LIVE STREAM FORMAT: ${PrefsManager.getLiveFormat(this).uppercase()}") { toggleLiveFormat() }
        items += Item("PIN PROTECTED CATEGORIES")       { showPinProtectedCategories() }
        items += Item("CHANGE PIN")                    { showChangePinDialog() }
        items += Item("CHECK FOR UPDATES")             { checkForUpdatesManually() }
        items += Item("CLEAR ALL SAVED DATA")          { confirmClearAllData() }
        val versionName = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        items += Item("ORBITAL  v$versionName")        { }

        showTvDialog(AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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

        showTvDialog(AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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
        showTvDialog(AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
            .setTitle("REMOVE SERVER")
            .setItems(labels) { _, which ->
                val toDelete = profiles[which]
                showTvDialog(AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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

    /**
     * EPG listings are cached on disk per channel for 24h (see EpgCache) so switching
     * categories/channels doesn't re-hit the server every time. This clears that cache and
     * reloads whatever's currently on screen, forcing a fresh 7-day pull without touching the
     * channel/VOD/series catalog — that's the heavier "REFRESH SERVER" above.
     */
    private fun confirmFullEpgRefresh() {
        val name = PrefsManager.getActiveProfile(this)?.name?.uppercase() ?: "SERVER"
        showTvDialog(AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
            .setTitle("FULL EPG REFRESH")
            .setMessage("Clear the cached guide data for $name and re-download the 7-day EPG. Continue?")
            .setPositiveButton("REFRESH") { _, _ ->
                lifecycleScope.launch {
                    EpgCache.clearAll(this@TvModeActivity)
                    Toast.makeText(this@TvModeActivity, "EPG REFRESHING…", Toast.LENGTH_SHORT).show()
                    if (currentStreamId >= 0) loadInlineEpg(currentStreamId)
                    if (binding.guideOverlay.visibility == View.VISIBLE) {
                        loadGuideEpg(guideCategoryId) {
                            Toast.makeText(this@TvModeActivity, "EPG REFRESH COMPLETE", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this@TvModeActivity, "EPG REFRESH COMPLETE", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("CANCEL", null))
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

        showTvDialog(AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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
        showTvDialog(AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
            .setTitle("SELECT THEME")
            .setItems(labels) { _, which ->
                ThemeManager.set(this, themes[which])
                recreate()
            })
    }

    /** How see-through the left nav panel / HUD / ticker overlays are — 0% is solid (the
     *  original look), higher percentages let more of the live video show through behind them. */
    private fun showTransparencyPicker() {
        val options = intArrayOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90)
        val current = PrefsManager.getTvPanelTransparency(this)
        val labels = options.map { pct ->
            if (pct == current) "●  $pct%" else "○  $pct%"
        }.toTypedArray()
        showTvDialog(AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
            .setTitle("PANEL TRANSPARENCY")
            .setItems(labels) { _, which ->
                PrefsManager.setTvPanelTransparency(this, options[which])
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
        val builder = AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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

    private fun showTmdbKeyDialog() {
        val current = PrefsManager.getTmdbApiKey(this) ?: ""
        val et = android.widget.EditText(this).apply {
            setText(current)
            hint = "PASTE API KEY HERE"
            setPadding(48, 24, 48, 24)
        }
        val builder = AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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
        showTvDialog(AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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

        val dialog = AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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
        showTvDialog(AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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
            currentCategoryId == FAV_CATEGORY_ID -> favouriteChannelsList()
            currentCategoryId.isBlank() -> TvModeHolder.allChannels
            else -> TvModeHolder.allChannels.filter { it.categoryId == currentCategoryId }
        }
    }

    /** Recently-watched channels (most recent first) ahead of the rest of the manually-favourited
     *  channels, deduplicated — shown together under the ★ FAVOURITES category. */
    private fun favouriteChannelsList(): List<LiveStream> {
        val favIds = FavouritesManager.getLiveChannels(this).map { it.streamId }.toSet()
        val recentIds = com.orbital.iptv.utils.RecentChannelsManager.getAll(this).map { it.streamId }
        val recentChannels = recentIds.mapNotNull { id -> TvModeHolder.allChannels.find { it.streamId == id } }
        val favChannels = TvModeHolder.allChannels.filter { it.streamId in favIds && it.streamId !in recentIds }
        return recentChannels + favChannels
    }

    private fun loadChannelsInBackground(onLoaded: (() -> Unit)? = null) {
        val creds = PrefsManager.getCredentials(this) ?: return
        lifecycleScope.launch {
            // Serve the on-disk cache immediately so the panel isn't blank on a cold start.
            // HomeActivity writes this cache after every successful load; TvModeActivity now
            // does the same (see below), so the cache stays warm across sessions.
            if (TvModeHolder.allChannels.isEmpty()) {
                val cached = withContext(Dispatchers.IO) {
                    ContentCache.getLiveStreams(this@TvModeActivity, creds.serverUrl)
                }
                if (cached != null) {
                    TvModeHolder.allChannels = cached
                    refreshCategoryChannels()
                }
            }

            // Categories are a tiny payload — fetch first so the category panel populates fast
            val cats = withContext(Dispatchers.IO) {
                repository.getLiveCategories(creds.serverUrl, creds.username, creds.password).getOrNull()
            }
            if (cats != null) TvModeHolder.categories = cats

            // Full stream list can be several MB on large servers — stream straight to disk
            // rather than through Retrofit/Gson (avoids OOM on low-heap TV devices), then
            // read it back with the memory-cheap streaming JsonReader.
            withContext(Dispatchers.IO) {
                ContentCache.downloadAndSaveLiveStreams(this@TvModeActivity, creds.serverUrl, creds.username, creds.password)
            }
            val streams = withContext(Dispatchers.IO) {
                ContentCache.getLiveStreams(this@TvModeActivity, creds.serverUrl)
            }
            if (streams != null) {
                TvModeHolder.allChannels = streams
            }
            refreshCategoryChannels()
            onLoaded?.invoke()
        }
    }

    // ── Key dispatch ──────────────────────────────────────────────────────────
    //
    // Navigation summary:
    //   NONE        + LEFT   → CHANNELS panel (channel list + inline EPG column)
    //   NONE        + RIGHT  → previous channel (same as UP)
    //   CHANNELS    + LEFT   → CATEGORIES panel
    //   CHANNELS    + RIGHT  → close panel (NONE)
    //   CATEGORIES  + LEFT   → MAIN_MENU panel
    //   CATEGORIES  + RIGHT  → CHANNELS panel (current category)
    //   MAIN_MENU   + RIGHT  → CATEGORIES panel
    //   BACK (any)           → close panel / exit dialog

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (binding.guideOverlay.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.repeatCount > 0) return true
                hideGuideOverlay(); return true
            }
            // All other keys go to whichever child (EpgView or category list) has focus —
            // both already implement their own D-pad navigation.
            return super.dispatchKeyEvent(event)
        }
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
                        PanelState.CHANNELS   -> {
                            // Focus in the inline EPG column steps back to the channel list first;
                            // only from the channel list itself does LEFT drill out to CATEGORIES.
                            if (isFocusWithin(binding.rvChannelEpg)) focusChannelColumn() else showCategoryPanel()
                            return true
                        }
                        PanelState.CATEGORIES -> { showMainMenuPanel(); return true }
                        PanelState.MAIN_MENU  -> return true  // already at leftmost — swallow
                    }
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    when (panelState) {
                        PanelState.CHANNELS   -> {
                            // From the channel list, RIGHT steps into that channel's inline EPG
                            // column; from there (now the rightmost content), RIGHT closes the
                            // panel — same spot in the flow that used to close it from the channel list.
                            if (isFocusWithin(binding.rvPanelChannels)) focusEpgColumn() else hidePanel()
                            return true
                        }
                        PanelState.CATEGORIES -> { showChannelPanel(); return true }
                        PanelState.MAIN_MENU  -> { showCategoryPanel(); return true }
                        PanelState.NONE       -> { switchToPrevChannel(); return true }
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
        prevStreamId    = currentStreamId
        prevStreamUrl   = currentStreamUrl
        prevChannelName = currentChannelName
        prevCategoryId  = currentCategoryId
        currentStreamId    = stream.streamId
        currentStreamUrl   = url
        currentChannelName = stream.name
        PrefsManager.setLastTvChannel(this, url, stream.name, stream.streamId, currentCategoryId)
        player?.let { switchStream(it, url) }
        showZapBar(stream.name)
        recordRecentChannel(stream.streamId, stream.name, url)
    }

    private fun switchToPrevChannel() {
        if (prevStreamUrl.isBlank()) return
        val newUrl  = prevStreamUrl;  val newName  = prevChannelName
        val newId   = prevStreamId;   val newCatId = prevCategoryId
        prevStreamUrl   = currentStreamUrl;  prevChannelName = currentChannelName
        prevStreamId    = currentStreamId;   prevCategoryId  = currentCategoryId
        currentStreamUrl   = newUrl;  currentChannelName = newName
        currentStreamId    = newId;   currentCategoryId  = newCatId
        PrefsManager.setLastTvChannel(this, newUrl, newName, newId, newCatId)
        player?.let { switchStream(it, newUrl) }
        showZapBar(newName)
        recordRecentChannel(newId, newName, newUrl)
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
        guideLoadJob?.cancel()
        inlineEpgHandler.removeCallbacksAndMessages(null)
        hudHandler.removeCallbacksAndMessages(null)
        tickerHandler.removeCallbacksAndMessages(null)
        newsHandler.removeCallbacksAndMessages(null)
        tvRetryHandler.removeCallbacksAndMessages(null)
        stallWatchdog.stop()
        tickerScrollAnim?.cancel()
        binding.tvNewsTicker.stop()
        player?.removeListener(tvPlayerListener)
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
    private val alpha: Int = 255,
    private val onFocus: ((Int) -> Unit)? = null,
    private val onSelect: (Int) -> Unit
) : RecyclerView.Adapter<NowNextAdapter.VH>() {

    inner class VH(root: LinearLayout, val chanTv: TextView) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val d = parent.resources.displayMetrics.density
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * d).toInt())
            isFocusable = true; isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val chanTv = TextView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * d).toInt(), 0, (8 * d).toInt(), 0)
            textSize = 11f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 2
        }
        root.addView(chanTv)
        return VH(root, chanTv)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item      = items[position]
        val p         = ThemeManager.palette()
        val d         = holder.itemView.resources.displayMetrics.density
        val isPlaying = item.streamId == currentId
        val normalBg  = if (isPlaying) p.highlight
                         else ThemeManager.withAlpha(if (position % 2 == 0) p.rowEven else p.rowOdd, alpha)

        holder.chanTv.text = item.channelName
        holder.chanTv.setTextColor(if (isPlaying) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())

        holder.itemView.background = ThemeManager.roundedBg(normalBg, d)
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (!isPlaying) holder.itemView.background = ThemeManager.roundedBg(if (hasFocus) p.focus else normalBg, d)
            if (hasFocus) onFocus?.invoke(item.streamId)
        }
        holder.itemView.setOnClickListener { onSelect(item.streamId) }
    }

    /** Update the currently-playing highlight without closing the panel. */
    fun setCurrentId(id: Int) {
        val oldId = currentId
        currentId = id
        // notifyDataSetChanged() would detach/reattach every row during layout, which clears
        // Android focus from whichever item the user is on — breaking further D-pad UP/DOWN
        // navigation until the panel is closed and reopened. Rebind only the two affected rows.
        val oldPos = items.indexOfFirst { it.streamId == oldId }
        val newPos = items.indexOfFirst { it.streamId == id }
        if (oldPos >= 0) notifyItemChanged(oldPos)
        if (newPos >= 0) notifyItemChanged(newPos)
    }
}

// ── Category panel adapter ────────────────────────────────────────────────────

class CategoryPanelAdapter(
    private val items: List<LiveCategory>,
    private val currentCategoryId: String,
    private val countMap: Map<String, Int>,
    private val alpha: Int = 255,
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
        val normalBg  = if (isCurrent) p.highlight
                         else ThemeManager.withAlpha(if (position % 2 == 0) p.rowEven else p.rowOdd, alpha)

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

// ── Full EPG list adapter ─────────────────────────────────────────────────────

class EpgListAdapter(
    private val items: List<EpgListing>,
    private val currentIdx: Int,
    private val nowSec: Long,
    private val alpha: Int = 255,
    private val onSelect: ((EpgListing, Boolean) -> Unit)? = null
) : RecyclerView.Adapter<EpgListAdapter.VH>() {

    inner class VH(val timeTv: TextView, val titleTv: TextView, root: LinearLayout)
        : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val d = parent.resources.displayMetrics.density
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * d).toInt())
            isFocusable = true; isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val timeTv = TextView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams((58 * d).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(0, 0, (10 * d).toInt(), 0)
            textSize = 11f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        val titleTv = TextView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
            textSize = 11f
            typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            maxLines = 2
        }
        root.addView(timeTv)
        root.addView(titleTv)
        return VH(timeTv, titleTv, root)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item      = items[position]
        val p         = ThemeManager.palette()
        val d         = holder.itemView.resources.displayMetrics.density
        val isCurrent = position == currentIdx
        val isPast    = (item.stopTimestamp?.toLongOrNull() ?: Long.MAX_VALUE) < nowSec

        val startSec = item.startTimestamp?.toLongOrNull()
        if (startSec != null) {
            val cal = Calendar.getInstance().apply { timeInMillis = startSec * 1000 }
            holder.timeTv.text = "%02d:%02d".format(
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
            )
        } else {
            holder.timeTv.text = "--:--"
        }

        holder.titleTv.text = item.getDecodedTitle().ifBlank { "—" }

        val normalBg = when {
            isCurrent -> p.highlight
            isPast    -> ThemeManager.withAlpha(
                if (position % 2 == 0) ThemeManager.dim(p.rowEven) else ThemeManager.dim(p.rowOdd), alpha
            )
            else      -> ThemeManager.withAlpha(if (position % 2 == 0) p.rowEven else p.rowOdd, alpha)
        }
        holder.timeTv.setTextColor(when {
            isCurrent -> 0xFF000000.toInt()
            isPast    -> 0xFF808080.toInt()
            else      -> p.accent
        })
        holder.titleTv.setTextColor(when {
            isCurrent -> 0xFF000000.toInt()
            isPast    -> 0xFF999999.toInt()
            else      -> 0xFFEEEEEE.toInt()
        })
        holder.itemView.background = ThemeManager.roundedBg(normalBg, d)
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (!isCurrent) holder.itemView.background = ThemeManager.roundedBg(
                if (hasFocus) p.focus else normalBg, d
            )
        }
        holder.itemView.setOnClickListener { onSelect?.invoke(item, isCurrent) }
    }
}

// ── TV-friendly RecyclerView LayoutManager ────────────────────────────────────

private class TvLayoutManager(ctx: android.content.Context) : LinearLayoutManager(ctx) {
    override fun onRequestChildFocus(
        parent: RecyclerView, state: RecyclerView.State,
        child: android.view.View, focused: android.view.View?
    ): Boolean {
        val pos           = getPosition(child)
        val firstComplete = findFirstCompletelyVisibleItemPosition()
        val lastComplete  = findLastCompletelyVisibleItemPosition()
        when {
            pos < firstComplete -> {
                val dy = child.top
                if (dy < 0) parent.scrollBy(0, dy)
            }
            pos > lastComplete -> {
                val dy = child.bottom - parent.height
                if (dy > 0) parent.scrollBy(0, dy)
            }
        }
        return true
    }
}
