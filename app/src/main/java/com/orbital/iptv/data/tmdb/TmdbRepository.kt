package com.orbital.iptv.data.tmdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class TmdbMatch(
    val mediaType: String,   // "movie" or "tv"
    val title: String,
    val overview: String,
    val posterUrl: String?,
    val year: String?,
    val voteAverage: Double?
)

object TmdbRepository {

    private const val BASE       = "https://api.themoviedb.org/3"
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Searches TMDB's combined movie+TV index for [query] and returns up to [limit] candidates
     * (movie/tv only, "person" hits dropped), ordered: [preferType] matches first, then the rest
     * in TMDB's own relevance order. Returns candidates rather than committing to a single "best"
     * one — TMDB's full-text search is fuzzy and its top hit is not necessarily a real match, so
     * the caller is expected to run its own title-similarity check over these before using one.
     */
    suspend fun search(apiKey: String, query: String, preferType: String? = null, limit: Int = 5): List<TmdbMatch> =
        withContext(Dispatchers.IO) {
            runCatching {
                val q = URLEncoder.encode(query, "UTF-8")
                val req = Request.Builder()
                    .url("$BASE/search/multi?api_key=$apiKey&query=$q&include_adult=false")
                    .header("Accept", "application/json")
                    .build()
                val json = client.newCall(req).execute().use { it.body?.string() ?: "" }
                val results = JSONObject(json).optJSONArray("results") ?: return@runCatching emptyList()

                val candidates = mutableListOf<TmdbMatch>()
                for (i in 0 until results.length()) {
                    val r = results.getJSONObject(i)
                    val mediaType = r.optString("media_type")
                    if (mediaType != "movie" && mediaType != "tv") continue

                    val title      = (if (mediaType == "tv") r.optString("name") else r.optString("title")).ifBlank { query }
                    val dateStr    = if (mediaType == "tv") r.optString("first_air_date") else r.optString("release_date")
                    val year       = dateStr.take(4).takeIf { it.length == 4 }
                    val posterPath = r.optString("poster_path", "").takeIf { it.isNotBlank() }
                    val overview   = r.optString("overview", "")
                    val vote       = r.optDouble("vote_average", -1.0).takeIf { it > 0.0 }

                    candidates += TmdbMatch(
                        mediaType   = mediaType,
                        title       = title,
                        overview    = overview,
                        posterUrl   = posterPath?.let { "$IMAGE_BASE$it" },
                        year        = year,
                        voteAverage = vote
                    )
                }

                if (preferType != null) {
                    candidates.sortedByDescending { it.mediaType == preferType }.take(limit)
                } else {
                    candidates.take(limit)
                }
            }.getOrDefault(emptyList())
        }
}
