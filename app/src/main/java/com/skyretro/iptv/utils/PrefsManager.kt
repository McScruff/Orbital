package com.skyretro.iptv.utils

import android.content.Context
import android.content.SharedPreferences
import com.skyretro.iptv.data.model.XtreamCredentials

enum class PlayerType { EXOPLAYER, EXTERNAL }

object PrefsManager {
    private const val PREFS_NAME = "sky_retro_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_SAVED_SERVERS = "saved_servers"
    private const val KEY_USE_ORIGINAL_CATEGORIES = "use_original_categories"
    private const val KEY_PLAYER_TYPE = "player_type"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveCredentials(context: Context, credentials: XtreamCredentials) {
        getPrefs(context).edit().apply {
            putString(KEY_SERVER_URL, credentials.serverUrl)
            putString(KEY_USERNAME, credentials.username)
            putString(KEY_PASSWORD, credentials.password)
            apply()
        }
        addSavedServer(context, credentials)
    }

    private fun addSavedServer(context: Context, credentials: XtreamCredentials) {
        val servers = getSavedServers(context).toMutableList()
        if (servers.none { it.serverUrl == credentials.serverUrl && it.username == credentials.username }) {
            servers.add(credentials)
            saveServerList(context, servers)
        }
    }

    private fun saveServerList(context: Context, servers: List<XtreamCredentials>) {
        val json = com.google.gson.Gson().toJson(servers)
        getPrefs(context).edit().putString(KEY_SAVED_SERVERS, json).apply()
    }

    fun getSavedServers(context: Context): List<XtreamCredentials> {
        val json = getPrefs(context).getString(KEY_SAVED_SERVERS, null) ?: return emptyList()
        val type = object : com.google.gson.reflect.TypeToken<List<XtreamCredentials>>() {}.type
        return try {
            com.google.gson.Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getCredentials(context: Context): XtreamCredentials? {
        val prefs = getPrefs(context)
        val url = prefs.getString(KEY_SERVER_URL, null) ?: return null
        val user = prefs.getString(KEY_USERNAME, null) ?: return null
        val pass = prefs.getString(KEY_PASSWORD, null) ?: return null
        return XtreamCredentials(url, user, pass)
    }

    fun setUseOriginalCategories(context: Context, useOriginal: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_USE_ORIGINAL_CATEGORIES, useOriginal).apply()
    }

    fun useOriginalCategories(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_USE_ORIGINAL_CATEGORIES, false)
    }

    fun setPlayerType(context: Context, type: PlayerType) {
        getPrefs(context).edit().putString(KEY_PLAYER_TYPE, type.name).apply()
    }

    fun getPlayerType(context: Context): PlayerType {
        val name = getPrefs(context).getString(KEY_PLAYER_TYPE, PlayerType.EXOPLAYER.name)
        return try { PlayerType.valueOf(name!!) } catch (e: Exception) { PlayerType.EXOPLAYER }
    }

    fun clearCredentials(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    fun hasCredentials(context: Context): Boolean = getCredentials(context) != null
}
