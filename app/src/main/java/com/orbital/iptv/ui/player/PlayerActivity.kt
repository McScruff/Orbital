package com.orbital.iptv.ui.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.animation.LinearInterpolator
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.orbital.iptv.R
import com.orbital.iptv.data.model.FavType
import com.orbital.iptv.data.model.FavouriteItem
import com.orbital.iptv.data.model.getDecodedTitle
import com.orbital.iptv.data.repository.XtreamRepository
import com.orbital.iptv.databinding.ActivityPlayerBinding
import com.orbital.iptv.data.emby.EmbyRepository
import com.orbital.iptv.data.plex.PlexRepository
import com.orbital.iptv.recording.RecordingRepository
import com.orbital.iptv.recording.RecordingService
import com.orbital.iptv.recording.RecordingState
import com.orbital.iptv.utils.ChannelQueue
import com.orbital.iptv.utils.EpgCache
import com.orbital.iptv.utils.EmbyPrefsManager
import com.orbital.iptv.utils.PlexPrefsManager
import com.orbital.iptv.utils.FavouritesManager
import com.orbital.iptv.utils.PlayerEngine
import com.orbital.iptv.utils.PrefsManager
import com.orbital.iptv.utils.TickerManager
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VLCMediaPlayer
import kotlinx.coroutines.Dispatchers
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL     = "stream_url"
        const val EXTRA_CHANNEL_NAME   = "channel_name"
        const val EXTRA_STREAM_ID      = "stream_id"
        const val EXTRA_IS_LIVE        = "is_live"
        const val EXTRA_CHANNEL_NUM    = "channel_num"
        const val EXTRA_FAV_ID         = "fav_id"
        const val EXTRA_ART_URL        = "art_url"
        const val EXTRA_RESUME_MS      = "resume_ms"
        const val EXTRA_SERIES_ID      = "series_id"
        const val EXTRA_SEASON         = "season"
        const val EXTRA_EPISODE_NUM    = "episode_num"
        const val EXTRA_EPISODE_ID     = "episode_id"
        const val EXTRA_NEXT_EP_URL    = "next_ep_url"
        const val EXTRA_NEXT_EP_TITLE  = "next_ep_title"
        const val EXTRA_NEXT_EP_NUM    = "next_ep_num"
        const val EXTRA_NEXT_EP_SEASON = "next_ep_season"
        const val EXTRA_NEXT_EP_ID     = "next_ep_id"
        const val EXTRA_EMBY_ITEM_ID    = "emby_item_id"
        const val EXTRA_PLEX_RATING_KEY = "plex_rating_key"
        const val EXTRA_PLEX_DURATION_MS = "plex_duration_ms"
        const val EXTRA_SUBTITLE_PATH   = "subtitle_path"

        private const val OVERLAY_HIDE_DELAY_MS  = 5000L
        private const val SEEK_STEP_MS           = 30_000L
        private const val SEEK_COMMIT_DELAY_MS   = 400L
        private const val SEEK_INDICATOR_HIDE_MS = 1200L
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

        private val COLOR_BUFFERING = 0xFFFFCC00.toInt()
        private val COLOR_PLAYING   = 0xFF44CC44.toInt()
        private val COLOR_PAUSED    = 0xFF888888.toInt()
        private val COLOR_ERROR     = 0xFFFF4444.toInt()
        private val COLOR_WARNING   = 0xFFFF8800.toInt()
        private val COLOR_LIVE      = 0xFFFF2222.toInt()
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var player: ExoPlayer

    private var streamUrl: String = ""
    private var channelName: String = ""
    private var streamId: Int = -1
    private var isLive: Boolean = true
    private val repository = XtreamRepository()

    private var favId: String = ""
    private var artUrl: String = ""
    private var resumeMs: Long = 0L
    private var seriesId: Int = -1
    private var season: String = ""
    private var episodeNum: Int = 0
    private var episodeId: String = ""
    private var nextEpUrl: String = ""
    private var nextEpTitle: String = ""
    private var nextEpNum: Int = 0
    private var nextEpSeason: String = ""
    private var nextEpId: String = ""
    private var episodeCompleted = false
    private var hasError = false
    private var audioRecoveryAttempted = false
    private var embyItemId = ""
    private val embyRepo = EmbyRepository()
    private var plexRatingKey = ""
    private var plexDurationMs = 0L
    private val plexRepo = PlexRepository()
    private var enteringPip = false
    private var subtitlePath = ""

    private var currentEngine = PlayerEngine.MPV

    // MPV
    private var mpvReady = false
    private var mpvStartedPlayingOnce = false
    private var mpvLoadStartMs = 0L
    private val mpvHandler = Handler(Looper.getMainLooper())
    private val mpvPollRunnable = object : Runnable {
        override fun run() {
            updateMpvState()
            mpvHandler.postDelayed(this, 500L)
        }
    }

    private val overlayHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideOverlay() }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 30_000L)
        }
    }

    private val positionHandler = Handler(Looper.getMainLooper())
    private val positionRunnable = object : Runnable {
        override fun run() {
            updatePositionDisplay()
            positionHandler.postDelayed(this, 1000L)
        }
    }

    private val seekHandler = Handler(Looper.getMainLooper())
    private var pendingSeekTargetMs: Long = -1L
    private val commitSeekRunnable = Runnable { commitPendingSeek() }
    private val hideSeekIndicatorRunnable = Runnable { binding.seekIndicator.visibility = View.GONE }

    private val tickerHttp = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private val tickerHandler = Handler(Looper.getMainLooper())
    private val tickerRunnable = object : Runnable {
        override fun run() {
            fetchTickerScores()
            val hasLive = TickerManager.liveScores.any { it.state == "in" }
            tickerHandler.postDelayed(this, if (hasLive) 30_000L else 60_000L)
        }
    }

    private val newsHandler = Handler(Looper.getMainLooper())
    private val newsRunnable = object : Runnable {
        override fun run() {
            fetchNewsHeadlines()
            newsHandler.postDelayed(this, 300_000L)
        }
    }

    // VLC
    private var vlcLibrary: LibVLC? = null
    private var vlcPlayer: VLCMediaPlayer? = null

    private var pendingTickerText: String? = null
    private var pendingNewsText: String? = null
    private var tickerScrollAnim: ValueAnimator? = null
    private var newsScrollAnim: ValueAnimator? = null
    private var tickerShowingPlaceholder = false
    private var newsShowingPlaceholder = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        streamUrl    = intent.getStringExtra(EXTRA_STREAM_URL) ?: ""
        channelName  = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: "UNKNOWN"
        streamId     = intent.getIntExtra(EXTRA_STREAM_ID, -1)
        isLive       = intent.getBooleanExtra(EXTRA_IS_LIVE, true)
        val channelNum = intent.getIntExtra(EXTRA_CHANNEL_NUM, -1)
        favId        = intent.getStringExtra(EXTRA_FAV_ID) ?: ""
        artUrl       = intent.getStringExtra(EXTRA_ART_URL) ?: ""
        resumeMs     = intent.getLongExtra(EXTRA_RESUME_MS, 0L)
        seriesId     = intent.getIntExtra(EXTRA_SERIES_ID, -1)
        season       = intent.getStringExtra(EXTRA_SEASON) ?: ""
        episodeNum   = intent.getIntExtra(EXTRA_EPISODE_NUM, 0)
        episodeId    = intent.getStringExtra(EXTRA_EPISODE_ID) ?: ""
        nextEpUrl    = intent.getStringExtra(EXTRA_NEXT_EP_URL) ?: ""
        nextEpTitle  = intent.getStringExtra(EXTRA_NEXT_EP_TITLE) ?: ""
        nextEpNum    = intent.getIntExtra(EXTRA_NEXT_EP_NUM, 0)
        nextEpSeason = intent.getStringExtra(EXTRA_NEXT_EP_SEASON) ?: ""
        nextEpId     = intent.getStringExtra(EXTRA_NEXT_EP_ID) ?: ""
        embyItemId      = intent.getStringExtra(EXTRA_EMBY_ITEM_ID) ?: ""
        plexRatingKey   = intent.getStringExtra(EXTRA_PLEX_RATING_KEY) ?: ""
        plexDurationMs  = intent.getLongExtra(EXTRA_PLEX_DURATION_MS, 0L)
        subtitlePath    = intent.getStringExtra(EXTRA_SUBTITLE_PATH) ?: ""

        currentEngine = when {
            isLive       -> PrefsManager.getLivePlayer(this)
            seriesId >= 0 -> PrefsManager.getSeriesPlayer(this)
            else          -> PrefsManager.getMoviePlayer(this)
        }

        binding.tvPlayerTitle.text = channelName.uppercase()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAudio.setOnClickListener { showAudioPicker() }
        binding.btnSubs.setOnClickListener { showSubtitlePicker() }
        binding.btnScores.setOnClickListener { toggleTicker() }
        binding.btnNews.setOnClickListener { toggleNewsTicker() }
        binding.btnOpenIn.setOnClickListener { launchExternalPlayer() }
        binding.root.setOnClickListener {
            if (hasError) {
                hasError = false
                when {
                    mpvReady -> {
                        mpvStartedPlayingOnce = false
                        mpvLoadStartMs = System.currentTimeMillis()
                        binding.mpvView.loadUrl(streamUrl, 0L)
                        binding.progressBar.visibility = View.VISIBLE
                        setStatus("CONNECTING...", COLOR_BUFFERING)
                    }
                    vlcPlayer != null -> retryVlc()
                    else -> playMedia()
                }
            } else {
                showOverlay()
            }
        }

        updateScoresButton()
        if (TickerManager.tickerEnabled) startTicker()
        updateNewsButton()
        if (TickerManager.newsTickerEnabled) startNewsTicker()

        if (!isLive) {
            binding.btnSeekBack.setOnClickListener { seekBy(-SEEK_STEP_MS) }
            binding.btnSeekFwd.setOnClickListener  { seekBy(SEEK_STEP_MS) }
            binding.btnPause.setOnClickListener    { togglePause() }
            binding.vodSeekRow.visibility  = View.VISIBLE
            binding.vodProgress.visibility = View.VISIBLE
        }

        if (isLive) {
            val displayName = if (channelNum > 0) "$channelNum  ${channelName.uppercase()}"
                              else channelName.uppercase()
            binding.tvChannelInfo.text = displayName
            val p2 = (2 * resources.displayMetrics.density).toInt()
            binding.bottomBar.setBackgroundColor(0xFFE8890A.toInt())
            binding.bottomBar.setPadding(p2, p2, p2, p2)
            binding.liveNowRow.visibility = View.VISIBLE
            binding.liveNextRow.visibility = View.VISIBLE
            binding.tvClock.visibility = View.VISIBLE
            updateClock()
        } else {
            binding.tvChannelInfo.text = channelName
            binding.tvClock.visibility = View.GONE
            binding.liveNowRow.visibility = View.GONE
            binding.liveNextRow.visibility = View.GONE
        }

        enterFullscreen()
        showOverlay()
        initPlayer()
        if (isLive) loadEpg()
        if (embyItemId.isNotEmpty()) reportEmbyStart()
        if (plexRatingKey.isNotEmpty()) reportPlexStart()

        if (isLive) {
            binding.btnRecord.visibility = View.VISIBLE
            binding.btnRecord.setOnClickListener { onRecordButtonClicked() }
            binding.btnRecord.setOnFocusChangeListener { _, hasFocus ->
                // Bright outline when focused so remote users can see it's selected
                val baseColor = if (isRecordingThisChannel) 0xFFCC0000.toInt() else 0xFF8B0000.toInt()
                binding.btnRecord.setBackgroundColor(if (hasFocus) 0xFFFF4444.toInt() else baseColor)
                // Keep the overlay alive while navigating buttons
                if (hasFocus) {
                    overlayHandler.removeCallbacks(hideOverlayRunnable)
                    overlayHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS)
                }
            }
            updateRecordButton()
        }
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    // Local flag so button state is correct immediately (service sets RecordingState async)
    private var isRecordingThisChannel = false

    private fun updateRecordButton() {
        if (isRecordingThisChannel) {
            binding.btnRecord.text = "■ STOP REC"
            binding.btnRecord.setBackgroundColor(0xFFCC0000.toInt())
        } else {
            binding.btnRecord.text = "● REC"
            binding.btnRecord.setBackgroundColor(0xFF8B0000.toInt())
        }
    }

    private fun onRecordButtonClicked() {
        if (isRecordingThisChannel) stopRecordNow() else showRecordConfirmDialog()
        overlayHandler.removeCallbacks(hideOverlayRunnable)
        overlayHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS)
    }

    private fun showRecordConfirmDialog() {
        val availGb  = RecordingRepository.availableGb(this)
        val epgTitle = binding.tvNowTitle.text.toString().ifBlank { channelName }
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("● START RECORDING")
            .setMessage(
                "Channel: $channelName\n" +
                "Show: $epgTitle\n\n" +
                "Available storage: ${"%.1f".format(availGb)} GB\n\n" +
                "⚠ USES 2 CONNECTIONS\n" +
                "Recording opens a separate stream alongside the one already playing. " +
                "Your account must allow at least 2 simultaneous connections or the recording will fail.\n\n" +
                "Recording will continue until you press STOP REC."
            )
            .setPositiveButton("RECORD") { _, _ -> startRecordNow(epgTitle) }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun startRecordNow(epgTitle: String) {
        startForegroundService(Intent(this, RecordingService::class.java).apply {
            putExtra(RecordingService.EXTRA_CHANNEL_NAME,  channelName)
            putExtra(RecordingService.EXTRA_CHANNEL_URL,   streamUrl)
            putExtra(RecordingService.EXTRA_STREAM_ID,     streamId)
            putExtra(RecordingService.EXTRA_EPG_TITLE,     epgTitle)
            putExtra(RecordingService.EXTRA_SCHEDULED_END, 0L)
        })
        isRecordingThisChannel = true
        updateRecordButton()
    }

    private fun stopRecordNow() {
        startService(Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        })
        RecordingState.activeRecordNowUrl = null
        isRecordingThisChannel = false
        updateRecordButton()
    }

    private fun setStatus(text: String, color: Int) {
        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(color)
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> { finish(); return true }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (!isLive) { togglePause(); return true }
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                    if (!isLive) { seekBy(SEEK_STEP_MS); return true }
                }
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                    if (!isLive) { seekBy(-SEEK_STEP_MS); return true }
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!isLive && !isOverlayFocused()) { seekBy(-SEEK_STEP_MS); return true }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!isLive && !isOverlayFocused()) { seekBy(SEEK_STEP_MS); return true }
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (binding.hudOverlay.visibility != View.VISIBLE) {
                        if (isLive && ChannelQueue.entries.size > 1) { changeChannel(-1); return true }
                    } else if (isLive && currentFocus == binding.btnRecord) {
                        binding.btnBack.requestFocus()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (binding.hudOverlay.visibility != View.VISIBLE) {
                        if (isLive && ChannelQueue.entries.size > 1) { changeChannel(+1); return true }
                    } else if (isLive && binding.btnRecord.visibility == View.VISIBLE) {
                        if (binding.hudOverlay.findFocus() != null) {
                            binding.btnRecord.requestFocus()
                            return true
                        }
                    }
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BUTTON_SELECT -> {
                    if (binding.hudOverlay.visibility != View.VISIBLE) {
                        showOverlay(); return true
                    } else if (!isLive) {
                        val f = currentFocus
                        if (f == null || f == binding.root || f == binding.surfaceView) {
                            togglePause(); return true
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showOverlay() {
        binding.hudOverlay.visibility = View.VISIBLE
        binding.bottomBar.visibility = View.VISIBLE
        overlayHandler.removeCallbacks(hideOverlayRunnable)
        overlayHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS)
        if (!isLive) binding.btnPause.requestFocus() else binding.btnBack.requestFocus()
        if (isLive) {
            updateClock()
            clockHandler.removeCallbacks(clockRunnable)
            clockHandler.postDelayed(clockRunnable, 30_000L)
        }
        if (!isLive) updatePositionDisplay()
    }

    private fun hideOverlay() {
        binding.hudOverlay.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        clockHandler.removeCallbacks(clockRunnable)
    }

    private fun isOverlayFocused(): Boolean =
        binding.hudOverlay.findFocus() != null || binding.bottomBar.findFocus() != null

    private fun togglePause() {
        if (isLive) return
        when {
            mpvReady -> {
                val paused = binding.mpvView.isPaused()
                binding.mpvView.setPaused(!paused)
                showOverlay()
                if (!paused) overlayHandler.removeCallbacks(hideOverlayRunnable)
            }
            vlcPlayer != null -> {
                val vp = vlcPlayer!!
                if (vp.isPlaying) {
                    vp.pause(); showOverlay(); overlayHandler.removeCallbacks(hideOverlayRunnable)
                } else {
                    vp.play(); showOverlay()
                }
            }
            ::player.isInitialized -> {
                if (player.isPlaying) {
                    player.pause(); showOverlay(); overlayHandler.removeCallbacks(hideOverlayRunnable)
                } else {
                    player.play(); showOverlay()
                }
            }
        }
    }

    private fun updatePauseButton() {
        if (isLive) return
        val playing = when {
            mpvReady -> !binding.mpvView.isPaused()
            vlcPlayer != null -> vlcPlayer!!.isPlaying
            ::player.isInitialized -> player.isPlaying
            else -> false
        }
        binding.btnPause.text = if (playing) "▌▌  PAUSE" else "▶  RESUME"
    }

    private fun seekBy(deltaMs: Long) {
        val dur: Long
        val base: Long
        when {
            mpvReady -> {
                dur = binding.mpvView.getDurationMs().takeIf { it > 0L } ?: return
                base = if (pendingSeekTargetMs >= 0L) pendingSeekTargetMs else binding.mpvView.getCurrentPositionMs()
            }
            vlcPlayer != null -> {
                dur = vlcPlayer!!.length.takeIf { it > 0L } ?: return
                base = if (pendingSeekTargetMs >= 0L) pendingSeekTargetMs else vlcPlayer!!.time.coerceAtLeast(0L)
            }
            ::player.isInitialized -> {
                dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: return
                base = if (pendingSeekTargetMs >= 0L) pendingSeekTargetMs else player.currentPosition
            }
            else -> return
        }
        pendingSeekTargetMs = (base + deltaMs).coerceIn(0L, dur)

        binding.tvSeekDirection.text = if (deltaMs > 0) "▶▶" else "◀◀"
        binding.tvSeekTime.text = formatMs(pendingSeekTargetMs)
        binding.seekIndicator.visibility = View.VISIBLE
        seekHandler.removeCallbacks(hideSeekIndicatorRunnable)

        // Update progress/position only if the overlay is already visible
        if (binding.hudOverlay.visibility == View.VISIBLE) {
            binding.vodProgress.progress = ((pendingSeekTargetMs.toFloat() / dur) * 1000).toInt()
            binding.tvPosition.text = "${formatMs(pendingSeekTargetMs)} / ${formatMs(dur)}"
        }

        seekHandler.removeCallbacks(commitSeekRunnable)
        seekHandler.postDelayed(commitSeekRunnable, SEEK_COMMIT_DELAY_MS)
    }

    private fun commitPendingSeek() {
        if (pendingSeekTargetMs < 0L) return
        when {
            mpvReady -> binding.mpvView.seekTo(pendingSeekTargetMs)
            vlcPlayer != null -> vlcPlayer!!.time = pendingSeekTargetMs
            ::player.isInitialized -> player.seekTo(pendingSeekTargetMs)
        }
        pendingSeekTargetMs = -1L
        seekHandler.postDelayed(hideSeekIndicatorRunnable, SEEK_INDICATOR_HIDE_MS)
    }

    private fun updatePositionDisplay() {
        if (mpvReady) { updateMpvPositionDisplay(); return }
        if (vlcPlayer != null) { updateVlcPositionDisplay(); return }
        if (!::player.isInitialized) return
        val pos = player.currentPosition
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: return
        binding.tvPosition.text = "${formatMs(pos)} / ${formatMs(dur)}"
        binding.vodProgress.progress = ((pos.toFloat() / dur) * 1000).toInt()
    }

    private fun updateVlcPositionDisplay() {
        val vp = vlcPlayer ?: return
        if (pendingSeekTargetMs >= 0L) return
        val pos = vp.time.takeIf { it >= 0L } ?: return
        val dur = vp.length.takeIf { it > 0L } ?: return
        binding.tvPosition.text = "${formatMs(pos)} / ${formatMs(dur)}"
        binding.vodProgress.progress = ((pos.toFloat() / dur) * 1000).toInt()
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            when {
                ::player.isInitialized -> player.setVideoSurface(holder.surface)
                vlcPlayer != null && !(vlcPlayer!!.vlcVout.areViewsAttached()) -> {
                    val vout = vlcPlayer!!.vlcVout
                    vout.setVideoSurface(holder.surface, holder)
                    vout.attachViews { _, _, _, _, _, _, _ ->
                        runOnUiThread {
                            val w = binding.surfaceView.width
                            val h = binding.surfaceView.height
                            if (w > 0 && h > 0) vout.setWindowSize(w, h)
                        }
                    }
                    val w = binding.surfaceView.width
                    val h = binding.surfaceView.height
                    if (w > 0 && h > 0) vout.setWindowSize(w, h)
                    vlcPlayer!!.videoScale = VLCMediaPlayer.ScaleType.SURFACE_BEST_FIT
                }
            }
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (::player.isInitialized) player.clearVideoSurface()
            vlcPlayer?.vlcVout?.detachViews()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> runOnUiThread {
                    binding.progressBar.visibility = View.VISIBLE
                    setStatus("BUFFERING...", COLOR_BUFFERING)
                }
                Player.STATE_READY -> runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    updatePauseButton()
                    val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    val anySelected = audioGroups.any { it.isSelected }
                    if (!anySelected && audioGroups.isNotEmpty()) {
                        val codecs = audioGroups.joinToString(", ") {
                            it.getTrackFormat(0).sampleMimeType?.substringAfterLast('/') ?: "?"
                        }
                        setStatus("NO AUDIO — UNSUPPORTED FORMAT ($codecs)", COLOR_WARNING)
                        binding.hudOverlay.visibility = View.VISIBLE
                        binding.bottomBar.visibility = View.VISIBLE
                        overlayHandler.removeCallbacks(hideOverlayRunnable)
                    }
                }
                Player.STATE_ENDED -> runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    setStatus("FINISHED", COLOR_PLAYING)
                    positionHandler.removeCallbacks(positionRunnable)
                    if (!isLive && !episodeCompleted) {
                        episodeCompleted = true
                        when {
                            favId.isNotEmpty() -> {
                                handleCompletion()
                                showOverlay()
                                overlayHandler.removeCallbacks(hideOverlayRunnable)
                            }
                            embyItemId.isNotEmpty() -> markEmbyPlayedAndFinish()
                            plexRatingKey.isNotEmpty() -> markPlexPlayedAndFinish()
                            else -> {
                                showOverlay()
                                overlayHandler.removeCallbacks(hideOverlayRunnable)
                            }
                        }
                    } else if (!isLive) {
                        showOverlay()
                        overlayHandler.removeCallbacks(hideOverlayRunnable)
                    }
                }
                Player.STATE_IDLE -> runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            runOnUiThread {
                if (isPlaying) {
                    hasError = false
                    binding.progressBar.visibility = View.GONE
                    setStatus(if (isLive) "● LIVE" else "▶ PLAYING", if (isLive) COLOR_LIVE else COLOR_PLAYING)
                    if (!isLive) {
                        positionHandler.removeCallbacks(positionRunnable)
                        positionHandler.post(positionRunnable)
                    }
                } else {
                    if (::player.isInitialized && player.playbackState == Player.STATE_READY) {
                        setStatus("⏸ PAUSED", COLOR_PAUSED)
                        positionHandler.removeCallbacks(positionRunnable)
                    }
                }
                updatePauseButton()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                positionHandler.removeCallbacks(positionRunnable)

                val canRecoverAudio = !isLive && !audioRecoveryAttempted && (
                    error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                )

                if (canRecoverAudio) {
                    audioRecoveryAttempted = true
                    val savedPos = if (::player.isInitialized) player.currentPosition else 0L
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
                    player.prepare()
                    if (savedPos > 0L) player.seekTo(savedPos)
                    player.play()
                    setStatus("NO AUDIO — UNSUPPORTED FORMAT", COLOR_WARNING)
                    binding.hudOverlay.visibility = View.VISIBLE
                    binding.bottomBar.visibility = View.VISIBLE
                    overlayHandler.removeCallbacks(hideOverlayRunnable)
                    overlayHandler.postDelayed(hideOverlayRunnable, 8_000L)
                } else {
                    hasError = true
                    val reason = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "NO NETWORK"
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS           -> "STREAM OFFLINE"
                        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "BAD CONTENT TYPE"
                        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED   -> "HTTP BLOCKED"
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "UNSUPPORTED FORMAT"
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED          -> "DECODER FAILED"
                        else -> "ERR ${error.errorCode}"
                    }
                    setStatus("$reason — TAP TO RETRY", COLOR_ERROR)
                    showOverlay()
                }
            }
        }
    }

    // ── Scores ticker ────────────────────────────────────────────────────────

    private fun toggleTicker() {
        TickerManager.tickerEnabled = !TickerManager.tickerEnabled
        updateScoresButton()
        if (TickerManager.tickerEnabled) startTicker() else stopTicker()
    }

    private fun updateScoresButton() {
        val on = TickerManager.tickerEnabled
        binding.btnScores.text = if (on) "SCORES ON" else "SCORES"
        binding.btnScores.setBackgroundResource(
            if (on) R.drawable.bg_btn_scores_on else R.drawable.bg_btn_hud
        )
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
        tickerScrollAnim?.cancel()
        tickerScrollAnim = null
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
        if (selected.isEmpty()) {
            TickerManager.liveScores = emptyList()
            updateTickerText()
            return
        }
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
                val id = event.optString("id")
                if (id !in ids) continue
                val comp = event.optJSONArray("competitions")?.getJSONObject(0) ?: continue
                val competitors = comp.optJSONArray("competitors") ?: continue
                val status = comp.optJSONObject("status") ?: continue
                val statusType = status.optJSONObject("type") ?: continue
                val state = statusType.optString("state", "pre")
                val detail = statusType.optString("shortDetail", "")
                val statusDesc = statusType.optString("description", "")
                // Pull penalty/AET note: prefer the ESPN notes headline, fall back to description parsing
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
                    state     = state,
                    detail    = detail,
                    note      = note
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
            tickerScrollAnim?.cancel()
            tickerScrollAnim = null
            binding.tvTicker.text = newText
            loopTickerScroll()
        } else {
            pendingTickerText = newText
        }
    }

    // ── News ticker ───────────────────────────────────────────────────────────

    private fun toggleNewsTicker() {
        TickerManager.newsTickerEnabled = !TickerManager.newsTickerEnabled
        updateNewsButton()
        if (TickerManager.newsTickerEnabled) startNewsTicker() else stopNewsTicker()
    }

    private fun updateNewsButton() {
        val on = TickerManager.newsTickerEnabled
        binding.btnNews.text = if (on) "NEWS ON" else "NEWS"
        binding.btnNews.setBackgroundResource(
            if (on) R.drawable.bg_btn_scores_on else R.drawable.bg_btn_hud
        )
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
        newsScrollAnim?.cancel()
        newsScrollAnim = null
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
                            Request.Builder()
                                .url(feed.rssUrl)
                                .header("User-Agent", "Mozilla/5.0")
                                .build()
                        ).execute().use { it.body?.string() ?: "" }
                    }
                    val titles = parseRssTitles(xml)
                    if (titles.isNotEmpty()) results[feed.id] = titles
                } catch (_: Exception) {}
            }
            if (results.isNotEmpty()) {
                TickerManager.sportHeadlines = results
                updateNewsTickerText()
            }
        }
    }

    private fun parseRssTitles(xml: String): List<String> {
        val titles = mutableListOf<String>()
        try {
            val parser = android.util.Xml.newPullParser()
            parser.setInput(xml.reader())
            var inItem = false
            var event = parser.eventType
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
            newsScrollAnim?.cancel()
            newsScrollAnim = null
            binding.tvNewsTicker.text = newText
            loopNewsScroll()
        } else {
            pendingNewsText = newText
        }
    }

    // ── MPV player (VOD) ─────────────────────────────────────────────────────

    private fun initMpvPlayer() {
        binding.surfaceView.visibility = View.GONE
        binding.mpvView.visibility = View.VISIBLE
        binding.mpvView.init()
        mpvReady = true
        mpvStartedPlayingOnce = false
        mpvLoadStartMs = System.currentTimeMillis()
        binding.mpvView.loadUrl(streamUrl, resumeMs)
        resumeMs = 0L
        if (subtitlePath.isNotBlank()) {
            // Delay slightly so MPV has opened the stream before accepting sub-add
            binding.mpvView.postDelayed({ binding.mpvView.loadExternalSubtitle(subtitlePath) }, 1500L)
        }
        binding.progressBar.visibility = View.VISIBLE
        setStatus("CONNECTING...", COLOR_BUFFERING)
        mpvHandler.post(mpvPollRunnable)

        // BaseMPVView intercepts touches, so mirror the root click handler here
        binding.mpvView.setOnClickListener {
            if (hasError) {
                hasError = false
                mpvStartedPlayingOnce = false
                mpvLoadStartMs = System.currentTimeMillis()
                binding.mpvView.loadUrl(streamUrl, 0L)
                binding.progressBar.visibility = View.VISIBLE
                setStatus("CONNECTING...", COLOR_BUFFERING)
            } else {
                showOverlay()
            }
        }
    }

    private fun initExoPlayer() {
        binding.mpvView.visibility = View.GONE
        binding.surfaceView.visibility = View.VISIBLE
        player = ExoPlayer.Builder(this).build()
        player.addListener(playerListener)
        binding.surfaceView.holder.addCallback(surfaceCallback)
        player.setMediaItem(MediaItem.fromUri(streamUrl))
        if (resumeMs > 0L) { player.seekTo(resumeMs); resumeMs = 0L }
        player.prepare()
        player.playWhenReady = true
        binding.progressBar.visibility = View.VISIBLE
        setStatus("CONNECTING...", COLOR_BUFFERING)
    }

    private fun initVlcPlayer() {
        binding.mpvView.visibility = View.GONE
        binding.surfaceView.visibility = View.VISIBLE
        val lib = LibVLC(this, arrayListOf("--network-caching=1500", "--codec=mediacodec_ndk,all"))
        vlcLibrary = lib
        val vp = VLCMediaPlayer(lib)
        vlcPlayer = vp
        binding.surfaceView.holder.addCallback(surfaceCallback)
        binding.surfaceView.keepScreenOn = true
        val surface = binding.surfaceView.holder.surface
        if (surface != null && surface.isValid) {
            val vout = vp.vlcVout
            vout.setVideoSurface(surface, binding.surfaceView.holder)
            vout.attachViews { _, _, _, _, _, _, _ ->
                runOnUiThread {
                    val w = binding.surfaceView.width
                    val h = binding.surfaceView.height
                    if (w > 0 && h > 0) vout.setWindowSize(w, h)
                }
            }
            val w = binding.surfaceView.width
            val h = binding.surfaceView.height
            if (w > 0 && h > 0) vp.vlcVout.setWindowSize(w, h)
        }
        vp.videoScale = VLCMediaPlayer.ScaleType.SURFACE_BEST_FIT
        vp.setEventListener { event ->
            when (event.type) {
                VLCMediaPlayer.Event.Playing -> runOnUiThread {
                    hasError = false
                    binding.progressBar.visibility = View.GONE
                    setStatus(if (isLive) "● LIVE" else "▶ PLAYING", if (isLive) COLOR_LIVE else COLOR_PLAYING)
                    updatePauseButton()
                    if (!isLive) { positionHandler.removeCallbacks(positionRunnable); positionHandler.post(positionRunnable) }
                }
                VLCMediaPlayer.Event.Paused -> runOnUiThread {
                    setStatus("⏸ PAUSED", COLOR_PAUSED)
                    positionHandler.removeCallbacks(positionRunnable)
                    updatePauseButton()
                }
                VLCMediaPlayer.Event.Buffering -> runOnUiThread {
                    if (event.buffering < 100f) {
                        binding.progressBar.visibility = View.VISIBLE
                        setStatus("BUFFERING...", COLOR_BUFFERING)
                    } else {
                        binding.progressBar.visibility = View.GONE
                    }
                }
                VLCMediaPlayer.Event.EndReached -> runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    setStatus("FINISHED", COLOR_PLAYING)
                    positionHandler.removeCallbacks(positionRunnable)
                    if (!isLive && !episodeCompleted) {
                        episodeCompleted = true
                        when {
                            favId.isNotEmpty() -> { handleCompletion(); showOverlay(); overlayHandler.removeCallbacks(hideOverlayRunnable) }
                            embyItemId.isNotEmpty() -> markEmbyPlayedAndFinish()
                            plexRatingKey.isNotEmpty() -> markPlexPlayedAndFinish()
                            else -> { showOverlay(); overlayHandler.removeCallbacks(hideOverlayRunnable) }
                        }
                    }
                }
                VLCMediaPlayer.Event.EncounteredError -> runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    positionHandler.removeCallbacks(positionRunnable)
                    hasError = true
                    setStatus("STREAM ERROR — TAP TO RETRY", COLOR_ERROR)
                    showOverlay()
                }
            }
        }
        val media = Media(lib, android.net.Uri.parse(streamUrl))
        vp.media = media
        media.release()
        if (resumeMs > 0L) { vp.time = resumeMs; resumeMs = 0L }
        vp.play()
        binding.progressBar.visibility = View.VISIBLE
        setStatus("CONNECTING...", COLOR_BUFFERING)
    }

    private fun retryVlc() {
        val vp = vlcPlayer ?: return
        val lib = vlcLibrary ?: return
        val media = Media(lib, android.net.Uri.parse(streamUrl))
        vp.media = media
        media.release()
        vp.play()
        binding.progressBar.visibility = View.VISIBLE
        setStatus("CONNECTING...", COLOR_BUFFERING)
    }

    private fun showVlcAudioPicker() {
        val vp = vlcPlayer ?: return
        val tracks = vp.audioTracks
        if (tracks.isNullOrEmpty()) {
            Toast.makeText(this, "NO AUDIO TRACKS FOUND IN THIS STREAM", Toast.LENGTH_SHORT).show()
            overlayHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS)
            return
        }
        val currentId = vp.audioTrack
        val labels = tracks.map { t -> "${if (t.id == currentId) "●" else "○"}  ${t.name.uppercase()}" }
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("AUDIO LANGUAGE")
            .setItems(labels.toTypedArray()) { _, i -> vp.audioTrack = tracks[i].id }
            .setOnDismissListener { overlayHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS) }
            .show()
    }

    private fun showVlcSubtitlePicker() {
        val vp = vlcPlayer ?: return
        data class SubEntry(val id: Int, val name: String)
        val entries = mutableListOf(SubEntry(-1, "OFF"))
        vp.spuTracks?.forEach { t -> entries.add(SubEntry(t.id, t.name)) }
        if (entries.size == 1) {
            Toast.makeText(this, "NO SUBTITLE TRACKS AVAILABLE", Toast.LENGTH_SHORT).show()
            overlayHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS)
            return
        }
        val currentId = vp.spuTrack
        val labels = entries.map { e -> "${if (e.id == currentId) "●" else "○"}  ${e.name.uppercase()}" }
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("SUBTITLES")
            .setItems(labels.toTypedArray()) { _, i -> vp.spuTrack = entries[i].id }
            .setOnDismissListener { overlayHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS) }
            .show()
    }

    private fun updateMpvState() {
        if (!mpvReady) return
        val buffering = binding.mpvView.isBuffering()
        val paused    = binding.mpvView.isPaused()
        val idle      = binding.mpvView.isIdle()
        runOnUiThread {
            when {
                buffering -> {
                    binding.progressBar.visibility = View.VISIBLE
                    setStatus("BUFFERING...", COLOR_BUFFERING)
                }
                !paused && !idle -> {
                    hasError = false
                    mpvStartedPlayingOnce = true
                    binding.progressBar.visibility = View.GONE
                    setStatus(if (isLive) "● LIVE" else "▶ PLAYING", if (isLive) COLOR_LIVE else COLOR_PLAYING)
                    if (!isLive) {
                        positionHandler.removeCallbacks(positionRunnable)
                        positionHandler.post(positionRunnable)
                    }
                }
                paused && !idle -> {
                    binding.progressBar.visibility = View.GONE
                    setStatus("⏸ PAUSED", COLOR_PAUSED)
                    positionHandler.removeCallbacks(positionRunnable)
                }
                idle && !paused && !buffering -> {
                    val idlePos = binding.mpvView.getCurrentPositionMs()
                    val idleDur = binding.mpvView.getDurationMs()
                    val nearEnd = idleDur > 0L && idlePos > 0L && idlePos >= idleDur - 30_000L
                    val stuckTimeout = !mpvStartedPlayingOnce &&
                        (System.currentTimeMillis() - mpvLoadStartMs) > 15_000L
                    when {
                        stuckTimeout && !hasError -> {
                            hasError = true
                            binding.progressBar.visibility = View.GONE
                            setStatus("FAILED TO CONNECT — TAP TO RETRY", COLOR_ERROR)
                            showOverlay()
                        }
                        nearEnd && !episodeCompleted && !hasError -> {
                            episodeCompleted = true
                            binding.progressBar.visibility = View.GONE
                            setStatus("FINISHED", COLOR_PLAYING)
                            positionHandler.removeCallbacks(positionRunnable)
                            when {
                                favId.isNotEmpty() -> {
                                    handleCompletion()
                                    showOverlay()
                                    overlayHandler.removeCallbacks(hideOverlayRunnable)
                                }
                                embyItemId.isNotEmpty() -> markEmbyPlayedAndFinish()
                                plexRatingKey.isNotEmpty() -> markPlexPlayedAndFinish()
                                else -> {
                                    showOverlay()
                                    overlayHandler.removeCallbacks(hideOverlayRunnable)
                                }
                            }
                        }
                    }
                }
            }
            updatePauseButton()
            updateMpvPositionDisplay()
        }
    }

    private fun updateMpvPositionDisplay() {
        if (!mpvReady || pendingSeekTargetMs >= 0L) return
        val pos = binding.mpvView.getCurrentPositionMs()
        val dur = binding.mpvView.getDurationMs()
        if (dur > 0L) {
            binding.tvPosition.text = "${formatMs(pos)} / ${formatMs(dur)}"
            binding.vodProgress.progress = ((pos.toFloat() / dur) * 1000).toInt()
        }
    }

    private fun showMpvAudioPicker() {
        val tracks = binding.mpvView.getAudioTracks()
        if (tracks.isEmpty()) {
            Toast.makeText(this, "NO AUDIO TRACKS FOUND IN THIS STREAM", Toast.LENGTH_SHORT).show()
            overlayHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS)
            return
        }
        val labels = tracks.map { t ->
            val name = listOfNotNull(t.language?.uppercase(), t.title?.uppercase(), t.codec?.uppercase())
                .joinToString(" ").ifBlank { "TRACK ${t.id}" }
            "${if (t.selected) "●" else "○"}  $name"
        }
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("AUDIO LANGUAGE")
            .setItems(labels.toTypedArray()) { _, i -> binding.mpvView.selectAudioTrack(tracks[i].id) }
            .setOnDismissListener { overlayHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS) }
            .show()
    }

    private fun showMpvSubtitlePicker() {
        val tracks = binding.mpvView.getSubtitleTracks()
        val entries = mutableListOf(MpvTrack(-1, null, "OFF", null, false))
        entries.addAll(tracks)
        val labels = entries.map { t ->
            if (t.id == -1) return@map "○  OFF"
            val name = listOfNotNull(t.language?.uppercase(), t.title?.uppercase())
                .joinToString(" ").ifBlank { "SUBTITLE ${t.id}" }
            "${if (t.selected) "●" else "○"}  $name"
        }
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("SUBTITLES")
            .setItems(labels.toTypedArray()) { _, i ->
                if (entries[i].id == -1) binding.mpvView.disableSubtitles()
                else binding.mpvView.selectSubtitleTrack(entries[i].id)
            }
            .setOnDismissListener { overlayHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS) }
            .show()
    }

    private fun initPlayer() {
        when (currentEngine) {
            PlayerEngine.MPV      -> initMpvPlayer()
            PlayerEngine.EXOPLAYER -> initExoPlayer()
            PlayerEngine.VLC      -> initVlcPlayer()
            PlayerEngine.EXTERNAL -> { launchExternalPlayer(); finish() }
        }
    }

    private fun playMedia() {
        if (!::player.isInitialized) return
        hasError = false
        audioRecoveryAttempted = false
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(MediaItem.fromUri(streamUrl))
        if (resumeMs > 0L) {
            player.seekTo(resumeMs)
            resumeMs = 0L
        }
        player.prepare()
        player.playWhenReady = true
        binding.progressBar.visibility = View.VISIBLE
        setStatus("CONNECTING...", COLOR_BUFFERING)
    }

    // ── Channel navigation ────────────────────────────────────────────────────

    private fun changeChannel(delta: Int) {
        val queue = ChannelQueue.entries
        if (queue.isEmpty()) return
        val newIdx = (ChannelQueue.currentIndex + delta + queue.size) % queue.size
        ChannelQueue.currentIndex = newIdx
        val entry = queue[newIdx]

        val creds = PrefsManager.getCredentials(this) ?: return
        streamUrl   = repository.buildStreamUrl(creds.serverUrl, creds.username, creds.password, entry.streamId)
        channelName = entry.name
        streamId    = entry.streamId
        hasError          = false
        audioRecoveryAttempted = false
        episodeCompleted  = false

        binding.tvPlayerTitle.text = channelName.uppercase()
        val displayName = if (entry.num > 0) "${entry.num}  ${channelName.uppercase()}" else channelName.uppercase()
        binding.tvChannelInfo.text = displayName
        binding.tvNowTitle.text  = ""
        binding.tvNextTitle.text = ""
        binding.tvNextTime.text  = ""

        binding.progressBar.visibility = View.VISIBLE
        setStatus("LOADING...", COLOR_BUFFERING)
        showOverlay()

        when {
            mpvReady -> {
                mpvStartedPlayingOnce = false
                mpvLoadStartMs = System.currentTimeMillis()
                binding.mpvView.loadUrl(streamUrl, 0L)
            }
            vlcPlayer != null -> {
                val media = Media(vlcLibrary!!, android.net.Uri.parse(streamUrl))
                vlcPlayer!!.media = media
                media.release()
                vlcPlayer!!.play()
            }
            else -> playMedia()
        }

        loadEpg()
    }

    // ── EPG ───────────────────────────────────────────────────────────────────

    private fun loadEpg() {
        if (streamId == -1) return
        val creds = PrefsManager.getCredentials(this) ?: return

        lifecycleScope.launch {
            try {
                val cached = EpgCache.get(this@PlayerActivity, streamId)
                val listings = if (cached != null) {
                    cached
                } else {
                    val result = repository.getShortEpg(creds.serverUrl, creds.username, creds.password, streamId)
                    result.getOrNull()?.listings?.also { l ->
                        EpgCache.put(this@PlayerActivity, streamId, l)
                    } ?: emptyList()
                }

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
                    binding.tvNowTitle.text = it
                }
                next?.let { n ->
                    n.getDecodedTitle().takeIf { it.isNotBlank() }?.let { title ->
                        binding.tvNextTitle.text = title
                        val startSec = n.startTimestamp?.toLongOrNull()
                        if (startSec != null) {
                            val cal = Calendar.getInstance().apply { timeInMillis = startSec * 1000 }
                            val h = cal.get(Calendar.HOUR_OF_DAY)
                            val m = cal.get(Calendar.MINUTE)
                            val ampm = if (h < 12) "am" else "pm"
                            val h12 = if (h % 12 == 0) 12 else h % 12
                            binding.tvNextTime.text = "%d:%02d%s".format(h12, m, ampm)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // ── External player ───────────────────────────────────────────────────────

    private fun launchExternalPlayer() {
        overlayHandler.removeCallbacks(hideOverlayRunnable)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(android.net.Uri.parse(streamUrl), "video/*")
            putExtra("title", channelName)
        }
        try {
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "NO VIDEO PLAYER APP FOUND", Toast.LENGTH_SHORT).show()
        }
        overlayHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS)
    }

    // ── Track pickers ─────────────────────────────────────────────────────────

    private fun showAudioPicker() {
        if (mpvReady) { showMpvAudioPicker(); return }
        if (vlcPlayer != null) { showVlcAudioPicker(); return }
        if (!::player.isInitialized) return
        overlayHandler.removeCallbacks(hideOverlayRunnable)

        val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isEmpty()) {
            Toast.makeText(this, "NO AUDIO TRACKS FOUND IN THIS STREAM", Toast.LENGTH_SHORT).show()
            overlayHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS)
            return
        }

        data class AudioEntry(val groupIdx: Int, val trackIdx: Int, val label: String, val selected: Boolean)
        val entries = mutableListOf<AudioEntry>()
        audioGroups.forEachIndexed { gi, group ->
            for (ti in 0 until group.length) {
                val format = group.getTrackFormat(ti)
                val lang = format.language?.uppercase() ?: ""
                val lbl  = format.label?.uppercase() ?: ""
                val name = listOfNotNull(lang.takeIf { it.isNotBlank() }, lbl.takeIf { it.isNotBlank() })
                    .joinToString(" ").ifBlank { "TRACK ${gi + 1}" }
                entries.add(AudioEntry(gi, ti, name, group.isSelected && group.isTrackSelected(ti)))
            }
        }

        val labels = entries.map { "${if (it.selected) "●" else "○"}  ${it.label}" }
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("AUDIO LANGUAGE")
            .setItems(labels.toTypedArray()) { _, which ->
                val entry = entries[which]
                val group = audioGroups[entry.groupIdx]
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .addOverride(TrackSelectionOverride(group.mediaTrackGroup, entry.trackIdx))
                    .build()
            }
            .setOnDismissListener { overlayHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS) }
            .show()
    }

    private fun showSubtitlePicker() {
        if (mpvReady) { showMpvSubtitlePicker(); return }
        if (vlcPlayer != null) { showVlcSubtitlePicker(); return }
        if (!::player.isInitialized) return
        overlayHandler.removeCallbacks(hideOverlayRunnable)

        val textGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        data class SubEntry(val groupIdx: Int, val trackIdx: Int, val label: String, val selected: Boolean)
        val entries = mutableListOf<SubEntry>()
        val subsOn = textGroups.any { it.isSelected }
        entries.add(SubEntry(-1, -1, "OFF", !subsOn))

        textGroups.forEachIndexed { gi, group ->
            for (ti in 0 until group.length) {
                val format = group.getTrackFormat(ti)
                val lang = format.language?.uppercase() ?: ""
                val lbl  = format.label?.uppercase() ?: ""
                val name = listOfNotNull(lang.takeIf { it.isNotBlank() }, lbl.takeIf { it.isNotBlank() })
                    .joinToString(" ").ifBlank { "SUBTITLE ${gi + 1}" }
                entries.add(SubEntry(gi, ti, name, group.isSelected && group.isTrackSelected(ti)))
            }
        }

        if (entries.size == 1) {
            Toast.makeText(this, "NO SUBTITLE TRACKS AVAILABLE", Toast.LENGTH_SHORT).show()
            overlayHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS)
            return
        }

        val labels = entries.map { "${if (it.selected) "●" else "○"}  ${it.label}" }
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("SUBTITLES")
            .setItems(labels.toTypedArray()) { _, which ->
                val entry = entries[which]
                val params = player.trackSelectionParameters.buildUpon()
                if (entry.groupIdx == -1) {
                    params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    val group = textGroups[entry.groupIdx]
                    params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .addOverride(TrackSelectionOverride(group.mediaTrackGroup, entry.trackIdx))
                }
                player.trackSelectionParameters = params.build()
            }
            .setOnDismissListener { overlayHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS) }
            .show()
    }

    // ── Favourites / completion ───────────────────────────────────────────────

    private fun handleCompletion() {
        if (seriesId >= 0) FavouritesManager.removeBySeriesId(this, seriesId)
        else FavouritesManager.remove(this, favId)
        if (nextEpUrl.isNotEmpty()) {
            val nextId = "ep_${seriesId}_${nextEpSeason}_${nextEpNum}"
            FavouritesManager.addOrUpdate(this, FavouriteItem(
                id               = nextId,
                type             = FavType.EPISODE,
                title            = nextEpTitle,
                artUrl           = artUrl,
                streamUrl        = nextEpUrl,
                streamId         = 0,
                seriesId         = seriesId,
                season           = nextEpSeason,
                episodeNum       = nextEpNum,
                episodeId        = nextEpId
            ))
            Toast.makeText(this, "NEXT EPISODE ADDED TO FAVOURITES", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveResumePosition() {
        if (isLive || favId.isEmpty() || episodeCompleted) return
        val pos: Long = when {
            mpvReady              -> binding.mpvView.getCurrentPositionMs()
            vlcPlayer != null     -> vlcPlayer!!.time.coerceAtLeast(0L)
            ::player.isInitialized -> player.currentPosition
            else                  -> return
        }.takeIf { it > 0L } ?: return
        val dur: Long = when {
            mpvReady              -> binding.mpvView.getDurationMs()
            vlcPlayer != null     -> vlcPlayer!!.length
            ::player.isInitialized -> player.duration.takeIf { it != C.TIME_UNSET } ?: return
            else                  -> return
        }.takeIf { it > 0L } ?: return
        if (pos < 10_000L) return

        val fraction = pos.toFloat() / dur
        if (fraction > 0.92f) {
            handleCompletion()
            episodeCompleted = true
            return
        }

        if (FavouritesManager.contains(this, favId)) {
            FavouritesManager.updateResume(this, favId, pos, dur)
        } else {
            val type = if (seriesId >= 0 && season.isNotEmpty()) FavType.EPISODE else FavType.MOVIE
            FavouritesManager.addOrUpdate(this, FavouriteItem(
                id                = favId,
                type              = type,
                title             = channelName,
                artUrl            = artUrl,
                streamUrl         = streamUrl,
                streamId          = streamId,
                resumePositionMs  = pos,
                durationMs        = dur,
                seriesId          = seriesId,
                season            = season,
                episodeNum        = episodeNum,
                episodeId         = episodeId,
                nextEpisodeUrl    = nextEpUrl,
                nextEpisodeTitle  = nextEpTitle,
                nextEpisodeSeason = nextEpSeason,
                nextEpisodeNum    = nextEpNum,
                nextEpisodeId     = nextEpId
            ))
        }
    }

    // ── Emby reporting ───────────────────────────────────────────────────────────

    private fun reportEmbyStart() {
        val session = EmbyPrefsManager.getSession(this) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            embyRepo.reportStart(session.serverUrl, session.token, embyItemId, resumeMs)
        }
    }

    private fun reportEmbyStop() {
        val session = EmbyPrefsManager.getSession(this) ?: return
        val posMs: Long = when {
            mpvReady              -> binding.mpvView.getCurrentPositionMs()
            vlcPlayer != null     -> vlcPlayer!!.time.coerceAtLeast(0L)
            ::player.isInitialized -> player.currentPosition
            else                  -> 0L
        }
        lifecycleScope.launch(Dispatchers.IO) {
            embyRepo.reportStop(session.serverUrl, session.token, embyItemId, posMs)
        }
    }

    private fun markEmbyPlayedAndFinish() {
        val session = EmbyPrefsManager.getSession(this)
        if (session == null) { finish(); return }
        lifecycleScope.launch(Dispatchers.IO) {
            embyRepo.markPlayed(session.serverUrl, session.userId, session.token, embyItemId)
            withContext(Dispatchers.Main) { finish() }
        }
    }

    private fun markPlexPlayedAndFinish() {
        val session = PlexPrefsManager.getSession(this)
        if (session == null) { finish(); return }
        lifecycleScope.launch(Dispatchers.IO) {
            plexRepo.markPlayed(session.serverUrl, session.token, plexRatingKey)
            withContext(Dispatchers.Main) { finish() }
        }
    }

    // ── Plex reporting ────────────────────────────────────────────────────────

    private fun reportPlexStart() {
        val session = PlexPrefsManager.getSession(this) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            plexRepo.reportTimeline(session.serverUrl, session.token,
                plexRatingKey, "playing", resumeMs, plexDurationMs)
        }
    }

    private fun reportPlexStop() {
        val session = PlexPrefsManager.getSession(this) ?: return
        val posMs: Long = when {
            mpvReady              -> binding.mpvView.getCurrentPositionMs()
            vlcPlayer != null     -> vlcPlayer!!.time.coerceAtLeast(0L)
            ::player.isInitialized -> player.currentPosition
            else                  -> 0L
        }
        lifecycleScope.launch(Dispatchers.IO) {
            plexRepo.reportTimeline(session.serverUrl, session.token,
                plexRatingKey, "stopped", posMs, plexDurationMs)
        }
    }

    // ── Clock ─────────────────────────────────────────────────────────────────

    private fun updateClock() {
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val ampm = if (h < 12) "am" else "pm"
        val h12 = if (h % 12 == 0) 12 else h % 12
        val dateStr = SimpleDateFormat("EEE dd MMM", Locale.UK).format(cal.time)
        binding.tvClock.text = "%d.%02d%s  %s".format(h12, m, ampm, dateStr.uppercase())
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onStop() {
        super.onStop()
        saveResumePosition()
        if (embyItemId.isNotEmpty() && !episodeCompleted) reportEmbyStop()
        if (plexRatingKey.isNotEmpty() && !episodeCompleted) reportPlexStop()
        if (isLive) {
            RecordingState.isLiveTvActive   = false
            RecordingState.liveTvChannelUrl  = null
            RecordingState.liveTvChannelName = null
        }
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.surfaceView.keepScreenOn = true
        binding.mpvView.keepScreenOn = true
        enterFullscreen()
        when {
            mpvReady -> binding.mpvView.setPaused(false)
            vlcPlayer != null && !hasError -> vlcPlayer!!.play()
            ::player.isInitialized && !hasError -> player.playWhenReady = true
        }
        com.orbital.iptv.utils.ReminderBus.register { r -> showReminderDialog(r) }
        if (isLive) {
            RecordingState.isLiveTvActive   = true
            RecordingState.liveTvChannelUrl  = streamUrl
            RecordingState.liveTvChannelName = channelName
            // Sync local flag with actual service state (e.g. after coming back from another activity)
            isRecordingThisChannel = RecordingState.activeRecordNowUrl == streamUrl
            updateRecordButton()
            RecordingState.registerStopLiveTv { finish() }
        }
    }

    override fun onPause() {
        super.onPause()
        if (enteringPip) { enteringPip = false; return }  // keep playing in PiP
        com.orbital.iptv.utils.ReminderBus.unregister()
        when {
            mpvReady -> binding.mpvView.setPaused(true)
            vlcPlayer != null -> vlcPlayer!!.pause()
            ::player.isInitialized -> player.pause()
        }
        if (isLive) {
            RecordingState.unregisterStopLiveTv()
        }
    }

    private fun showReminderDialog(r: com.orbital.iptv.utils.ReminderBus.Reminder) {
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
                    startActivity(Intent(this, PlayerActivity::class.java).apply {
                        putExtra(EXTRA_STREAM_URL,   r.streamUrl)
                        putExtra(EXTRA_CHANNEL_NAME, r.channelName)
                        putExtra(EXTRA_STREAM_ID,    r.streamId)
                        putExtra(EXTRA_IS_LIVE,      true)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    })
                    finish()
                }
            }
            .setNegativeButton("DISMISS", null)
            .show()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isLive) return
        if (!PrefsManager.isPipEnabled(this)) return
        val playing = if (mpvReady) !binding.mpvView.isPaused() && !binding.mpvView.isIdle()
                      else ::player.isInitialized && player.isPlaying
        if (!playing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enteringPip = true
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            binding.hudOverlay.visibility = View.GONE
            binding.bottomBar.visibility = View.GONE
            overlayHandler.removeCallbacks(hideOverlayRunnable)
        } else {
            showOverlay()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayHandler.removeCallbacks(hideOverlayRunnable)
        clockHandler.removeCallbacks(clockRunnable)
        positionHandler.removeCallbacks(positionRunnable)
        seekHandler.removeCallbacksAndMessages(null)
        tickerHandler.removeCallbacksAndMessages(null)
        newsHandler.removeCallbacksAndMessages(null)
        mpvHandler.removeCallbacksAndMessages(null)
        when {
            mpvReady -> binding.mpvView.release()
            vlcPlayer != null -> {
                vlcPlayer!!.stop()
                runCatching { vlcPlayer!!.vlcVout.detachViews() }
                vlcPlayer!!.release()
                vlcLibrary?.release()
                vlcPlayer = null
                vlcLibrary = null
                binding.surfaceView.holder.removeCallback(surfaceCallback)
            }
            else -> {
                binding.surfaceView.holder.removeCallback(surfaceCallback)
                if (::player.isInitialized) {
                    player.removeListener(playerListener)
                    player.release()
                }
            }
        }
    }
}
