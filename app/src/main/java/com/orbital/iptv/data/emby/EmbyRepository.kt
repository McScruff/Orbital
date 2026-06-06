package com.orbital.iptv.data.emby

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class EmbyRepository {

    companion object {
        private const val FIELDS       = "UserData,ImageTags,SeriesPrimaryImageTag,MediaStreams,MediaSources,Height,Width"
        private const val CONNECT_BASE = "https://connect.emby.media/service/"
        private const val CONNECT_APP  = "Emby Theater/3.0.10"
    }

    private val serviceCache = mutableMapOf<String, EmbyApiService>()

    // Shared plain Gson for Connect JSON (no need for safeGson here)
    private val connectGson: Gson = Gson()

    private val connectClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun connectGet(path: String, token: String): String {
        val req = Request.Builder()
            .url("$CONNECT_BASE$path")
            .addHeader("X-Application", CONNECT_APP)
            .addHeader("X-Connect-UserToken", token)
            .build()
        return connectClient.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun connectPost(path: String, bodyJson: String): String {
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$CONNECT_BASE$path")
            .addHeader("X-Application", CONNECT_APP)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()
        return connectClient.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    // Request a new PIN from Emby Connect
    suspend fun requestPin(): Result<ConnectPinResponse> = runCatching {
        // API requires form-encoded body with lowercase deviceId
        val body = "deviceId=orbital-android"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val req = Request.Builder()
            .url("${CONNECT_BASE}pin")
            .addHeader("X-Application", CONNECT_APP)
            .post(body)
            .build()
        connectClient.newCall(req).execute().use { r ->
            val json = r.body?.string() ?: ""
            if (!r.isSuccessful) throw Exception("HTTP ${r.code}: ${json.take(300).ifBlank { "(empty)" }}")
            if (json.isBlank()) throw Exception("HTTP ${r.code}: blank body")
            connectGson.fromJson(json, ConnectPinResponse::class.java)
                ?: throw Exception("Null parse: ${json.take(200)}")
        }
    }

    // Poll to check whether the PIN has been confirmed
    suspend fun checkPin(pin: String, deviceId: String): Result<ConnectPinResponse> = runCatching {
        val req = Request.Builder()
            .url("${CONNECT_BASE}pin?pin=$pin&deviceId=$deviceId")
            .addHeader("X-Application", CONNECT_APP)
            .build()
        val json = connectClient.newCall(req).execute().use { it.body?.string() ?: "" }
        android.util.Log.d("EmbyConnect", "checkPin raw: $json")
        connectGson.fromJson(json, ConnectPinResponse::class.java)
            ?: throw Exception("No response from Emby Connect")
    }

    // Exchange confirmed PIN for real ConnectUserId + ConnectAccessToken
    // Called after getPinStatus returns IsConfirmed=true
    suspend fun exchangePin(pin: String, deviceId: String): Result<ConnectPinExchangeResult> = runCatching {
        val body = "deviceId=$deviceId&pin=$pin"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val req = Request.Builder()
            .url("${CONNECT_BASE}pin/authenticate")
            .addHeader("X-Application", CONNECT_APP)
            .post(body)
            .build()
        val json = connectClient.newCall(req).execute().use { it.body?.string() ?: "" }
        android.util.Log.d("EmbyConnect", "exchangePin raw: $json")
        if (json.isBlank()) throw Exception("Empty response from pin/authenticate")
        connectGson.fromJson(json, ConnectPinExchangeResult::class.java)
            ?: throw Exception("Null parse: ${json.take(200)}")
    }

    // Get servers linked to this Connect account
    suspend fun getConnectServers(connectUserId: String, connectToken: String): Result<List<ConnectServer>> = runCatching {
        android.util.Log.d("EmbyConnect", "getConnectServers userId=$connectUserId")
        val json = connectGet("servers?userId=$connectUserId", connectToken)
        android.util.Log.d("EmbyConnect", "getConnectServers raw: $json")
        if (json.isBlank() || json == "null") return@runCatching emptyList()
        val type = object : TypeToken<List<ConnectServer>>() {}.type
        connectGson.fromJson<List<ConnectServer>>(json, type) ?: emptyList()
    }

    // Exchange the server's AccessKey for local server credentials via the Emby server itself
    suspend fun connectExchange(serverUrl: String, connectUserId: String, accessKey: String): Result<ConnectExchange> =
        runCatching {
            service(serverUrl).connectExchange(
                exchangeToken = accessKey,
                auth = baseHeader(),
                connectUserId = connectUserId
            )
        }

    private fun service(serverUrl: String): EmbyApiService {
        val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        return serviceCache.getOrPut(base) {
            val gson = GsonBuilder()
                .registerTypeAdapterFactory(object : TypeAdapterFactory {
                    override fun <T> create(gson2: com.google.gson.Gson, type: TypeToken<T>): TypeAdapter<T> {
                        val delegate = gson2.getDelegateAdapter(this, type)
                        return object : TypeAdapter<T>() {
                            override fun write(out: JsonWriter, value: T?) = delegate.write(out, value)
                            override fun read(reader: JsonReader): T? = try {
                                delegate.read(reader)
                            } catch (_: Exception) {
                                try { reader.skipValue() } catch (_: Exception) {}
                                null
                            }
                        }
                    }
                }).create()

            val client = OkHttpClient.Builder()
                .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            Retrofit.Builder()
                .baseUrl(base)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(EmbyApiService::class.java)
        }
    }

    suspend fun authenticate(serverUrl: String, username: String, password: String): Result<EmbyAuthResponse> =
        runCatching {
            service(serverUrl).authenticate(
                auth = baseHeader(),
                request = EmbyAuthRequest(username, password)
            )
        }

    suspend fun getItems(
        serverUrl: String, userId: String, token: String,
        parentId: String? = null, types: String? = null,
        recursive: Boolean = false, sortBy: String = "SortName", limit: Int = 1000,
        searchTerm: String? = null, genreIds: String? = null
    ): Result<List<EmbyItem>> = runCatching {
        service(serverUrl).getItems(
            auth = authHeader(token), userId = userId,
            parentId = parentId, types = types, recursive = recursive,
            sortBy = sortBy, sortOrder = "Ascending", fields = FIELDS, limit = limit,
            genreIds = genreIds,
            searchTerm = searchTerm.takeIf { !it.isNullOrBlank() }
        ).items
    }

    suspend fun getGenres(serverUrl: String, userId: String, token: String, types: String): Result<List<EmbyItem>> =
        runCatching {
            service(serverUrl).getGenres(
                auth = authHeader(token), userId = userId, types = types
            ).items
        }

    suspend fun getResumeItems(serverUrl: String, userId: String, token: String): Result<List<EmbyItem>> =
        runCatching {
            service(serverUrl).getResumeItems(
                auth = authHeader(token), userId = userId,
                types = "Movie,Episode", fields = FIELDS, limit = 20
            ).items
        }

    suspend fun getSeasons(serverUrl: String, userId: String, token: String, seriesId: String): Result<List<EmbyItem>> =
        runCatching {
            service(serverUrl).getSeasons(
                auth = authHeader(token), seriesId = seriesId,
                userId = userId, fields = FIELDS
            ).items
        }

    suspend fun getEpisodes(
        serverUrl: String, userId: String, token: String,
        seriesId: String, seasonId: String?
    ): Result<List<EmbyItem>> = runCatching {
        service(serverUrl).getEpisodes(
            auth = authHeader(token), seriesId = seriesId,
            userId = userId, seasonId = seasonId, fields = FIELDS
        ).items
    }

    suspend fun reportStart(serverUrl: String, token: String, itemId: String, positionMs: Long): Result<Unit> =
        runCatching {
            service(serverUrl).reportPlaybackStart(
                auth = authHeader(token),
                info = mapOf(
                    "ItemId" to itemId,
                    "PositionTicks" to positionMs * 10_000L,
                    "CanSeek" to true,
                    "IsPaused" to false,
                    "PlayMethod" to "DirectStream"
                )
            )
            Unit
        }

    suspend fun markPlayed(serverUrl: String, userId: String, token: String, itemId: String): Result<Unit> =
        runCatching {
            service(serverUrl).markPlayed(authHeader(token), userId, itemId)
            Unit
        }

    suspend fun reportStop(serverUrl: String, token: String, itemId: String, positionMs: Long): Result<Unit> =
        runCatching {
            service(serverUrl).reportPlaybackStopped(
                auth = authHeader(token),
                info = mapOf(
                    "ItemId" to itemId,
                    "PositionTicks" to positionMs * 10_000L,
                    "CanSeek" to true,
                    "IsPaused" to false,
                    "PlayMethod" to "DirectStream"
                )
            )
            Unit
        }

    suspend fun getPlaybackInfo(serverUrl: String, userId: String, token: String, itemId: String): Result<EmbyPlaybackInfoResponse> =
        runCatching {
            service(serverUrl).getPlaybackInfo(
                auth = authHeader(token), itemId = itemId, userId = userId
            )
        }

    fun buildStreamUrl(serverUrl: String, itemId: String, token: String, source: EmbyMediaSource? = null): String {
        val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val container = source?.container?.ifBlank { null }
        val mediaSourceId = source?.id?.ifBlank { null } ?: itemId
        val path = if (container != null) "stream.$container" else "stream"
        return "${base}Videos/$itemId/$path?api_key=$token&Static=true&MediaSourceId=$mediaSourceId&DeviceId=orbital-android"
    }

    fun buildArtworkUrl(serverUrl: String, itemId: String, token: String, tag: String?): String? {
        if (tag == null) return null
        val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        return "${base}Items/$itemId/Images/Primary?maxHeight=300&api_key=$token&tag=$tag"
    }

    private fun baseHeader() =
        "MediaBrowser Client=\"Orbital\", Device=\"Orbital\", DeviceId=\"orbital-android\", Version=\"1.4\""

    private fun authHeader(token: String) = "${baseHeader()}, Token=\"$token\""
}
