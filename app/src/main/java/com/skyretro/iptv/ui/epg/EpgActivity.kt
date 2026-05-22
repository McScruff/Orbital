package com.skyretro.iptv.ui.epg

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.skyretro.iptv.data.repository.XtreamRepository
import com.skyretro.iptv.databinding.ActivityEpgBinding
import com.skyretro.iptv.ui.player.PlayerActivity
import com.skyretro.iptv.utils.EpgCache
import com.skyretro.iptv.utils.PrefsManager
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class EpgActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_IDS   = "epg_stream_ids"
        const val EXTRA_STREAM_NAMES = "epg_stream_names"
        private const val MAX_CHANNELS = 25
    }

    private lateinit var binding: ActivityEpgBinding
    private val repository = XtreamRepository()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityEpgBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterFullscreen()

        val streamIds   = intent.getIntegerArrayListExtra(EXTRA_STREAM_IDS)   ?: return finish()
        val streamNames = intent.getStringArrayListExtra(EXTRA_STREAM_NAMES)  ?: return finish()
        val creds       = PrefsManager.getCredentials(this)                   ?: return finish()

        binding.btnBack.setOnClickListener { finish() }

        // Show time window label
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val nowMs = System.currentTimeMillis()
        binding.tvTimeWindow.text = "${sdf.format(Date(nowMs - 30 * 60_000))} — ${sdf.format(Date(nowMs + 90 * 60_000))}"

        val rows = streamIds.zip(streamNames).take(MAX_CHANNELS).map { (id, name) ->
            EpgRow(streamId = id, channelName = name)
        }

        binding.epgView.setRows(rows)
        binding.epgView.onChannelSelected = { streamId ->
            val name = rows.find { it.streamId == streamId }?.channelName ?: ""
            val url  = repository.buildStreamUrl(creds.serverUrl, creds.username, creds.password, streamId)
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_STREAM_URL, url)
                putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, name)
                putExtra(PlayerActivity.EXTRA_STREAM_ID, streamId)
            })
        }

        // Load EPG: serve from cache, fetch from API only if stale
        rows.forEach { row ->
            scope.launch {
                val cached = EpgCache.get(this@EpgActivity, row.streamId)
                if (cached != null) {
                    binding.epgView.updateRow(row.streamId, cached)
                } else {
                    val result = repository.getShortEpg(creds.serverUrl, creds.username, creds.password, row.streamId)
                    result.onSuccess { epg ->
                        val listings = epg.listings ?: emptyList()
                        EpgCache.put(this@EpgActivity, row.streamId, listings)
                        binding.epgView.updateRow(row.streamId, listings)
                    }
                }
            }
        }
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        enterFullscreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
