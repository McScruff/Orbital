package com.orbital.iptv

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class OrbitalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    REMINDER_CHANNEL_ID,
                    "Programme Reminders",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when your programmes are about to start"
                }
            )
        }
    }

    companion object {
        const val REMINDER_CHANNEL_ID = "orbital_reminders"
    }
}
