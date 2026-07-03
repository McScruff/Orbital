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
 *
 * The tradeoff: DEFAULT_AUDIO_CAPABILITIES is stereo-only (2 channels), so 5.1/7.1 sources are
 * always downmixed — never true surround. [allowSurround] opts back into querying the device's
 * real reported audio capabilities (multichannel PCM / passthrough where actually supported),
 * for testing on setups known to handle it; this can reintroduce the silent-passthrough bug on
 * setups that lie about their capabilities, which is why it's off by default.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PcmOnlyRenderersFactory(
    context: Context,
    private val allowSurround: Boolean = false
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        val capabilities = if (allowSurround) {
            AudioCapabilities.getCapabilities(context)
        } else {
            AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
        }
        return DefaultAudioSink.Builder(context)
            .setAudioCapabilities(capabilities)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}
