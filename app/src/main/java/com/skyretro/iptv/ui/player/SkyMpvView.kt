package com.skyretro.iptv.ui.player

import android.content.Context
import android.util.AttributeSet
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.Utils

class SkyMpvView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseMPVView(context, attrs) {

    private var initialized = false

    fun init() {
        if (initialized) return
        Utils.copyAssets(context)
        initialize(context.filesDir.path, context.cacheDir.path)
        initialized = true
    }

    fun loadUrl(url: String, startMs: Long = 0L) {
        if (!initialized) return
        if (startMs > 0L) mpv.setPropertyString("start", (startMs / 1000).toString())
        playFile(url)
    }

    fun seekTo(ms: Long) {
        if (!initialized) return
        mpv.setPropertyDouble("time-pos", (ms.coerceAtLeast(0L) / 1000.0))
    }

    fun getCurrentPositionMs(): Long {
        if (!initialized) return 0L
        return ((mpv.getPropertyDouble("time-pos") ?: 0.0) * 1000.0).toLong().coerceAtLeast(0L)
    }

    fun getDurationMs(): Long {
        if (!initialized) return 0L
        return ((mpv.getPropertyDouble("duration") ?: 0.0) * 1000.0).toLong()
    }

    fun setPaused(paused: Boolean) {
        if (!initialized) return
        mpv.setPropertyBoolean("pause", paused)
    }

    fun isPaused(): Boolean = if (initialized) mpv.getPropertyBoolean("pause") ?: true else true

    fun isBuffering(): Boolean = if (initialized) mpv.getPropertyBoolean("paused-for-cache") ?: false else false

    fun isIdle(): Boolean = if (initialized) mpv.getPropertyBoolean("core-idle") ?: true else true

    fun getAudioTracks(): List<MpvTrack> = tracksOfType("audio")

    fun getSubtitleTracks(): List<MpvTrack> = tracksOfType("sub")

    fun selectAudioTrack(id: Int) {
        if (!initialized) return
        mpv.setPropertyInt("aid", id)
    }

    fun selectSubtitleTrack(id: Int) {
        if (!initialized) return
        mpv.setPropertyBoolean("sub-visibility", true)
        mpv.setPropertyInt("sid", id)
    }

    fun disableSubtitles() {
        if (!initialized) return
        mpv.setPropertyString("sid", "no")
        mpv.setPropertyBoolean("sub-visibility", false)
    }

    fun release() {
        if (!initialized) return
        runCatching { destroy() }
        initialized = false
    }

    private fun tracksOfType(type: String): List<MpvTrack> {
        if (!initialized) return emptyList()
        val count = mpv.getPropertyInt("track-list/count") ?: return emptyList()
        return (0 until count).mapNotNull { i ->
            if (mpv.getPropertyString("track-list/$i/type") != type) return@mapNotNull null
            val id = mpv.getPropertyInt("track-list/$i/id") ?: return@mapNotNull null
            MpvTrack(
                id       = id,
                language = mpv.getPropertyString("track-list/$i/lang"),
                title    = mpv.getPropertyString("track-list/$i/title"),
                codec    = mpv.getPropertyString("track-list/$i/codec"),
                selected = mpv.getPropertyBoolean("track-list/$i/selected") ?: false
            )
        }
    }

    override fun initOptions() {
        mpv.setOptionString("profile", "fast")
        setVo("mediacodec_embed")
        mpv.setOptionString("hwdec", "mediacodec")
        mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        mpv.setOptionString("ao", "audiotrack,opensles")
        mpv.setOptionString("audio-set-media-role", "yes")
        mpv.setOptionString("tls-verify", "no")
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("network-timeout", "10")
        mpv.setOptionString("cache-pause", "no")
        mpv.setOptionString("demuxer-max-bytes", "${32 * 1024 * 1024}")
        mpv.setOptionString("demuxer-max-back-bytes", "${32 * 1024 * 1024}")
    }

    override fun postInitOptions() {}

    override fun observeProperties() {
        // Using polling in PlayerActivity instead
    }
}

data class MpvTrack(
    val id: Int,
    val language: String?,
    val title: String?,
    val codec: String?,
    val selected: Boolean
)
