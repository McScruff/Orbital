package com.orbital.iptv.recording

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import com.orbital.iptv.ui.player.PlayerActivity
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class RecordingService : Service() {

    companion object {
        const val CHANNEL_ID = "orbital_recording"
        const val NOTIF_ID   = 5001

        const val ACTION_STOP      = "com.orbital.iptv.rec.STOP"
        const val ACTION_SKIP      = "com.orbital.iptv.rec.SKIP"
        const val ACTION_USE_TWO   = "com.orbital.iptv.rec.USE_TWO"
        const val ACTION_STOP_LIVE = "com.orbital.iptv.rec.STOP_LIVE"
        const val ACTION_GO_CHAN   = "com.orbital.iptv.rec.GO_CHAN"

        const val EXTRA_RECORDING_ID  = "recording_id"
        const val EXTRA_CHANNEL_NAME  = "channel_name"
        const val EXTRA_CHANNEL_URL   = "channel_url"
        const val EXTRA_STREAM_ID     = "stream_id"
        const val EXTRA_EPG_TITLE     = "epg_title"
        const val EXTRA_SCHEDULED_END = "scheduled_end"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null
    private var stopHandler: Handler? = null
    private var currentRec: RecordingEntity? = null
    // Set true when ACTION_STOP arrives before the job even starts (race condition guard)
    private var stopRequested = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // unlimited — live stream never "ends"
        .build()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()

            ACTION_SKIP -> {
                val id = intent.getIntExtra(EXTRA_RECORDING_ID, -1)
                if (id >= 0) scope.launch {
                    RecordingDatabase.get(applicationContext).dao()
                        .updateStatus(id, RecordingStatus.SKIPPED)
                }
                clearActiveState()
                androidx.core.app.ServiceCompat.stopForeground(this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_USE_TWO -> startRecording()

            ACTION_STOP_LIVE -> {
                RecordingState.postStopLiveTv()
                startRecording()
            }

            ACTION_GO_CHAN -> {
                currentRec?.let { rec ->
                    startActivity(Intent(this, PlayerActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra(PlayerActivity.EXTRA_STREAM_URL,   rec.channelUrl)
                        putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, rec.channelName)
                        putExtra(PlayerActivity.EXTRA_STREAM_ID,    rec.streamId)
                        putExtra(PlayerActivity.EXTRA_IS_LIVE,      true)
                    })
                }
                startRecording()
            }

            else -> handleNewRecording(intent)
        }
        return START_NOT_STICKY
    }

    private fun handleNewRecording(intent: Intent?) {
        stopRequested = false
        val dbId = intent?.getIntExtra(EXTRA_RECORDING_ID, -1) ?: -1
        scope.launch {
            val rec = if (dbId >= 0) {
                RecordingDatabase.get(applicationContext).dao().byId(dbId)
            } else {
                val entity = RecordingEntity(
                    channelName    = intent?.getStringExtra(EXTRA_CHANNEL_NAME) ?: "",
                    channelUrl     = intent?.getStringExtra(EXTRA_CHANNEL_URL) ?: "",
                    streamId       = intent?.getIntExtra(EXTRA_STREAM_ID, -1) ?: -1,
                    epgTitle       = intent?.getStringExtra(EXTRA_EPG_TITLE) ?: "",
                    scheduledStart = System.currentTimeMillis(),
                    scheduledEnd   = intent?.getLongExtra(EXTRA_SCHEDULED_END, 0L) ?: 0L,
                    status         = RecordingStatus.SCHEDULED
                )
                val newId = RecordingDatabase.get(applicationContext).dao().insert(entity).toInt()
                entity.copy(id = newId)
            }
            if (rec == null) { stopSelf(); return@launch }
            currentRec = rec

            // If stop was already requested before we got here, mark completed and quit
            if (stopRequested) {
                RecordingDatabase.get(applicationContext).dao()
                    .updateStatus(rec.id, RecordingStatus.COMPLETED)
                clearActiveState()
                withContext(Dispatchers.Main) { stopSelf() }
                return@launch
            }

            val isConflict = RecordingState.isLiveTvActive &&
                RecordingState.liveTvChannelUrl != rec.channelUrl

            withContext(Dispatchers.Main) {
                if (isConflict) showConflictNotification(rec)
                else startRecording()
            }
        }
    }

    private fun startRecording() {
        val rec = currentRec ?: return
        if (stopRequested) {
            scope.launch {
                RecordingDatabase.get(applicationContext).dao()
                    .updateStatus(rec.id, RecordingStatus.COMPLETED)
            }
            clearActiveState()
            stopSelf()
            return
        }

        val dir  = RecordingRepository.getDir(applicationContext)
        val name = RecordingRepository.buildFilename(rec.channelName, rec.epgTitle, rec.scheduledStart)
        val file = File(dir, name)

        showRecordingNotification(rec.channelName, rec.epgTitle)

        recordingJob = scope.launch {
            // ── 1. Persist the file path before we write a single byte ─────────
            val recWithPath = rec.copy(filePath = file.absolutePath, status = RecordingStatus.RECORDING)
            currentRec = recWithPath
            RecordingDatabase.get(applicationContext).dao().update(recWithPath)

            RecordingState.activeRecordNowId  = rec.id
            RecordingState.activeRecordNowUrl = rec.channelUrl

            // ── 2. Stream to file ──────────────────────────────────────────────
            var finalStatus = RecordingStatus.FAILED
            try {
                val request = Request.Builder()
                    .url(rec.channelUrl)
                    .header("User-Agent", USER_AGENT)
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@launch
                    response.body?.byteStream()?.use { input ->
                        file.outputStream().use { output ->
                            val buf = ByteArray(65536)
                            while (isActive) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                            }
                        }
                    }
                }
                // Reached here cleanly (stream ended or isActive went false)
                finalStatus = RecordingStatus.COMPLETED
            } catch (_: Exception) {
                // IOException, network error, etc.
                // If the job was cancelled cleanly, treat as completed
                if (!isActive) finalStatus = RecordingStatus.COMPLETED
            } finally {
                // ── NonCancellable: these MUST run even if job was cancelled ───
                withContext(NonCancellable) {
                    RecordingDatabase.get(applicationContext).dao()
                        .updateStatus(recWithPath.id, finalStatus)
                    clearActiveState()
                    withContext(Dispatchers.Main) { stopSelf() }
                }
            }
        }

        // Auto-stop at scheduledEnd (0 = unlimited / Record Now)
        if (rec.scheduledEnd > 0L) {
            val remaining = rec.scheduledEnd - System.currentTimeMillis()
            if (remaining > 0L) {
                stopHandler = Handler(Looper.getMainLooper())
                stopHandler!!.postDelayed({ stopRecording() }, remaining)
            }
        }
    }

    private fun stopRecording() {
        stopHandler?.removeCallbacksAndMessages(null)
        stopRequested = true
        if (recordingJob != null) {
            recordingJob?.cancel()   // finally{NonCancellable} will update DB and call stopSelf()
        } else {
            // Job hasn't started yet — update DB here and stop
            val rec = currentRec
            if (rec != null) {
                scope.launch {
                    RecordingDatabase.get(applicationContext).dao()
                        .updateStatus(rec.id, RecordingStatus.COMPLETED)
                    clearActiveState()
                    withContext(Dispatchers.Main) { stopSelf() }
                }
            } else {
                stopSelf()
            }
        }
    }

    private fun clearActiveState() {
        RecordingState.activeRecordNowId  = -1
        RecordingState.activeRecordNowUrl = null
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun pi(action: String, requestCode: Int, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, RecordingService::class.java).apply { this.action = action; extras() },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun showRecordingNotification(channelName: String, showTitle: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("● RECORDING: ${channelName.uppercase()}")
            .setContentText(showTitle.ifBlank { "Recording in progress" })
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "STOP RECORDING", pi(ACTION_STOP, 0))
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun showConflictNotification(rec: RecordingEntity) {
        val skipPi = PendingIntent.getService(
            this, 10,
            Intent(this, RecordingService::class.java).apply {
                action = ACTION_SKIP
                putExtra(EXTRA_RECORDING_ID, rec.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠ RECORDING CONFLICT")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Scheduled: '${rec.epgTitle}' on ${rec.channelName}. " +
                "You are watching a different channel on the same connection."
            ))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "SKIP RECORDING",    skipPi)
            .addAction(0, "STOP WATCHING",     pi(ACTION_STOP_LIVE, 11))
            .addAction(0, "GO TO CHANNEL",     pi(ACTION_GO_CHAN,   12))
            .addAction(0, "USE 2 CONNECTIONS", pi(ACTION_USE_TWO,   13))
            .build()
        startForeground(NOTIF_ID, notif)
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        stopHandler?.removeCallbacksAndMessages(null)
        recordingJob?.cancel()
        scope.cancel()
    }
}
