package com.orbital.iptv.recording

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.orbital.iptv.R
import com.orbital.iptv.OrbitalApp
import com.orbital.iptv.ui.home.HomeActivity
import com.orbital.iptv.utils.ReminderBus

class ReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val title     = inputData.getString(KEY_TITLE)      ?: "Programme"
        val channel   = inputData.getString(KEY_CHANNEL)    ?: ""
        val streamUrl = inputData.getString(KEY_STREAM_URL) ?: ""
        val streamId  = inputData.getInt(KEY_STREAM_ID, -1)

        // In-app popup — posts to main thread, fires if app is in foreground
        ReminderBus.post(ReminderBus.Reminder(title, channel, streamUrl, streamId))

        // Notification fallback for when app is backgrounded
        val pi = PendingIntent.getActivity(
            applicationContext, title.hashCode(),
            Intent(applicationContext, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, OrbitalApp.REMINDER_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("📺  STARTING NOW")
            .setContentText(if (channel.isNotBlank()) "$title  —  $channel" else title)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(if (channel.isNotBlank()) "$title\n$channel" else title))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        // POST_NOTIFICATIONS is a runtime permission on Android 13+ — notify() throws if denied
        try {
            val nm = NotificationManagerCompat.from(applicationContext)
            if (nm.areNotificationsEnabled()) nm.notify(title.hashCode(), notification)
        } catch (_: SecurityException) {}

        return Result.success()
    }

    companion object {
        const val KEY_TITLE      = "reminder_title"
        const val KEY_CHANNEL    = "reminder_channel"
        const val KEY_STREAM_URL = "reminder_stream_url"
        const val KEY_STREAM_ID  = "reminder_stream_id"
    }
}
