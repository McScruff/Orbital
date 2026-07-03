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
import com.orbital.iptv.data.model.EpgListing
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
    }

    /**
     * Fully tears down the current playlist/decoders before loading [url]. A seamless
     * setMediaItem() while playing can leave the video renderer holding its last decoded
     * frame (stale picture) while the audio renderer moves on to the new stream — stop()
     * alone doesn't release decoders, so clearMediaItems() is required to force ExoPlayer
     * to rebuild fresh MediaCodec instances on the next prepare().
     */
    private fun switchStream(exo: ExoPlayer, url: String) {
        exo.stop()
        exo.clearMediaItems()
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.play()
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
        binding.menuItemGuide.tag       = 0xFF0D1E38.toInt()
        binding.menuItemBoxOffice.tag   = 0xFF0A1628.toInt()
        binding.menuItemRadio.tag       = 0xFF0D1E38.toInt()
        binding.menuItemInteractive.tag = 0xFF0A1628.toInt()
        binding.menuItemSettings.tag    = 0xFF0D1E38.toInt()

        menuItem(binding.menuItemLiveTv)      { hidePanel() }
        menuItem(binding.menuItemGuide)       { showGuideOverlay() }
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
            binding.rvChannelEpg.layoutManager = LinearLayoutManager(this@TvModeActivity)
            binding.rvChannelEpg.itemAnimator  = null
            binding.rvChannelEpg.adapter       = EpgListAdapter(sorted, currentIdx, nowSec)
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

    private fun loadChannelPanel(channels: List<LiveStream>) {
        val items = channels.map { ch -> NowNextItem(ch.streamId, ch.name) }.toMutableList()

        channelPanelAdapter = NowNextAdapter(items, currentStreamId, onFocus = { id ->
            focusedChannelStreamId = id
            scheduleInlineEpg(id)
        }) { streamId ->
            val ch    = channels.find { it.streamId == streamId } ?: return@NowNextAdapter
            val creds = PrefsManager.getCredentials(this) ?: return@NowNextAdapter
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

    private fun loadGuideEpg(categoryId: String) {
        guideLoadJob?.cancel()
        val creds = PrefsManager.getCredentials(this) ?: return

        val channels = when {
            categoryId == FAV_CATEGORY_ID -> {
                val favIds = FavouritesManager.getLiveChannels(this).map { it.streamId }.toSet()
                TvModeHolder.allChannels.filter { it.streamId in favIds }
            }
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
        binding.epgViewGuide.onProgrammeSelected = { streamId, _, _ -> tuneFromGuide(streamId) }

        guideLoadJob = lifecycleScope.launch {
            rows.forEach { row ->
                launch {
                    val cached = withContext(Dispatchers.IO) {
                        EpgCache.get(this@TvModeActivity, row.streamId, minCount = 50)
                    }
                    val listings = cached ?: withContext(Dispatchers.IO) {
                        repository.getFullChannelEpg(
                            creds.serverUrl, creds.username, creds.password, row.streamId
                        ).getOrNull()?.listings?.also { l ->
                            EpgCache.put(this@TvModeActivity, row.streamId, l)
                        } ?: emptyList()
                    }
                    binding.epgViewGuide.updateRow(row.streamId, listings)
                }
            }
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

    private fun showTmdbKeyDialog() {
        val current = PrefsManager.getTmdbApiKey(this) ?: ""
        val et = android.widget.EditText(this).apply {
            setText(current)
            hint = "PASTE API KEY HERE"
            setPadding(48, 24, 48, 24)
        }
        val builder = AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
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
        tickerScrollAnim?.cancel()
        binding.tvNewsTicker.stop()
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
        val normalBg  = if (isPlaying) p.highlight else if (position % 2 == 0) 0xFF0A1628.toInt() else 0xFF0D1E38.toInt()

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

// ── Full EPG list adapter ─────────────────────────────────────────────────────

class EpgListAdapter(
    private val items: List<EpgListing>,
    private val currentIdx: Int,
    private val nowSec: Long
) : RecyclerView.Adapter<EpgListAdapter.VH>() {

    inner class VH(val timeTv: TextView, val titleTv: TextView, root: LinearLayout)
        : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val d = parent.resources.displayMetrics.density
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * d).toInt())
            isFocusable = true
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
            isPast    -> if (position % 2 == 0) 0xFF060C06.toInt() else 0xFF090F09.toInt()
            else      -> if (position % 2 == 0) 0xFF0A1628.toInt() else 0xFF0D1E38.toInt()
        }
        holder.timeTv.setTextColor(when {
            isCurrent -> 0xFF000000.toInt()
            isPast    -> 0xFF446644.toInt()
            else      -> 0xFF00CCCC.toInt()
        })
        holder.titleTv.setTextColor(when {
            isCurrent -> 0xFF000000.toInt()
            isPast    -> 0xFF558855.toInt()
            else      -> 0xFFEEEEEE.toInt()
        })
        holder.itemView.background = ThemeManager.roundedBg(normalBg, d)
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (!isCurrent) holder.itemView.background = ThemeManager.roundedBg(
                if (hasFocus) p.focus else normalBg, d
            )
        }
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
