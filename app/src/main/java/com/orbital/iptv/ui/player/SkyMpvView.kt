package com.orbital.iptv.ui.player

import android.content.Context
import android.util.AttributeSet
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.Utils
import com.orbital.iptv.utils.PrefsManager

class SkyMpvView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseMPVView(context, attrs) {

    private var initialized = false

    /** Set before calling init(). Selects the appropriate VO for the stream type. */
    var isCatchup: Boolean = false

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

    fun loadExternalSubtitle(path: String) {
        if (!initialized) return
        mpv.command("sub-add \"$path\" select")
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
        val compatibleDecode = PrefsManager.isCompatibleDecode(context)
        if (!isCatchup && !compatibleDecode) {
            // Live TV: zero-copy hardware path — most efficient, no GPU copy overhead.
            // mediacodec_embed + mediacodec is stable for normal live streams.
            setVo("mediacodec_embed")
            mpv.setOptionString("hwdec", "mediacodec")
        } else {
            // Catchup: use vo=gpu so SW-decode fallback works for streams with unknown
            // HEVC profiles (which make MediaCodec return 0x0 and fall back to software).
            // Compatible mode: vo=gpu + pure software decode.
            setVo("gpu")
            mpv.setOptionString("hwdec", if (compatibleDecode) "no" else "mediacodec-copy")
        }
        mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        mpv.setOptionString("ao", "audiotrack,opensles")
        mpv.setOptionString("audio-set-media-role", "yes")
        mpv.setOptionString("tls-verify", "no")
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("network-timeout", "10")
        mpv.setOptionString("cache-pause", "no")
        mpv.setOptionString("demuxer-max-bytes", "${32 * 1024 * 1024}")
        mpv.setOptionString("demuxer-max-back-bytes", "${32 * 1024 * 1024}")
        // Some catchup streams have SPS/PPS headers late in the first GOP — probe more
        // data so MediaCodec gets valid dimensions instead of 0x0 (which causes black screen)
        mpv.setOptionString("demuxer-lavf-probesize", "50000000")
        mpv.setOptionString("demuxer-lavf-analyzeduration", "10000000")
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
