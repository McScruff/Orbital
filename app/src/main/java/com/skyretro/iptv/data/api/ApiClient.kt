package com.skyretro.iptv.data.api

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    // Gson that silently skips any field whose JSON type doesn't match the model,
    // rather than throwing IllegalStateException. Fixes servers that return
    // `seasons` or other fields as the wrong type (array vs object, etc.).
    private val safeGson: Gson = GsonBuilder()
        .registerTypeAdapterFactory(object : TypeAdapterFactory {
            override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T> {
                val delegate = gson.getDelegateAdapter(this, type)
                return object : TypeAdapter<T>() {
                    override fun write(out: JsonWriter, value: T?) = delegate.write(out, value)
                    override fun read(reader: JsonReader): T? = try {
                        delegate.read(reader)
                    } catch (e: Exception) {
                        try { reader.skipValue() } catch (_: Exception) {}
                        null
                    }
                }
            }
        })
        .create()

    private var retrofit: Retrofit? = null
    private var currentBaseUrl: String? = null

    fun getService(baseUrl: String): XtreamApiService {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        if (retrofit == null || currentBaseUrl != normalizedUrl) {
            currentBaseUrl = normalizedUrl
            retrofit = buildRetrofit(normalizedUrl)
        }
        return retrofit!!.create(XtreamApiService::class.java)
    }

    private fun buildRetrofit(baseUrl: String): Retrofit {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(safeGson))
            .build()
    }

    fun buildStreamUrl(serverUrl: String, username: String, password: String, streamId: Int): String {
        val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        return "${base}live/$username/$password/$streamId.ts"
    }

    fun buildVodUrl(serverUrl: String, username: String, password: String, streamId: Int, ext: String): String {
        val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        return "${base}movie/$username/$password/$streamId.$ext"
    }

    fun buildSeriesEpisodeUrl(serverUrl: String, username: String, password: String, episodeId: String, ext: String): String {
        val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        return "${base}series/$username/$password/$episodeId.$ext"
    }

    fun buildCatchupUrl(
        serverUrl: String, username: String, password: String,
        streamId: Int, startTimestamp: Long, durationMinutes: Int
    ): String {
        val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = startTimestamp * 1000
        }
        val date = "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
        val hh = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val mm = cal.get(java.util.Calendar.MINUTE)
        return "${base}timeshift/$username/$password/$durationMinutes/$date:$hh-$mm/$streamId.ts"
    }
}
