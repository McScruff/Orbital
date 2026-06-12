package com.orbital.iptv

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

class OrbitalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) =
                a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
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
