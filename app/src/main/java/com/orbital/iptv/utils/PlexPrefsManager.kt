package com.orbital.iptv.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object PlexPrefsManager {
    private const val PREF      = "plex_session_v2"
    private const val KEY_URL   = "server_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_NAME  = "username"

    data class PlexSession(val serverUrl: String, val token: String, val username: String)

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            PREF,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveSession(context: Context, session: PlexSession) {
        prefs(context).edit()
            .putString(KEY_URL,   session.serverUrl)
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_NAME,  session.username)
            .apply()
    }

    fun getSession(context: Context): PlexSession? {
        val p = prefs(context)
        val url   = p.getString(KEY_URL,   null) ?: return null
        val token = p.getString(KEY_TOKEN, null) ?: return null
        val name  = p.getString(KEY_NAME,  "") ?: ""
        return PlexSession(url, token, name)
    }

    fun clearSession(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
