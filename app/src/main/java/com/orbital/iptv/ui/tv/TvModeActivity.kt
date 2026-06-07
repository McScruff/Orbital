package com.orbital.iptv.ui.tv

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.orbital.iptv.R
import com.orbital.iptv.data.model.LiveCategory
import com.orbital.iptv.data.model.LiveStream
import com.orbital.iptv.data.model.getDecodedTitle
import com.orbital.iptv.data.repository.XtreamRepository
import com.orbital.iptv.utils.EpgCache
import com.orbital.iptv.databinding.ActivityTvModeBinding
import com.orbital.iptv.recording.RecordingService
import com.orbital.iptv.recording.RecordingState
import com.orbital.iptv.ui.home.HomeActivity
import com.orbital.iptv.ui.player.PlayerActivity
import com.orbital.iptv.utils.FavouritesManager
import com.orbital.iptv.utils.PlayerEngine
import com.orbital.iptv.utils.PrefsManager
import com.orbital.iptv.utils.ThemeManager
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

class TvModeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL   = "tv_stream_url"
        const val EXTRA_CHANNEL_NAME = "tv_channel_name"
        const val EXTRA_STREAM_ID    = "tv_stream_id"
        const val EXTRA_CATEGORY_ID  = "tv_category_id"
        private const val FAV_CATEGORY_ID = "__favourites__"
    }

    private enum class OverlayState { NONE, CHANNELS, CATEGORIES }

    private lateinit var binding: ActivityTvModeBinding
    private var player: ExoPlayer? = null
    private var overlayState = OverlayState.NONE

    private var currentStreamUrl   = ""
    private var currentChannelName = ""
    private var currentStreamId    = -1
    private var currentCategoryId  = ""
    private var categoryChannels: List<LiveStream> = emptyList()

    private val hudHandler  = Handler(Looper.getMainLooper())
    private val hideHud     = Runnable { hideHudOverlay() }
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
        setupBackHandling()
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
        if (PrefsManager.getLivePlayer(this) != PlayerEngine.EXOPLAYER) {
            if (currentStreamUrl.isNotBlank()) playChannel(currentStreamUrl, currentChannelName)
            return
        }
        player = ExoPlayer.Builder(this).build().also { exo ->
            exo.repeatMode = Player.REPEAT_MODE_OFF
            exo.setVideoSurfaceView(binding.surfaceView)
            if (currentStreamUrl.isNotBlank()) {
                exo.setMediaItem(MediaItem.fromUri(currentStreamUrl))
                exo.prepare()
                exo.play()
                showInfoBar(currentChannelName)
                loadEpgForCurrentChannel()
            }
        }
    }

    private fun playChannel(url: String, name: String) {
        when (PrefsManager.getLivePlayer(this)) {
            PlayerEngine.EXOPLAYER -> {
                val exo = player ?: return
                exo.setMediaItem(MediaItem.fromUri(url))
                exo.prepare()
                exo.play()
                showInfoBar(name)
                loadEpgForCurrentChannel()
            }
            PlayerEngine.EXTERNAL -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(android.net.Uri.parse(url), "video/*")
                    })
                } catch (e: Exception) {
                    Toast.makeText(this, "NO EXTERNAL PLAYER FOUND", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_STREAM_URL, url)
                    putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, name)
                    putExtra(PlayerActivity.EXTRA_STREAM_ID, currentStreamId)
                    putExtra(PlayerActivity.EXTRA_IS_LIVE, true)
                })
            }
        }
    }

    private fun setupButtons() {
        val p = ThemeManager.palette()
        binding.btnBackToMenu.setBackgroundColor(p.bgHeader)
        binding.btnBackToMenu.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }
        binding.btnBackToMenu.setOnFocusChangeListener { _, hasFocus ->
            binding.btnBackToMenu.setBackgroundColor(if (hasFocus) ThemeManager.palette().focus else ThemeManager.palette().bgHeader)
        }
        binding.overlayDismiss.setOnClickListener { hideOverlay() }
        // Tapping the video surface shows the HUD
        binding.surfaceView.setOnClickListener { showHudOverlay() }
    }

    private fun setupHudButtons() {
        val p = ThemeManager.palette()

        fun timerReset(v: View) {
            v.setOnFocusChangeListener { _, h ->
                if (h) { hudHandler.removeCallbacks(hideHud); hudHandler.postDelayed(hideHud, 5000L) }
            }
        }

        binding.btnHudMenu.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
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
        binding.surfaceView.requestFocus()
    }

    private fun showInfoBar(name: String) {
        binding.tvChannelName.text = name.uppercase()
        binding.tvHudChannelInfo.text = name.uppercase()
        showHudOverlay()
    }

    private fun loadEpgForCurrentChannel() {
        if (currentStreamId < 0) return
        val creds = PrefsManager.getCredentials(this) ?: return
        // Snapshot before suspend — currentStreamId may change if user switches channel
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
                // Discard result if user has already switched to a different channel
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
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("● START RECORDING")
            .setMessage("Channel: $currentChannelName\nShow: $epgTitle\n\n⚠ Uses 2 connections.")
            .setPositiveButton("RECORD") { _, _ ->
                startForegroundService(Intent(this, RecordingService::class.java).apply {
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
            .setOnDismissListener { showHudOverlay() }
            .show()
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

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (overlayState != OverlayState.NONE) hideOverlay()
                // When NONE: stay in TV mode (do nothing — user must use "MENU" button to leave)
            }
        })
    }

    // ── Overlay state machine ─────────────────────────────────────────────────

    private fun showChannelsOverlay() {
        overlayState = OverlayState.CHANNELS
        binding.tvPanelTitle.text = "CHANNELS"
        refreshCategoryChannels()
        populateChannelList()
        binding.overlayContainer.visibility = View.VISIBLE
        binding.rvList.post {
            val idx = maxOf(0, categoryChannels.indexOfFirst { it.streamId == currentStreamId })
            (binding.rvList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(idx, 0)
            binding.rvList.layoutManager?.findViewByPosition(idx)?.requestFocus()
                ?: binding.rvList.requestFocus()
        }
    }

    private fun showCategoriesOverlay() {
        overlayState = OverlayState.CATEGORIES
        binding.tvPanelTitle.text = "CATEGORIES"
        populateCategoryList()
        binding.rvList.post {
            // Position 0 = Favourites; regular categories start at 1
            val idx = when {
                currentCategoryId == FAV_CATEGORY_ID -> 0
                else -> {
                    val base = TvModeHolder.categories.indexOfFirst { it.categoryId == currentCategoryId }
                    if (base >= 0) base + 1 else 0
                }
            }
            (binding.rvList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(idx, 0)
            binding.rvList.layoutManager?.findViewByPosition(idx)?.requestFocus()
                ?: binding.rvList.requestFocus()
        }
    }

    private fun hideOverlay() {
        overlayState = OverlayState.NONE
        binding.overlayContainer.visibility = View.GONE
        binding.surfaceView.requestFocus()
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

    private fun populateChannelList() {
        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.rvList.itemAnimator = null
        binding.rvList.adapter = ChannelListAdapter(categoryChannels, currentStreamId) { stream ->
            val creds = PrefsManager.getCredentials(this) ?: return@ChannelListAdapter
            val url = repository.buildStreamUrl(creds.serverUrl, creds.username, creds.password, stream.streamId)
            currentStreamId    = stream.streamId
            currentStreamUrl   = url
            currentChannelName = stream.name
            PrefsManager.setLastTvChannel(this, url, stream.name, stream.streamId, currentCategoryId)
            playChannel(url, stream.name)
            hideOverlay()
        }
    }

    private fun populateCategoryList() {
        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.rvList.itemAnimator = null
        val favCat = LiveCategory(FAV_CATEGORY_ID, "★ FAVOURITES", 0)
        val allCats = listOf(favCat) + TvModeHolder.categories
        binding.rvList.adapter = CategoryListAdapter(allCats, currentCategoryId) { cat ->
            currentCategoryId = cat.categoryId
            refreshCategoryChannels()
            showChannelsOverlay()
        }
    }

    private fun loadChannelsInBackground() {
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
        }
    }

    // ── Key dispatch ──────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val hudVisible = binding.hudTop.visibility == View.VISIBLE
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (!hudVisible && overlayState == OverlayState.NONE) {
                        showHudOverlay()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!hudVisible && overlayState == OverlayState.NONE) {
                        changeTvChannel(-1)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!hudVisible && overlayState == OverlayState.NONE) {
                        changeTvChannel(+1)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (overlayState == OverlayState.CHANNELS) {
                        showCategoriesOverlay()
                        return true
                    }
                    if (overlayState == OverlayState.NONE) {
                        // Only let LEFT navigate within HUD if a non-leftmost HUD button has focus
                        val hudNavFocused = currentFocus?.let { f ->
                            f == binding.btnHudAudio || f == binding.btnHudRecord ||
                            f == binding.btnHudScores || f == binding.btnHudNews
                        } ?: false
                        if (!hudNavFocused) {
                            hudHandler.removeCallbacks(hideHud)
                            hideHudOverlay()
                            showChannelsOverlay()
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (overlayState != OverlayState.NONE && currentFocus != binding.btnBackToMenu) {
                        hideOverlay()
                        return true
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
        playChannel(url, stream.name)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        player?.play()
        updateRecordButton()
        if (binding.hudTop.visibility != View.VISIBLE && overlayState == OverlayState.NONE) {
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
        hudHandler.removeCallbacksAndMessages(null)
        tickerHandler.removeCallbacksAndMessages(null)
        newsHandler.removeCallbacksAndMessages(null)
        tickerScrollAnim?.cancel()
        newsScrollAnim?.cancel()
        player?.release()
        player = null
    }
}

// ── Inline adapters ───────────────────────────────────────────────────────────

private class ChannelListAdapter(
    private val channels: List<LiveStream>,
    private val currentId: Int,
    private val onSelect: (LiveStream) -> Unit
) : RecyclerView.Adapter<ChannelListAdapter.VH>() {

    inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val density = parent.resources.displayMetrics.density
        val tv = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * density).toInt())
            setPadding((12 * density).toInt(), 0, (8 * density).toInt(), 0)
            gravity = Gravity.CENTER_VERTICAL
            textSize = 11f
            isFocusable = true; isClickable = true
            setTextColor(0xFFFFFFFF.toInt())
        }
        return VH(tv)
    }

    override fun getItemCount() = channels.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val stream = channels[position]
        val p = ThemeManager.palette()
        val density = holder.tv.resources.displayMetrics.density
        val isPlaying = stream.streamId == currentId
        val normalBg = if (isPlaying) p.highlight else if (position % 2 == 0) 0xFF0A1628.toInt() else 0xFF0D1E38.toInt()

        holder.tv.text = "${position + 1}.  ${stream.name}"
        holder.tv.background = ThemeManager.roundedBg(normalBg, density)
        holder.tv.setTextColor(if (isPlaying) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        holder.tv.setOnFocusChangeListener { _, hasFocus ->
            if (!isPlaying) holder.tv.background = ThemeManager.roundedBg(if (hasFocus) p.focus else normalBg, density)
        }
        holder.tv.setOnClickListener { onSelect(stream) }
    }
}

private class CategoryListAdapter(
    private val categories: List<LiveCategory>,
    private val currentId: String,
    private val onSelect: (LiveCategory) -> Unit
) : RecyclerView.Adapter<CategoryListAdapter.VH>() {

    inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val density = parent.resources.displayMetrics.density
        val tv = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * density).toInt())
            setPadding((12 * density).toInt(), 0, (8 * density).toInt(), 0)
            gravity = Gravity.CENTER_VERTICAL
            textSize = 11f
            isFocusable = true; isClickable = true
            setTextColor(0xFFFFFFFF.toInt())
        }
        return VH(tv)
    }

    override fun getItemCount() = categories.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cat = categories[position]
        val p = ThemeManager.palette()
        val density = holder.tv.resources.displayMetrics.density
        val isCurrent = cat.categoryId == currentId
        val normalBg = if (isCurrent) p.highlight else if (position % 2 == 0) 0xFF0A1628.toInt() else 0xFF0D1E38.toInt()

        holder.tv.text = cat.categoryName.uppercase()
        holder.tv.background = ThemeManager.roundedBg(normalBg, density)
        holder.tv.setTextColor(if (isCurrent) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        holder.tv.setOnFocusChangeListener { _, hasFocus ->
            if (!isCurrent) holder.tv.background = ThemeManager.roundedBg(if (hasFocus) p.focus else normalBg, density)
        }
        holder.tv.setOnClickListener { onSelect(cat) }
    }
}
