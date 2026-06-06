package com.orbital.iptv.data.model

import com.google.gson.annotations.SerializedName

data class XtreamCredentials(
    val serverUrl: String,
    val username: String,
    val password: String
)

data class ServerProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val serverUrl: String,
    val username: String,
    val password: String
) {
    fun toCredentials() = XtreamCredentials(serverUrl, username, password)
}

data class ServerInfo(
    @SerializedName("user_info") val userInfo: UserInfo?,
    @SerializedName("server_info") val serverInfo: ServerDetails?
)

data class UserInfo(
    @SerializedName("username") val username: String?,
    @SerializedName("password") val password: String?,
    @SerializedName("status") val status: String?,
    @SerializedName("exp_date") val expDate: String?,
    @SerializedName("is_trial") val isTrial: String?,
    @SerializedName("active_cons") val activeCons: String?,
    @SerializedName("max_connections") val maxConnections: String?,
    @SerializedName("allowed_output_formats") val allowedOutputFormats: List<String>?
)

data class ServerDetails(
    @SerializedName("url") val url: String?,
    @SerializedName("port") val port: String?,
    @SerializedName("https_port") val httpsPort: String?,
    @SerializedName("server_protocol") val serverProtocol: String?,
    @SerializedName("rtmp_port") val rtmpPort: String?,
    @SerializedName("timezone") val timezone: String?,
    @SerializedName("timestamp_now") val timestampNow: Long?,
    @SerializedName("time_now") val timeNow: String?
)

data class LiveCategory(
    @SerializedName("category_id") val categoryId: String,
    @SerializedName("category_name") val categoryName: String,
    @SerializedName("parent_id") val parentId: Int
)

data class LiveStream(
    @SerializedName("num") val num: Int?,
    @SerializedName("name") val name: String,
    @SerializedName("stream_type") val streamType: String?,
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("epg_channel_id") val epgChannelId: String?,
    @SerializedName("added") val added: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("tv_archive") val tvArchive: Int?,
    @SerializedName("direct_source") val directSource: String?,
    @SerializedName("tv_archive_duration") val tvArchiveDuration: Int?,
    var epgNow: String? = null
)

data class EpgResponse(
    @SerializedName("epg_listings") val listings: List<EpgListing>?
)

data class EpgListing(
    @SerializedName("id") val id: String?,
    @SerializedName("epg_id") val epgId: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("start") val start: String?,
    @SerializedName("end") val end: String?,
    @SerializedName("start_timestamp") val startTimestamp: String?,
    @SerializedName("stop_timestamp") val stopTimestamp: String?
)

fun EpgListing.getDecodedTitle(): String {
    return android.util.Base64.decode(title ?: "", android.util.Base64.DEFAULT).decodeToString()
}

data class VodCategory(
    @SerializedName("category_id") val categoryId: String,
    @SerializedName("category_name") val categoryName: String,
    @SerializedName("parent_id") val parentId: Int
)

data class VodStream(
    @SerializedName("num") val num: Int?,
    @SerializedName("name") val name: String,
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("rating_5based") val rating5: Double?,
    @SerializedName("added") val added: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("container_extension") val containerExtension: String?
)

data class VodInfoResponse(
    @SerializedName("info") val info: MovieInfo?,
    @SerializedName("movie_data") val movieData: VodStream?
)

data class MovieInfo(
    @SerializedName("name") val name: String?,
    @SerializedName("o_name") val oName: String?,
    @SerializedName("cover_big") val coverBig: String?,
    @SerializedName("movie_image") val movieImage: String?,
    @SerializedName("releasedate") val releaseDate: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("actors") val actors: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("country") val country: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("duration") val duration: String?,
    @SerializedName("duration_secs") val durationSecs: Int?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("youtube_trailer") val youtubeTrailer: String?
)

data class SeriesCategory(
    @SerializedName("category_id") val categoryId: String,
    @SerializedName("category_name") val categoryName: String,
    @SerializedName("parent_id") val parentId: Int
)

data class SeriesStream(
    @SerializedName("series_id") val seriesId: Int,
    @SerializedName("name") val name: String,
    @SerializedName("cover") val cover: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("releaseDate") val releaseDate: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("rating_5based") val rating5: Double?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("category_ids") val categoryIds: List<String>?
)

data class SeriesInfoResponse(
    @SerializedName("info") val info: SeriesInfoDetail?,
    @SerializedName("episodes") val episodes: Map<String, List<Episode>>?
)


data class SeriesInfoDetail(
    @SerializedName("name") val name: String?,
    @SerializedName("cover") val cover: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("releaseDate") val releaseDate: String?,
    @SerializedName("rating") val rating: String?
)

data class Episode(
    @SerializedName("id") val id: String,
    @SerializedName("episode_num") val episodeNum: Int?,
    @SerializedName("title") val title: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("info") val info: EpisodeInfo?,
    @SerializedName("season") val season: Int?
)

data class EpisodeInfo(
    @SerializedName("duration_secs") val durationSecs: Int?,
    @SerializedName("duration") val duration: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("still_image") val stillImage: String?
)

// Sky UK category mapping for the retro guide
enum class SkyCategory(val displayName: String, val number: Int) {
    TV_GUIDE("TV GUIDE LISTINGS", 1),
    ENTERTAINMENT("ENTERTAINMENT", 2),
    MOVIES("MOVIES", 3),
    SPORTS("SPORTS", 4),
    NEWS_DOCUMENTARIES("NEWS & DOCUMENTARIES", 5),
    CHILDREN("CHILDREN", 6),
    MUSIC_SPECIALIST("MUSIC & SPECIALIST", 7),
    OTHER_CHANNELS("OTHER CHANNELS", 8)
}

fun mapCategoryToSky(categoryName: String): SkyCategory {
    val lower = categoryName.lowercase()
    return when {
        lower.contains("sport") || lower.contains("football") || lower.contains("cricket") -> SkyCategory.SPORTS
        lower.contains("movie") || lower.contains("film") || lower.contains("cinema") -> SkyCategory.MOVIES
        lower.contains("news") || lower.contains("documentary") || lower.contains("documentar") -> SkyCategory.NEWS_DOCUMENTARIES
        lower.contains("child") || lower.contains("kid") || lower.contains("cartoon") || lower.contains("junior") -> SkyCategory.CHILDREN
        lower.contains("music") || lower.contains("radio") || lower.contains("mtv") -> SkyCategory.MUSIC_SPECIALIST
        lower.contains("entertainment") || lower.contains("drama") || lower.contains("comedy") -> SkyCategory.ENTERTAINMENT
        else -> SkyCategory.OTHER_CHANNELS
    }
}
