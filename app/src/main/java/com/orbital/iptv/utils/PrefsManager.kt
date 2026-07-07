package com.orbital.iptv.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.orbital.iptv.data.model.ServerProfile
import com.orbital.iptv.data.model.XtreamCredentials

enum class PlayerType { EXOPLAYER, EXTERNAL }

object PrefsManager {
    private const val PREFS_NAME = "orbital_prefs"

    // Legacy single-server keys (kept for migration)
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_USERNAME   = "username"
    private const val KEY_PASSWORD   = "password"

    // Multi-server keys
    private const val KEY_PROFILES          = "server_profiles"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"

    // Settings
    private const val KEY_USE_ORIGINAL_CATEGORIES = "use_original_categories"
    private const val KEY_PLAYER_TYPE             = "player_type"
    private const val KEY_TMDB_API_KEY            = "tmdb_api_key"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Profiles ─────────────────────────────────────────────────────────────

    fun getProfiles(context: Context): List<ServerProfile> {
        val json = prefs(context).getString(KEY_PROFILES, null)
        if (json != null) {
            return try {
                Gson().fromJson(json, object : TypeToken<List<ServerProfile>>() {}.type)
            } catch (_: Exception) { emptyList() }
        }
        // Migrate old single-server data on first run
        return migrateLegacy(context)
    }

    private fun migrateLegacy(context: Context): List<ServerProfile> {
        val p = prefs(context)
        val url  = p.getString(KEY_SERVER_URL, null) ?: return emptyList()
        val user = p.getString(KEY_USERNAME,   null) ?: return emptyList()
        val pass = p.getString(KEY_PASSWORD,   null) ?: return emptyList()
        val profile = ServerProfile(name = "Server 1", serverUrl = url, username = user, password = pass)
        saveProfileList(context, listOf(profile))
        p.edit().putString(KEY_ACTIVE_PROFILE_ID, profile.id).apply()
        return listOf(profile)
    }

    private fun saveProfileList(context: Context, profiles: List<ServerProfile>) {
        prefs(context).edit().putString(KEY_PROFILES, Gson().toJson(profiles)).apply()
    }

    fun saveProfile(context: Context, profile: ServerProfile) {
        val list = getProfiles(context).toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        saveProfileList(context, list)
    }

    fun deleteProfile(context: Context, id: String) {
        saveProfileList(context, getProfiles(context).filter { it.id != id })
    }

    fun getActiveProfileId(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE_PROFILE_ID, null)

    fun setActiveProfile(context: Context, id: String) {
        val profile = getProfiles(context).find { it.id == id } ?: return
        prefs(context).edit()
            .putString(KEY_ACTIVE_PROFILE_ID, id)
            // Keep legacy keys in sync so old code paths still work
            .putString(KEY_SERVER_URL, profile.serverUrl)
            .putString(KEY_USERNAME,   profile.username)
            .putString(KEY_PASSWORD,   profile.password)
            .apply()
    }

    fun getActiveProfile(context: Context): ServerProfile? {
        val profiles = getProfiles(context)
        if (profiles.isEmpty()) return null
        val activeId = getActiveProfileId(context)
        return (profiles.find { it.id == activeId } ?: profiles.first()).also {
            if (it.id != activeId) setActiveProfile(context, it.id)
        }
    }

    // ── Legacy shim ───────────────────────────────────────────────────────────

    fun getCredentials(context: Context): XtreamCredentials? =
        getActiveProfile(context)?.toCredentials()

    fun saveCredentials(context: Context, credentials: XtreamCredentials) {
        // Called only from old paths; new path uses saveProfile directly
        prefs(context).edit()
            .putString(KEY_SERVER_URL, credentials.serverUrl)
            .putString(KEY_USERNAME,   credentials.username)
            .putString(KEY_PASSWORD,   credentials.password)
            .apply()
    }

    fun hasCredentials(context: Context): Boolean = getActiveProfile(context) != null

    // ── Settings ──────────────────────────────────────────────────────────────

    fun setUseOriginalCategories(context: Context, useOriginal: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_ORIGINAL_CATEGORIES, useOriginal).apply()
    }

    fun useOriginalCategories(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_ORIGINAL_CATEGORIES, false)

    fun setPlayerType(context: Context, type: PlayerType) {
        prefs(context).edit().putString(KEY_PLAYER_TYPE, type.name).apply()
    }

    fun getPlayerType(context: Context): PlayerType {
        val name = prefs(context).getString(KEY_PLAYER_TYPE, PlayerType.EXOPLAYER.name)
        return try { PlayerType.valueOf(name!!) } catch (_: Exception) { PlayerType.EXOPLAYER }
    }

    fun clearCredentials(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun setTmdbApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_TMDB_API_KEY, key.trim()).apply()
    }

    fun getTmdbApiKey(context: Context): String? =
        prefs(context).getString(KEY_TMDB_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun getOpenSubsApiKey(context: Context): String? =
        prefs(context).getString("opensubs_api_key", null)?.takeIf { it.isNotBlank() }

    fun setOpenSubsApiKey(context: Context, key: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) prefs(context).edit().remove("opensubs_api_key").apply()
        else prefs(context).edit().putString("opensubs_api_key", trimmed).apply()
    }

    fun getTheme(context: Context): String =
        prefs(context).getString("app_theme", "ORBITAL") ?: "ORBITAL"

    fun setTheme(context: Context, themeName: String) {
        prefs(context).edit().putString("app_theme", themeName).apply()
    }

    // ── PiP ───────────────────────────────────────────────────────────────────

    fun isPipEnabled(context: Context): Boolean =
        prefs(context).getBoolean("pip_enabled", false)

    fun setPipEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("pip_enabled", enabled).apply()
    }

    // ── TV Mode ───────────────────────────────────────────────────────────────

    fun isTvModeEnabled(context: Context): Boolean =
        prefs(context).getBoolean("tv_mode_enabled", false)

    fun setTvModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("tv_mode_enabled", enabled).apply()
    }

    // How see-through the left nav panel / HUD / ticker overlays are, as a percentage
    // (0 = fully solid, matching the original hardcoded look; higher = more of the live
    // video behind shows through). Capped below 100 so the panel never goes fully invisible.
    fun getTvPanelTransparency(context: Context): Int =
        prefs(context).getInt("tv_panel_transparency", 10)

    fun setTvPanelTransparency(context: Context, percent: Int) {
        prefs(context).edit().putInt("tv_panel_transparency", percent.coerceIn(0, 90)).apply()
    }

    fun setLastTvChannel(context: Context, url: String, name: String, streamId: Int, categoryId: String) {
        prefs(context).edit()
            .putString("last_tv_url", url)
            .putString("last_tv_name", name)
            .putInt("last_tv_stream_id", streamId)
            .putString("last_tv_category_id", categoryId)
            .apply()
    }

    fun getLastTvChannelUrl(context: Context): String? = prefs(context).getString("last_tv_url", null)
    fun getLastTvChannelName(context: Context): String? = prefs(context).getString("last_tv_name", null)
    fun getLastTvStreamId(context: Context): Int = prefs(context).getInt("last_tv_stream_id", -1)
    fun getLastTvCategoryId(context: Context): String = prefs(context).getString("last_tv_category_id", "") ?: ""

    fun getLiveFormat(context: Context): String {
        val p = prefs(context)
        // Migrate: ts was the old default when MPV handled live TV. Now ExoPlayer-only,
        // m3u8 (HLS) is always better — AAC audio, adaptive bitrate, no AC3 issues.
        if (!p.contains("live_format_v2")) {
            p.edit().putString("live_format", "m3u8").putBoolean("live_format_v2", true).apply()
        }
        return p.getString("live_format", "m3u8") ?: "m3u8"
    }
    fun setLiveFormat(context: Context, format: String) =
        prefs(context).edit().putString("live_format", format).putBoolean("live_format_v2", true).apply()

    // ── Audio ─────────────────────────────────────────────────────────────────
    // Off by default: PcmOnlyRenderersFactory normally forces stereo-only PCM to avoid a
    // silent-passthrough bug on some HDMI paths (see PcmOnlyRenderersFactory doc comment).
    // This is a manual opt-in for testing real 5.1/7.1 output on setups known to handle it.

    fun isSurroundEnabled(context: Context): Boolean =
        prefs(context).getBoolean("surround_enabled", false)

    fun setSurroundEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("surround_enabled", enabled).apply()

}
