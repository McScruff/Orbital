package com.skyretro.iptv.data.emby

import retrofit2.Response
import retrofit2.http.*

interface EmbyApiService {

    @POST("Users/AuthenticateByName")
    suspend fun authenticate(
        @Header("X-Emby-Authorization") auth: String,
        @Body request: EmbyAuthRequest
    ): EmbyAuthResponse

    @GET("Users/{userId}/Items")
    suspend fun getItems(
        @Header("X-Emby-Authorization") auth: String,
        @Path("userId") userId: String,
        @Query("ParentId") parentId: String?,
        @Query("IncludeItemTypes") types: String?,
        @Query("Recursive") recursive: Boolean,
        @Query("SortBy") sortBy: String,
        @Query("SortOrder") sortOrder: String,
        @Query("Fields") fields: String,
        @Query("Limit") limit: Int,
        @Query("SearchTerm") searchTerm: String? = null
    ): EmbyItemsResponse

    @GET("Users/{userId}/Items/Resume")
    suspend fun getResumeItems(
        @Header("X-Emby-Authorization") auth: String,
        @Path("userId") userId: String,
        @Query("IncludeItemTypes") types: String,
        @Query("Fields") fields: String,
        @Query("Limit") limit: Int
    ): EmbyItemsResponse

    @GET("Shows/{seriesId}/Seasons")
    suspend fun getSeasons(
        @Header("X-Emby-Authorization") auth: String,
        @Path("seriesId") seriesId: String,
        @Query("UserId") userId: String,
        @Query("Fields") fields: String
    ): EmbyItemsResponse

    @GET("Shows/{seriesId}/Episodes")
    suspend fun getEpisodes(
        @Header("X-Emby-Authorization") auth: String,
        @Path("seriesId") seriesId: String,
        @Query("UserId") userId: String,
        @Query("SeasonId") seasonId: String?,
        @Query("Fields") fields: String
    ): EmbyItemsResponse

    // Exchange a Connect AccessKey for local server credentials
    @GET("Connect/Exchange")
    suspend fun connectExchange(
        @Header("X-MediaBrowser-Token") exchangeToken: String,
        @Header("X-Emby-Authorization") auth: String,
        @Query("ConnectUserId") connectUserId: String,
        @Query("format") format: String = "json"
    ): ConnectExchange

    @GET("Items/{itemId}/PlaybackInfo")
    suspend fun getPlaybackInfo(
        @Header("X-Emby-Authorization") auth: String,
        @Path("itemId") itemId: String,
        @Query("UserId") userId: String
    ): EmbyPlaybackInfoResponse

    @POST("Sessions/Playing")
    suspend fun reportPlaybackStart(
        @Header("X-Emby-Authorization") auth: String,
        @Body info: @JvmSuppressWildcards Map<String, Any>
    ): Response<Unit>

    @POST("Sessions/Playing/Stopped")
    suspend fun reportPlaybackStopped(
        @Header("X-Emby-Authorization") auth: String,
        @Body info: @JvmSuppressWildcards Map<String, Any>
    ): Response<Unit>
}
