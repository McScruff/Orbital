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
    val addedAt: Long = System.currentTimeMillis(),
    // True only for the entry PlayerActivity auto-creates for the next episode right after the
    // current one finishes — distinguishes "queued up next, not started" from a manually
    // favourited-but-unwatched episode, which looks identical otherwise (both start at 0/0).
    val autoQueued: Boolean = false
) {
    val progressFraction: Float
        get() = if (durationMs > 0 && resumePositionMs > 0)
            (resumePositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val hasResume: Boolean
        get() = resumePositionMs > 30_000L && durationMs > 0

    /** Queued next episode that hasn't actually been started yet — once playback begins and
     *  real progress is saved, [hasResume] takes over and this goes false on its own. */
    val isUpNext: Boolean
        get() = autoQueued && !hasResume

    val hasNextEpisode: Boolean
        get() = nextEpisodeUrl.isNotEmpty()
}
