package com.orbital.iptv.ui.player

import androidx.media3.exoplayer.DefaultLoadControl

/**
 * Default ExoPlayer buffering targets ~50s, tuned for VOD — far behind live for no benefit.
 * Keeps live playback within 3s of real time; after a stall, waits for a fresh 3s before
 * resuming so it doesn't immediately stall again.
 */
object LiveLoadControl {
    fun build(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(3000, 3000, 1500, 3000)
        .build()
}
