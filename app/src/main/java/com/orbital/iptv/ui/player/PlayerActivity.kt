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
import com.orbital.iptv.utils.PrefsManager
import com.orbital.iptv.utils.TickerManager
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
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
        const val EXTRA_IS_CATCHUP      = "is_catchup"

        private const val OVERLAY_HIDE_DELAY_MS  = 5000L
        private const val SEEK_STEP_MS           = 30_000L
        private const val SEEK_COMMIT_DELAY_MS   = 400L
        private const val SEEK_INDICATOR_HIDE_MS = 1200L
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        private const val MAX_IO_RETRIES = 5
        private const val MAX_AUDIO_SINK_ERRORS = 8

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
    private var liveHlsFallbackAttempted = false
    private var ioRetryCount = 0
    private var retryPositionMs = 0L
    private val ioRetryHandler = Handler(Looper.getMainLooper())
    private var audioSinkErrorCount = 0
    private var audioDisabledForSinkErrors = false
    private var embyItemId = ""
    private val embyRepo = EmbyRepository()
    private var plexRatingKey = ""
    private var plexDurationMs = 0L
    private val plexRepo = PlexRepository()
    private var enteringPip = false
    private var subtitlePath = ""

    private val overlayHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideOverlay() }

    private val zapHandler = Handler(Looper.getMainLooper())
    private val hideZapRunnable = Runnable { hideZapBar() }

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

    private var pendingTickerText: String? = null
    private var tickerScrollAnim: ValueAnimator? = null
    private var tickerShowingPlaceholder = false

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

        binding.tvPlayerTitle.text = channelName.uppercase()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAudio.setOnClickListener { showAudioPicker() }
        updateSurroundButton()
        binding.btnSurround.setOnClickListener { toggleSurroundSound() }
        binding.btnSubs.setOnClickListener { showSubtitlePicker() }
        binding.btnScores.setOnClickListener { toggleTicker() }
        binding.btnNews.setOnClickListener { toggleNewsTicker() }
        binding.btnOpenIn.setOnClickListener { launchExternalPlayer() }
        binding.root.setOnClickListener {
            if (hasError) {
                if (!isLive && retryPositionMs > 0L) resumeMs = retryPositionMs
                playMedia()
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
        AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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
        androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java).apply {
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
        zapHandler.removeCallbacks(hideZapRunnable)
        if (isLive) binding.btnRecord.visibility = View.VISIBLE
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
        updateSurroundButton()
    }

    private fun hideOverlay() {
        binding.hudOverlay.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        clockHandler.removeCallbacks(clockRunnable)
    }

    private fun showZapBar() {
        overlayHandler.removeCallbacks(hideOverlayRunnable)
        binding.hudOverlay.visibility = View.GONE
        binding.btnRecord.visibility = View.GONE
        binding.bottomBar.visibility = View.VISIBLE
        if (isLive) updateClock()
        zapHandler.removeCallbacks(hideZapRunnable)
        zapHandler.postDelayed(hideZapRunnable, 3000L)
    }

    private fun hideZapBar() {
        zapHandler.removeCallbacks(hideZapRunnable)
        if (binding.hudOverlay.visibility != View.VISIBLE) {
            binding.bottomBar.visibility = View.GONE
        }
    }

    private fun isOverlayFocused(): Boolean =
        binding.hudOverlay.findFocus() != null || binding.bottomBar.findFocus() != null

    private fun togglePause() {
        if (isLive) return
        if (::player.isInitialized) {
            if (player.isPlaying) {
                player.pause(); showOverlay(); overlayHandler.removeCallbacks(hideOverlayRunnable)
            } else {
                player.play(); showOverlay()
            }
        }
    }

    private fun updatePauseButton() {
        if (isLive) return
        val playing = if (::player.isInitialized) player.isPlaying else false
        binding.btnPause.text = if (playing) "▌▌  PAUSE" else "▶  RESUME"
    }

    private fun seekBy(deltaMs: Long) {
        if (!::player.isInitialized) return
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: return
        val base = if (pendingSeekTargetMs >= 0L) pendingSeekTargetMs else player.currentPosition
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
        if (::player.isInitialized) player.seekTo(pendingSeekTargetMs)
        pendingSeekTargetMs = -1L
        seekHandler.postDelayed(hideSeekIndicatorRunnable, SEEK_INDICATOR_HIDE_MS)
    }

    private fun updatePositionDisplay() {
        if (!::player.isInitialized) return
        val pos = player.currentPosition
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: return
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
            if (::player.isInitialized) player.setVideoSurface(holder.surface)
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (::player.isInitialized) player.clearVideoSurface()
        }
    }

    // Some E-AC3 streams hit a timestamp-discontinuity bug in the FFmpeg audio decoder
    // (org.jellyfin.media3:media3-ffmpeg-decoder) that isn't fixable from app code — it's inside a
    // prebuilt third-party decoder, not something Orbital compiles. The bug doesn't crash playback
    // (onPlayerError never fires) and it doesn't stop on its own; it repeats on essentially every
    // decoded buffer, so once it starts it's continuous for the rest of the file. Rather than let
    // that stutter forever, disable the audio track after enough consecutive errors so at least the
    // video is watchable — the user's only real fix for the audio itself is an external player (the
    // "OPEN IN" button) with its own decoder.
    private val audioSinkErrorListener = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onAudioSinkError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, audioSinkError: Exception) {
            android.util.Log.w("OrbitalPlayer", "onAudioSinkError #$audioSinkErrorCount disabled=$audioDisabledForSinkErrors: $audioSinkError")
            if (audioDisabledForSinkErrors) return
            audioSinkErrorCount++
            if (audioSinkErrorCount < MAX_AUDIO_SINK_ERRORS) return
            audioDisabledForSinkErrors = true
            runOnUiThread {
                if (!::player.isInitialized) return@runOnUiThread
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                setStatus("NO AUDIO — DECODER ERROR (EAC3)", COLOR_WARNING)
                binding.hudOverlay.visibility = View.VISIBLE
                binding.bottomBar.visibility = View.VISIBLE
                overlayHandler.removeCallbacks(hideOverlayRunnable)
                overlayHandler.postDelayed(hideOverlayRunnable, 8_000L)
            }
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
                    // FORMAT_HANDLED = fully decodable. ExoPlayer can silently select
                    // AC3 tracks it cannot decode (FORMAT_EXCEEDS_CAPABILITIES), so
                    // checking isSelected alone is not enough.
                    val hasPlayableAudio = audioGroups.any { group ->
                        (0 until group.length).any { i ->
                            group.isTrackSelected(i) && group.getTrackSupport(i) == C.FORMAT_HANDLED
                        }
                    }
                    val audioLog = audioGroups.flatMap { g ->
                        (0 until g.length).map { i ->
                            val f = g.getTrackFormat(i)
                            val sup = when (g.getTrackSupport(i)) {
                                C.FORMAT_HANDLED -> "OK"
                                C.FORMAT_EXCEEDS_CAPABILITIES -> "EXCEED"
                                C.FORMAT_UNSUPPORTED_TYPE -> "UNSUP_TYPE"
                                C.FORMAT_UNSUPPORTED_SUBTYPE -> "UNSUP_SUB"
                                else -> "?"
                            }
                            "${f.sampleMimeType?.substringAfterLast('/') ?: "?"}[sel=${g.isTrackSelected(i)},sup=$sup]"
                        }
                    }.joinToString(" ")
                    android.util.Log.d("OrbitalAudio", "STATE_READY: playable=$hasPlayableAudio url=$streamUrl tracks=[$audioLog]")
                    if (!hasPlayableAudio) {
                        // TS/HLS live streams often carry AC3 audio which Android phones
                        // can't decode. Retry with the HLS variant — providers transcode
                        // to AAC in their m3u8 output which ExoPlayer handles fine.
                        if (isLive && !liveHlsFallbackAttempted &&
                            streamUrl.endsWith(".ts", ignoreCase = true)) {
                            liveHlsFallbackAttempted = true
                            streamUrl = streamUrl.removeSuffix(".ts") + ".m3u8"
                            playMedia()
                            return@runOnUiThread
                        }
                        val codecs = audioGroups.flatMap { group ->
                            (0 until group.length).map { i ->
                                group.getTrackFormat(i).sampleMimeType?.substringAfterLast('/') ?: "?"
                            }
                        }.distinct().joinToString(", ")
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
                    ioRetryCount = 0
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
                android.util.Log.w("OrbitalPlayer", "onPlayerError code=${error.errorCode} name=${error.errorCodeName} msg=${error.message} cause=${error.cause}")
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
                    retryPositionMs = if (::player.isInitialized) player.currentPosition else 0L

                    // Remote HTTP sources (TorBox CDN links, etc.) can drop a connection mid-stream
                    // without the file actually being gone — retrying transparently from the same
                    // position avoids what otherwise looks like the player "glitching" back to 0:00
                    // every time a transient read/connect failure happens. VOD-only: a dead live
                    // channel should still surface immediately rather than retry silently forever.
                    val isRetryableIoError = !isLive && (
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                    )

                    if (isRetryableIoError && ioRetryCount < MAX_IO_RETRIES) {
                        ioRetryCount++
                        binding.progressBar.visibility = View.VISIBLE
                        setStatus("RECONNECTING…", COLOR_BUFFERING)
                        ioRetryHandler.removeCallbacksAndMessages(null)
                        ioRetryHandler.postDelayed({
                            resumeMs = retryPositionMs
                            playMedia()
                        }, 1000L * ioRetryCount)
                        return@runOnUiThread
                    }

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
        binding.tvNewsTicker.text = TickerManager.buildNewsText()
    }

    // ── ExoPlayer ─────────────────────────────────────────────────────────────

    private fun initPlayer() {
        initExoPlayer()
    }

    private fun initExoPlayer() {
        binding.surfaceView.visibility = View.VISIBLE
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(
                PcmOnlyRenderersFactory(this, PrefsManager.isSurroundEnabled(this))
                    .setEnableDecoderFallback(true)
                    // Tried EXTENSION_RENDERER_MODE_ON (prefer the platform decoder) to dodge a
                    // timestamp-discontinuity bug in the FFmpeg EAC3 decoder — confirmed via logcat
                    // this device has no platform EAC3 MediaCodec, so it fell back to the same
                    // FFmpeg decoder anyway. Reverted to PREFER; the real mitigation is the
                    // repeated-audio-sink-error handling below (auto-disables audio rather than
                    // looping forever on an undecodable track).
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioMimeTypes(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AC3)
            .build()
        player.addListener(playerListener)
        player.addAnalyticsListener(audioSinkErrorListener)
        binding.surfaceView.holder.removeCallback(surfaceCallback)
        binding.surfaceView.holder.addCallback(surfaceCallback)
        // addCallback() alone only fires surfaceCreated() when the native surface doesn't exist
        // yet. If the SurfaceView's surface is already valid — the normal case when rebuilding
        // the player on the same screen (e.g. the 5.1 toggle) rather than on first launch —
        // surfaceCreated() never fires again, so the new player would never get a video output
        // attached at all (silently no video; audio is unaffected since it's a separate
        // pipeline). Attach explicitly whenever a valid surface already exists.
        binding.surfaceView.holder.surface?.takeIf { it.isValid }?.let { player.setVideoSurface(it) }
        player.setMediaItem(MediaItem.fromUri(streamUrl))
        if (resumeMs > 0L) { player.seekTo(resumeMs); resumeMs = 0L }
        player.prepare()
        player.playWhenReady = true
        binding.progressBar.visibility = View.VISIBLE
        setStatus("CONNECTING...", COLOR_BUFFERING)
    }

    /** Toggles PcmOnlyRenderersFactory's stereo-only lock — see its doc comment for the tradeoff. */
    private fun toggleSurroundSound() {
        if (!hasSurroundAudioTrack()) {
            Toast.makeText(this, "NO 5.1/SURROUND AUDIO TRACK ON THIS STREAM", Toast.LENGTH_SHORT).show()
            return
        }
        val newState = !PrefsManager.isSurroundEnabled(this)
        PrefsManager.setSurroundEnabled(this, newState)
        updateSurroundButton()
        // RenderersFactory is baked in at ExoPlayer construction, so the only way to apply the
        // new capability setting is to release and rebuild the player at the current position.
        if (::player.isInitialized) {
            if (!isLive) resumeMs = player.currentPosition
            player.clearVideoSurface()
            player.removeListener(playerListener)
            player.release()
        }
        initExoPlayer()
        Toast.makeText(
            this,
            if (newState) "SURROUND: ON — testing real device audio capabilities" else "SURROUND: OFF — forced stereo",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateSurroundButton() {
        val on = PrefsManager.isSurroundEnabled(this)
        binding.btnSurround.text = if (on) "5.1 ✓" else "5.1"
        val available = hasSurroundAudioTrack()
        binding.btnSurround.isEnabled = available
        binding.btnSurround.visibility = if (available) View.VISIBLE else View.GONE
    }

    /**
     * True if the audio track actually SELECTED for playback right now is 5.1/7.1+ (channel
     * count) or explicitly labelled surround — not just any track the stream happens to offer.
     * Many IPTV channels bundle a stereo AAC track alongside a 5.1 AC3 alternate, and
     * setPreferredAudioMimeTypes() picks AAC first, so checking "any available track" let the
     * button light up even while a plain stereo track was the one actually playing.
     */
    private fun hasSurroundAudioTrack(): Boolean {
        if (!::player.isInitialized) return false
        return player.currentTracks.groups.any { group ->
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

    private fun playMedia() {
        if (!::player.isInitialized) return
        hasError = false
        audioRecoveryAttempted = false
        audioSinkErrorCount = 0
        audioDisabledForSinkErrors = false
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
        audioSinkErrorCount = 0
        audioDisabledForSinkErrors = false
        liveHlsFallbackAttempted = false
        episodeCompleted  = false

        binding.tvPlayerTitle.text = channelName.uppercase()
        val displayName = if (entry.num > 0) "${entry.num}  ${channelName.uppercase()}" else channelName.uppercase()
        binding.tvChannelInfo.text = displayName
        binding.tvNowTitle.text  = ""
        binding.tvNextTitle.text = ""
        binding.tvNextTime.text  = ""

        binding.progressBar.visibility = View.VISIBLE
        setStatus("LOADING...", COLOR_BUFFERING)
        showZapBar()

        playMedia()
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
        AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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
        AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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
        if (!::player.isInitialized) return
        val pos = player.currentPosition.takeIf { it > 0L } ?: return
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: return
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
        val posMs = if (::player.isInitialized) player.currentPosition else 0L
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
        val posMs = if (::player.isInitialized) player.currentPosition else 0L
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
        enterFullscreen()
        if (::player.isInitialized && !hasError) player.playWhenReady = true
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
        if (::player.isInitialized) player.pause()
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
        androidx.appcompat.app.AlertDialog.Builder(this, com.orbital.iptv.utils.ThemeManager.dialogStyle())
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
        val playing = ::player.isInitialized && player.isPlaying
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
        ioRetryHandler.removeCallbacksAndMessages(null)
        binding.surfaceView.holder.removeCallback(surfaceCallback)
        if (::player.isInitialized) {
            player.removeListener(playerListener)
            player.release()
        }
    }
}
