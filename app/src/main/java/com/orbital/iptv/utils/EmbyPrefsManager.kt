package com.orbital.iptv.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object EmbyPrefsManager {
    private const val PREF        = "emby_session_v2"
    private const val KEY_URL     = "server_url"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_TOKEN   = "token"
    private const val KEY_NAME    = "username"

    data class EmbySession(
        val serverUrl: String,
        val userId: String,
        val token: String,
        val username: String
    )

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

    fun saveSession(context: Context, session: EmbySession) {
        prefs(context).edit()
            .putString(KEY_URL,     session.serverUrl)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_TOKEN,   session.token)
            .putString(KEY_NAME,    session.username)
            .apply()
    }

    fun getSession(context: Context): EmbySession? {
        val p = prefs(context)
        val url   = p.getString(KEY_URL,     null) ?: return null
        val uid   = p.getString(KEY_USER_ID, null) ?: return null
        val token = p.getString(KEY_TOKEN,   null) ?: return null
        val name  = p.getString(KEY_NAME,    "") ?: ""
        return EmbySession(url, uid, token, name)
    }

    fun clearSession(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun getSavedServerUrl(context: Context): String =
        prefs(context).getString(KEY_URL, "") ?: ""
}
