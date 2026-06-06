package com.orbital.iptv.data.repository

import com.orbital.iptv.data.api.ApiClient
import com.orbital.iptv.data.model.*


class XtreamRepository {

    suspend fun authenticate(serverUrl: String, username: String, password: String): Result<ServerInfo> {
        return try {
            val service = ApiClient.getService(serverUrl)
            val info = service.getServerInfo(username, password)
            if (info.userInfo?.status == "Active") {
                Result.success(info)
            } else {
                Result.failure(Exception("Account not active or invalid credentials"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLiveCategories(serverUrl: String, username: String, password: String): Result<List<LiveCategory>> {
        return try {
            val service = ApiClient.getService(serverUrl)
            val categories = service.getLiveCategories(username, password)
            Result.success(categories)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLiveStreams(serverUrl: String, username: String, password: String): Result<List<LiveStream>> {
        return try {
            val service = ApiClient.getService(serverUrl)
            val streams = service.getAllLiveStreams(username, password)
            Result.success(streams)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLiveStreamsByCategory(
        serverUrl: String,
        username: String,
        password: String,
        categoryId: String
    ): Result<List<LiveStream>> {
        return try {
            val service = ApiClient.getService(serverUrl)
            val streams = service.getLiveStreamsByCategory(username, password, categoryId = categoryId)
            Result.success(streams)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getShortEpg(serverUrl: String, username: String, password: String, streamId: Int): Result<EpgResponse> {
        return try {
            val service = ApiClient.getService(serverUrl)
            val epg = service.getShortEpg(username, password, streamId = streamId)
            Result.success(epg)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Fetches up to 100 future listings — enough to cover 7 days for most channels.
    suspend fun getFullChannelEpg(serverUrl: String, username: String, password: String, streamId: Int): Result<EpgResponse> {
        return try {
            Result.success(ApiClient.getService(serverUrl).getShortEpgWithLimit(username, password, streamId = streamId, limit = 100))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun buildStreamUrl(serverUrl: String, username: String, password: String, streamId: Int): String {
        return ApiClient.buildStreamUrl(serverUrl, username, password, streamId)
    }

    suspend fun getVodCategories(serverUrl: String, username: String, password: String): Result<List<VodCategory>> {
        return try {
            Result.success(ApiClient.getService(serverUrl).getVodCategories(username, password))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getVodStreams(serverUrl: String, username: String, password: String, categoryId: String): Result<List<VodStream>> {
        return try {
            Result.success(ApiClient.getService(serverUrl).getVodStreams(username, password, categoryId = categoryId))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getVodInfo(serverUrl: String, username: String, password: String, vodId: Int): Result<VodInfoResponse> {
        return try {
            Result.success(ApiClient.getService(serverUrl).getVodInfo(username, password, vodId = vodId))
        } catch (e: Exception) { Result.failure(e) }
    }

    fun buildVodUrl(serverUrl: String, username: String, password: String, streamId: Int, ext: String): String {
        return ApiClient.buildVodUrl(serverUrl, username, password, streamId, ext)
    }

    suspend fun getAllVodStreams(serverUrl: String, username: String, password: String): Result<List<VodStream>> {
        return try {
            Result.success(ApiClient.getService(serverUrl).getAllVodStreams(username, password))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getSeriesCategories(serverUrl: String, username: String, password: String): Result<List<SeriesCategory>> {
        return try {
            Result.success(ApiClient.getService(serverUrl).getSeriesCategories(username, password))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getSeriesByCategory(serverUrl: String, username: String, password: String, categoryId: String): Result<List<SeriesStream>> {
        return try {
            Result.success(ApiClient.getService(serverUrl).getSeriesByCategory(username, password, categoryId = categoryId))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getAllSeries(serverUrl: String, username: String, password: String): Result<List<SeriesStream>> {
        return try {
            Result.success(ApiClient.getService(serverUrl).getAllSeries(username, password))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getSeriesInfo(serverUrl: String, username: String, password: String, seriesId: Int): Result<SeriesInfoResponse> {
        return try {
            Result.success(ApiClient.getService(serverUrl).getSeriesInfo(username, password, seriesId = seriesId))
        } catch (e: Exception) { Result.failure(e) }
    }

    fun buildSeriesEpisodeUrl(serverUrl: String, username: String, password: String, episodeId: String, ext: String): String {
        return ApiClient.buildSeriesEpisodeUrl(serverUrl, username, password, episodeId, ext)
    }

    suspend fun getCatchupEpg(serverUrl: String, username: String, password: String, streamId: Int): Result<EpgResponse> {
        return try {
            // get_simple_data_table returns multi-day EPG history with real show titles
            Result.success(ApiClient.getService(serverUrl).getSimpleDataTable(username, password, streamId = streamId))
        } catch (e: Exception) {
            // fall back to short EPG if the endpoint isn't supported
            try {
                Result.success(ApiClient.getService(serverUrl).getShortEpgWithLimit(username, password, streamId = streamId, limit = 300))
            } catch (e2: Exception) { Result.failure(e2) }
        }
    }

    fun buildCatchupUrl(serverUrl: String, username: String, password: String, streamId: Int, startTimestamp: Long, durationMinutes: Int): String {
        return ApiClient.buildCatchupUrl(serverUrl, username, password, streamId, startTimestamp, durationMinutes)
    }
}
