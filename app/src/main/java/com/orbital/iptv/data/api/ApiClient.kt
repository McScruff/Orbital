package com.orbital.iptv.data.api

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Converter
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object ApiClient {

    /** Set once on startup from PrefsManager; all buildStreamUrl calls use this. */
    var liveFormat: String = "ts"

    // Gson that silently ignores any field whose JSON type doesn't match the model.
    // Uses a tree-based read: the whole JSON value is consumed as a JsonElement first,
    // so a failed deserialization never corrupts the stream position for the parent object.
    private val safeGson: Gson = GsonBuilder()
        .registerTypeAdapterFactory(object : TypeAdapterFactory {
            override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T> {
                val delegate = gson.getDelegateAdapter(this, type)
                val elementAdapter = gson.getAdapter(JsonElement::class.java)
                return object : TypeAdapter<T>() {
                    override fun write(out: JsonWriter, value: T?) = delegate.write(out, value)
                    override fun read(reader: JsonReader): T? = try {
                        delegate.fromJsonTree(elementAdapter.read(reader))
                    } catch (e: Exception) { null }
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
            .addConverterFactory(trimmingGsonConverterFactory(safeGson))
            .build()
    }

    // Some IPTV servers append trailing whitespace or a BOM before/after the JSON body, which
    // causes GsonConverterFactory's strict JsonReader to throw "JSON document was not fully
    // consumed". We strip those bytes first, then parse with the normal strict reader so all
    // other Gson behaviour (null handling, type errors, etc.) is unchanged.
    private fun trimmingGsonConverterFactory(gson: Gson): Converter.Factory = object : Converter.Factory() {
        override fun responseBodyConverter(
            type: java.lang.reflect.Type,
            annotations: Array<out Annotation>,
            retrofit: Retrofit
        ): Converter<ResponseBody, *> {
            val adapter = gson.getAdapter(TypeToken.get(type))
            return Converter { body ->
                val content = body.use { it.string().trimStart('﻿').trim() }
                adapter.read(JsonReader(java.io.StringReader(content)))
            }
        }
    }

    fun buildStreamUrl(serverUrl: String, username: String, password: String, streamId: Int): String {
        val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        return "${base}live/$username/$password/$streamId.$liveFormat"
    }

    fun buildVodUrl(serverUrl: String, username: String, password: String, streamId: Int, ext: String): String {
        val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        return "${base}movie/$username/$password/$streamId.$ext"
    }

    fun buildSeriesEpisodeUrl(serverUrl: String, username: String, password: String, episodeId: String, ext: String): String {
        val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        return "${base}series/$username/$password/$episodeId.$ext"
    }

    fun buildApiUrl(serverUrl: String, username: String, password: String, action: String): String {
        val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        return "${base}player_api.php?username=$username&password=$password&action=$action"
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
