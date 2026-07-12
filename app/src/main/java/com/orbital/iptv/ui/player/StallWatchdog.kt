package com.orbital.iptv.ui.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player

/**
 * Catches a "silent" live-stream stall that ExoPlayer never reports as an error: prolonged
 * buffering, or STATE_READY/isPlaying=true with playback position not actually advancing.
 * Seen on malformed broadcast-grade MPEG-TS that VLC's more tolerant demuxer plays through
 * but media3's TsExtractor just stops on — no exception, no callback, just a frozen frame.
 * Polls every [POLL_MS] and fires [onStall] once a stall has persisted for [STALL_TIMEOUT_MS].
 */
class StallWatchdog(
    private val getPlayer: () -> Player?,
    private val onStall: () -> Unit
) {
    companion object {
        private const val POLL_MS = 5_000L
        private const val STALL_TIMEOUT_MS = 15_000L
        private const val POSITION_EPSILON_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var bufferingSinceMs = -1L
    private var lastPosition = -1L
    private var frozenSinceMs = -1L

    private val tick = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        stop()
        reset()
        handler.postDelayed(tick, POLL_MS)
    }

    fun stop() {
        handler.removeCallbacks(tick)
    }

    /** Clears the stall-detection window — call after any deliberate reconnect/channel change. */
    fun reset() {
        bufferingSinceMs = -1L
        lastPosition = -1L
        frozenSinceMs = -1L
    }

    private fun check() {
        val player = getPlayer() ?: return
        val now = SystemClock.elapsedRealtime()

        if (player.playbackState == Player.STATE_BUFFERING) {
            frozenSinceMs = -1L
            lastPosition = -1L
            if (bufferingSinceMs < 0L) {
                bufferingSinceMs = now
            } else if (now - bufferingSinceMs >= STALL_TIMEOUT_MS) {
                reset()
                onStall()
            }
            return
        }
        bufferingSinceMs = -1L

        if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
            val pos = player.currentPosition
            if (lastPosition < 0L || pos > lastPosition + POSITION_EPSILON_MS) {
                lastPosition = pos
                frozenSinceMs = -1L
            } else {
                if (frozenSinceMs < 0L) {
                    frozenSinceMs = now
                } else if (now - frozenSinceMs >= STALL_TIMEOUT_MS) {
                    reset()
                    onStall()
                }
            }
        } else {
            lastPosition = -1L
            frozenSinceMs = -1L
        }
    }
}
