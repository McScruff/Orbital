package com.orbital.iptv.data.model

enum class FavType { MOVIE, EPISODE, LIVE }

data class FavouriteItem(
    val id: String,
    val type: FavType,
    val title: String,
    val artUrl: String = "",
    val streamUrl: String = "",
    val streamId: Int = 0,
    val resumePositionMs: Long = 0L,
    val durationMs: Long = 0L,
    // Episode fields
    val seriesId: Int = -1,
    val season: String = "",
    val episodeNum: Int = 0,
    val episodeId: String = "",
    val nextEpisodeUrl: String = "",
    val nextEpisodeTitle: String = "",
    val nextEpisodeSeason: String = "",
    val nextEpisodeNum: Int = 0,
    val nextEpisodeId: String = "",
    val addedAt: Long = System.currentTimeMillis()
) {
    val progressFraction: Float
        get() = if (durationMs > 0 && resumePositionMs > 0)
            (resumePositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val hasResume: Boolean
        get() = resumePositionMs > 30_000L && durationMs > 0

    val hasNextEpisode: Boolean
        get() = nextEpisodeUrl.isNotEmpty()
}
