package com.orbital.iptv.recording

import android.os.Handler
import android.os.Looper

object RecordingState {
    // Set by PlayerActivity while live TV is playing
    @Volatile var isLiveTvActive = false
    @Volatile var liveTvChannelUrl: String? = null
    @Volatile var liveTvChannelName: String? = null

    // Set by RecordingService while a Record-Now session is active
    @Volatile var activeRecordNowId: Int = -1
    @Volatile var activeRecordNowUrl: String? = null

    // Callback registered by PlayerActivity — invoked when the service needs it to stop
    private var stopLiveTvCallback: (() -> Unit)? = null

    fun registerStopLiveTv(callback: () -> Unit) { stopLiveTvCallback = callback }
    fun unregisterStopLiveTv() { stopLiveTvCallback = null }
    fun postStopLiveTv() {
        Handler(Looper.getMainLooper()).post { stopLiveTvCallback?.invoke() }
    }
}
