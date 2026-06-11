package com.orbital.iptv.ui.player

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Forces PCM-only audio output, disabling AC3/EAC3 passthrough.
 *
 * Without this, DefaultAudioSink detects that the audio route (HAL, HDMI, BT) claims
 * direct AC3 playback support and returns FORMAT_HANDLED — but many routes accept the
 * encoded bitstream and play silence. Locking to DEFAULT_AUDIO_CAPABILITIES (stereo PCM)
 * forces ExoPlayer to use a real decoder (FFmpeg extension via EXTENSION_RENDERER_MODE_PREFER)
 * instead of passthrough, which always produces audible PCM output.
 */
class PcmOnlyRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .build()
}
