package com.orbital.iptv.ui.epg

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.NumberPicker
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.work.*
import com.orbital.iptv.R
import com.orbital.iptv.data.model.EpgListing
import com.orbital.iptv.data.model.getDecodedTitle
import com.orbital.iptv.data.repository.XtreamRepository
import com.orbital.iptv.databinding.ActivityEpgBinding
import com.orbital.iptv.recording.*
import com.orbital.iptv.ui.player.PlayerActivity
import com.orbital.iptv.utils.EpgCache
import com.orbital.iptv.utils.PrefsManager
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import com.orbital.iptv.utils.ThemeManager

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
        ThemeManager.load(this)
        val p = ThemeManager.palette()
        binding.root.setBackgroundColor(p.bgPrimary)
        binding.root.findViewById<android.view.View>(R.id.layout_header)?.setBackgroundColor(p.bgHeader)
        binding.root.findViewById<android.view.View>(R.id.view_accent)?.setBackgroundColor(p.accent)

        enterFullscreen()

        val streamIds   = intent.getIntegerArrayListExtra(EXTRA_STREAM_IDS)   ?: return finish()
        val streamNames = intent.getStringArrayListExtra(EXTRA_STREAM_NAMES)  ?: return finish()
        val creds       = PrefsManager.getCredentials(this)                   ?: return finish()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { _, hasFocus ->
            binding.btnBack.setBackgroundColor(
                if (hasFocus) p.focus else resources.getColor(com.orbital.iptv.R.color.sky_mid_blue, theme)
            )
        }
        binding.btnBack.requestFocus()

        // Show time window label: today → +6 days
        val sdf = SimpleDateFormat("EEE dd MMM", Locale.UK)
        val endCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 6) }
        binding.tvTimeWindow.text = "${sdf.format(Date())} — ${sdf.format(endCal.time)}"

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

        binding.epgView.onProgrammeSelected = { streamId, channelName, listing ->
            val url = repository.buildStreamUrl(creds.serverUrl, creds.username, creds.password, streamId)
            handleProgrammeTap(streamId, channelName, url, listing)
        }

        // Load EPG: use cache if it has ≥50 entries (full fetch), otherwise re-fetch with limit=100
        rows.forEach { row ->
            scope.launch {
                val cached = EpgCache.get(this@EpgActivity, row.streamId, minCount = 50)
                if (cached != null) {
                    binding.epgView.updateRow(row.streamId, cached)
                } else {
                    val result = repository.getFullChannelEpg(creds.serverUrl, creds.username, creds.password, row.streamId)
                    result.onSuccess { epg ->
                        val listings = epg.listings ?: emptyList()
                        EpgCache.put(this@EpgActivity, row.streamId, listings)
                        binding.epgView.updateRow(row.streamId, listings)
                    }
                }
            }
        }
    }

    // ── Recording from EPG ────────────────────────────────────────────────────

    private fun handleProgrammeTap(streamId: Int, channelName: String, url: String, listing: EpgListing) {
        val title    = listing.getDecodedTitle().ifBlank { "Recording" }
        val startMs  = (listing.startTimestamp?.toLongOrNull() ?: 0L) * 1000L
        val endMs    = (listing.stopTimestamp?.toLongOrNull()  ?: 0L) * 1000L
        val nowMs    = System.currentTimeMillis()

        if (endMs > 0L && endMs <= nowMs) {
            AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
                .setTitle("CANNOT RECORD")
                .setMessage("'$title' has already finished.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val timeFmt  = SimpleDateFormat("HH:mm", Locale.UK)
        val dateFmt  = SimpleDateFormat("EEE dd MMM", Locale.UK)
        val availGb  = RecordingRepository.availableGb(this)

        val timeStr = if (startMs > 0L) {
            val dateStr = dateFmt.format(Date(startMs))
            val endStr  = if (endMs > 0L) timeFmt.format(Date(endMs)) else "?"
            "$dateStr  ${timeFmt.format(Date(startMs))} — $endStr"
        } else "Time unknown"

        val msg = "$channelName\n$title\n$timeStr\n\nAvailable storage: ${"%.1f".format(availGb)} GB"

        val label = if (startMs > nowMs) "SCHEDULE RECORDING" else "RECORD NOW (ongoing)"
        val builder = AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle(label)
            .setMessage(msg)
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
    }

    private fun scheduleReminder(channelName: String, title: String, startMs: Long, streamUrl: String, streamId: Int) {
        val delayMs = (startMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val data = Data.Builder()
            .putString(ReminderWorker.KEY_TITLE,      title)
            .putString(ReminderWorker.KEY_CHANNEL,    channelName)
            .putString(ReminderWorker.KEY_STREAM_URL, streamUrl)
            .putInt(ReminderWorker.KEY_STREAM_ID,     streamId)
            .build()
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag("reminder_${title.hashCode()}")
            .build()
        WorkManager.getInstance(this).enqueue(request)
        val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)
            .format(java.util.Date(startMs))
        Toast.makeText(this, "REMINDER SET: $title at $timeStr", Toast.LENGTH_SHORT).show()
    }

    private fun askForDuration(onChosen: (Long) -> Unit) {
        val options = arrayOf("30 minutes", "1 hour", "1 hour 30 min", "2 hours", "3 hours")
        val durations = longArrayOf(30, 60, 90, 120, 180)
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("RECORDING DURATION")
            .setMessage("No end time found in EPG. How long should we record?")
            .setItems(options) { _, i -> onChosen(durations[i] * 60_000L) }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun scheduleRecording(streamId: Int, channelName: String, url: String, title: String, startMs: Long, endMs: Long) {
        scope.launch(Dispatchers.IO) {
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
            val id = RecordingDatabase.get(this@EpgActivity).dao().insert(recording).toInt()

            val delayMs = startMs - System.currentTimeMillis()
            if (delayMs <= 0L) {
                // Start immediately
                this@EpgActivity.startForegroundService(
                    android.content.Intent(this@EpgActivity, RecordingService::class.java).apply {
                        putExtra(RecordingService.EXTRA_RECORDING_ID, id)
                    }
                )
            } else {
                WorkManager.getInstance(this@EpgActivity).enqueue(
                    OneTimeWorkRequestBuilder<RecordingWorker>()
                        .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                        .setInputData(workDataOf("recording_id" to id))
                        .addTag("rec_$id")
                        .build()
                )
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@EpgActivity, "RECORDING SCHEDULED: $title", Toast.LENGTH_SHORT).show()
            }
          } catch (e: Exception) {
              withContext(Dispatchers.Main) {
                  Toast.makeText(this@EpgActivity, "SCHEDULE FAILED: ${e.message}", Toast.LENGTH_LONG).show()
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
