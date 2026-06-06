package com.orbital.iptv.utils

import android.os.Handler
import android.os.Looper

object ReminderBus {

    data class Reminder(
        val title: String,
        val channelName: String,
        val streamUrl: String,
        val streamId: Int
    )

    private var listener: ((Reminder) -> Unit)? = null

    fun register(l: (Reminder) -> Unit)  { listener = l }
    fun unregister()                      { listener = null }

    fun post(reminder: Reminder) {
        Handler(Looper.getMainLooper()).post { listener?.invoke(reminder) }
    }
}
