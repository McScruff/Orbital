package com.skyretro.iptv.utils

import android.content.Context

object EmbyPrefsManager {
    private const val PREF        = "emby_session"
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

    fun saveSession(context: Context, session: EmbySession) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL,     session.serverUrl)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_TOKEN,   session.token)
            .putString(KEY_NAME,    session.username)
            .apply()
    }

    fun getSession(context: Context): EmbySession? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val url   = p.getString(KEY_URL,     null) ?: return null
        val uid   = p.getString(KEY_USER_ID, null) ?: return null
        val token = p.getString(KEY_TOKEN,   null) ?: return null
        val name  = p.getString(KEY_NAME,    "") ?: ""
        return EmbySession(url, uid, token, name)
    }

    fun clearSession(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun getSavedServerUrl(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_URL, "") ?: ""
}
