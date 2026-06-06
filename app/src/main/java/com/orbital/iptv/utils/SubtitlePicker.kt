package com.orbital.iptv.utils

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.orbital.iptv.R
import com.orbital.iptv.data.opensubtitles.OpenSubtitlesRepository
import com.orbital.iptv.data.opensubtitles.SubtitleHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object SubtitlePicker {

    private val LANG_FLAG = mapOf(
        "en" to "🇬🇧", "fr" to "🇫🇷", "es" to "🇪🇸", "de" to "🇩🇪",
        "pt" to "🇵🇹", "it" to "🇮🇹", "nl" to "🇳🇱", "pl" to "🇵🇱",
        "ru" to "🇷🇺", "ar" to "🇸🇦", "zh" to "🇨🇳", "ja" to "🇯🇵",
        "ko" to "🇰🇷", "tr" to "🇹🇷", "sv" to "🇸🇪", "no" to "🇳🇴",
        "da" to "🇩🇰", "fi" to "🇫🇮", "el" to "🇬🇷", "ro" to "🇷🇴",
        "cs" to "🇨🇿", "hu" to "🇭🇺", "uk" to "🇺🇦"
    )

    fun langLabel(code: String): String {
        val flag = LANG_FLAG[code.lowercase()] ?: ""
        return "$flag ${code.uppercase()}".trim()
    }

    // onResult: null = user removed subtitle, non-null = subtitle file chosen
    fun pickForMovie(
        activity: AppCompatActivity,
        title: String,
        year: String?,
        current: File?,
        onResult: (File?) -> Unit
    ) = pick(activity, title, year, 0, 0, current, onResult)

    fun pickForEpisode(
        activity: AppCompatActivity,
        seriesTitle: String,
        season: Int,
        episode: Int,
        current: File?,
        onResult: (File?) -> Unit
    ) = pick(activity, seriesTitle, null, season, episode, current, onResult)

    private fun pick(
        activity: AppCompatActivity,
        query: String,
        year: String?,
        season: Int,
        episode: Int,
        current: File?,
        onResult: (File?) -> Unit
    ) {
        // If subtitle already selected, offer change or remove
        if (current != null) {
            AlertDialog.Builder(activity, R.style.Theme_Orbital_Dialog)
                .setTitle("SUBTITLES")
                .setItems(arrayOf("CHANGE SUBTITLE", "REMOVE SUBTITLE")) { _, which ->
                    if (which == 0) searchAndPick(activity, query, year, season, episode, onResult)
                    else onResult(null)
                }
                .setNegativeButton("CANCEL", null)
                .show()
            return
        }
        searchAndPick(activity, query, year, season, episode, onResult)
    }

    private fun searchAndPick(
        activity: AppCompatActivity,
        query: String,
        year: String?,
        season: Int,
        episode: Int,
        onResult: (File?) -> Unit
    ) {
        val apiKey = PrefsManager.getOpenSubsApiKey(activity) ?: return
        doSearch(activity, apiKey, query, year, season, episode, onResult)
    }

    private fun doSearch(
        activity: AppCompatActivity,
        apiKey: String,
        query: String,
        year: String?,
        season: Int,
        episode: Int,
        onResult: (File?) -> Unit
    ) {
        val loading = AlertDialog.Builder(activity, R.style.Theme_Orbital_Dialog)
            .setTitle("SEARCHING SUBTITLES...")
            .setCancelable(false)
            .create()
            .also { it.show() }

        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (season > 0) OpenSubtitlesRepository.searchEpisode(apiKey, query, season, episode)
                else            OpenSubtitlesRepository.searchMovie(apiKey, query, year)
            }
            loading.dismiss()

            val hits = result.getOrNull()
            if (hits.isNullOrEmpty()) {
                Toast.makeText(activity, "NO SUBTITLES FOUND", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val labels = hits.map { h ->
                "${langLabel(h.language)}  —  ${h.releaseName.take(52).ifBlank { h.fileName }}"
            }.toTypedArray()

            AlertDialog.Builder(activity, R.style.Theme_Orbital_Dialog)
                .setTitle("SELECT SUBTITLE (${hits.size})")
                .setItems(labels) { _, i -> downloadAndReturn(activity, apiKey, hits[i], onResult) }
                .setNegativeButton("CANCEL", null)
                .show()
        }
    }

    private fun downloadAndReturn(
        activity: AppCompatActivity,
        apiKey: String,
        hit: SubtitleHit,
        onResult: (File?) -> Unit
    ) {
        val loading = AlertDialog.Builder(activity, R.style.Theme_Orbital_Dialog)
            .setTitle("DOWNLOADING...")
            .setCancelable(false)
            .create()
            .also { it.show() }

        activity.lifecycleScope.launch {
            val result = OpenSubtitlesRepository.downloadToCache(activity, apiKey, hit)
            loading.dismiss()
            result.fold(
                onSuccess = { file -> onResult(file) },
                onFailure = { Toast.makeText(activity, "DOWNLOAD FAILED", Toast.LENGTH_SHORT).show() }
            )
        }
    }
}
