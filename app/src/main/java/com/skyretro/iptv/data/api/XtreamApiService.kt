package com.skyretro.iptv.data.api

import com.skyretro.iptv.data.model.EpgResponse
import com.skyretro.iptv.data.model.LiveCategory
import com.skyretro.iptv.data.model.LiveStream
import com.skyretro.iptv.data.model.ServerInfo
import com.skyretro.iptv.data.model.Episode
import com.skyretro.iptv.data.model.SeriesCategory
import com.skyretro.iptv.data.model.SeriesInfoResponse
import com.skyretro.iptv.data.model.SeriesStream
import com.skyretro.iptv.data.model.VodCategory
import com.skyretro.iptv.data.model.VodInfoResponse
import com.skyretro.iptv.data.model.VodStream
import retrofit2.http.GET
import retrofit2.http.Query

interface XtreamApiService {

    @GET("player_api.php")
    suspend fun getServerInfo(
        @Query("username") username: String,
        @Query("password") password: String
    ): ServerInfo

    @GET("player_api.php")
    suspend fun getLiveCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_categories"
    ): List<LiveCategory>

    @GET("player_api.php")
    suspend fun getAllLiveStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_streams"
    ): List<LiveStream>

    @GET("player_api.php")
    suspend fun getLiveStreamsByCategory(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_streams",
        @Query("category_id") categoryId: String
    ): List<LiveStream>

    @GET("player_api.php")
    suspend fun getShortEpg(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_short_epg",
        @Query("stream_id") streamId: Int
    ): EpgResponse

    @GET("player_api.php")
    suspend fun getShortEpgWithLimit(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_short_epg",
        @Query("stream_id") streamId: Int,
        @Query("limit") limit: Int
    ): EpgResponse

    @GET("player_api.php")
    suspend fun getSimpleDataTable(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_simple_data_table",
        @Query("stream_id") streamId: Int
    ): EpgResponse

    @GET("player_api.php")
    suspend fun getVodCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_categories"
    ): List<VodCategory>

    @GET("player_api.php")
    suspend fun getVodStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_streams",
        @Query("category_id") categoryId: String
    ): List<VodStream>

    @GET("player_api.php")
    suspend fun getVodInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_info",
        @Query("vod_id") vodId: Int
    ): VodInfoResponse

    @GET("player_api.php")
    suspend fun getAllVodStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_streams"
    ): List<VodStream>

    @GET("player_api.php")
    suspend fun getSeriesCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series_categories"
    ): List<SeriesCategory>

    @GET("player_api.php")
    suspend fun getSeriesByCategory(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series",
        @Query("category_id") categoryId: String
    ): List<SeriesStream>

    @GET("player_api.php")
    suspend fun getAllSeries(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series"
    ): List<SeriesStream>

    @GET("player_api.php")
    suspend fun getSeriesInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series_info",
        @Query("series_id") seriesId: Int
    ): SeriesInfoResponse
}
