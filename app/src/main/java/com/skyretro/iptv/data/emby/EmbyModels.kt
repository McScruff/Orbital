package com.skyretro.iptv.data.emby

import com.google.gson.annotations.SerializedName

data class EmbyAuthRequest(
    @SerializedName("Username") val username: String,
    @SerializedName("Pw") val pw: String
)

data class EmbyUser(
    @SerializedName("Id") val id: String = "",
    @SerializedName("Name") val name: String = ""
)

data class EmbyAuthResponse(
    @SerializedName("User") val user: EmbyUser = EmbyUser(),
    @SerializedName("AccessToken") val accessToken: String = ""
)

data class EmbyUserData(
    @SerializedName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0L,
    @SerializedName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerializedName("Played") val played: Boolean = false
)

data class EmbyItem(
    @SerializedName("Id") val id: String = "",
    @SerializedName("Name") val name: String = "",
    @SerializedName("Type") val type: String = "",
    @SerializedName("CollectionType") val collectionType: String? = null,
    @SerializedName("IndexNumber") val indexNumber: Int? = null,
    @SerializedName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerializedName("SeriesName") val seriesName: String? = null,
    @SerializedName("SeasonName") val seasonName: String? = null,
    @SerializedName("ProductionYear") val productionYear: Int? = null,
    @SerializedName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerializedName("UserData") val userData: EmbyUserData? = null,
    @SerializedName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerializedName("SeriesPrimaryImageTag") val seriesPrimaryImageTag: String? = null,
    @SerializedName("SeriesId") val seriesId: String? = null,
    @SerializedName("SeasonId") val seasonId: String? = null
) {
    fun resumeMs(): Long = (userData?.playbackPositionTicks ?: 0L) / 10_000L

    fun subtitle(): String = when (type) {
        "Episode" -> {
            val s = parentIndexNumber?.let { "S$it" } ?: ""
            val e = indexNumber?.let { "E%02d".format(it) } ?: ""
            val ep = "$s$e".ifBlank { "" }
            listOfNotNull(seriesName, ep.ifBlank { null }).joinToString("  ")
        }
        "Season"  -> if ((indexNumber ?: 0) > 0) "SEASON ${indexNumber}" else name
        "Series"  -> productionYear?.toString() ?: ""
        "Movie"   -> productionYear?.toString() ?: ""
        else      -> ""
    }
}

data class EmbyItemsResponse(
    @SerializedName("Items") val items: List<EmbyItem> = emptyList(),
    @SerializedName("TotalRecordCount") val totalRecordCount: Int = 0
)

// ── Emby Connect (connect.emby.media) ────────────────────────────────────────

data class ConnectPinResponse(
    @SerializedName("Id") val id: String? = null,
    @SerializedName("Pin") val pin: String = "",
    @SerializedName("DeviceId") val deviceId: String = "",
    @SerializedName("IsConfirmed") val isConfirmed: Boolean = false,
    @SerializedName("IsExpired") val isExpired: Boolean = false,
    @SerializedName("AccessToken") val accessToken: String? = null,
    @SerializedName("UserId") val userId: String? = null
)

// Returned by POST /service/pin/authenticate after PIN is confirmed
data class ConnectPinExchangeResult(
    @SerializedName("AccessToken") val accessToken: String = "",
    @SerializedName("UserId") val userId: String = ""
)

data class ConnectServer(
    @SerializedName("SystemId") val systemId: String = "",
    @SerializedName("Id") val connectServerId: String = "",
    @SerializedName("AccessKey") val accessKey: String = "",  // used as X-MediaBrowser-Token for exchange
    @SerializedName("Name") val name: String = "",
    @SerializedName("Url") val url: String = "",
    @SerializedName("LocalAddress") val localAddress: String? = null,
    @SerializedName("RemoteAddress") val remoteAddress: String? = null
) {
    fun bestUrl(): String = when {
        url.isNotBlank() -> url
        !remoteAddress.isNullOrBlank() -> remoteAddress
        !localAddress.isNullOrBlank() -> localAddress
        else -> ""
    }
}

data class EmbyMediaSource(
    @SerializedName("Id") val id: String = "",
    @SerializedName("Container") val container: String? = null,
    @SerializedName("DirectStreamUrl") val directStreamUrl: String? = null,
    @SerializedName("AddApiKeyToDirectStreamUrl") val addApiKeyToDirectStreamUrl: Boolean = false,
    @SerializedName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerializedName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerializedName("TranscodingUrl") val transcodingUrl: String? = null
)

data class EmbyPlaybackInfoResponse(
    @SerializedName("MediaSources") val mediaSources: List<EmbyMediaSource> = emptyList(),
    @SerializedName("PlaySessionId") val playSessionId: String? = null
)

// Returned by GET {serverUrl}/Connect/Exchange on the local Emby server
data class ConnectExchange(
    @SerializedName("LocalUserId") val localUserId: String = "",
    @SerializedName("AccessToken") val accessToken: String = ""
)
