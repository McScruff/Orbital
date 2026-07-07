package com.orbital.iptv.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object TorboxPrefsManager {
    private const val PREF        = "torbox_session_v1"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_EMAIL   = "email"

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

    fun saveApiKey(context: Context, apiKey: String, email: String = "") {
        prefs(context).edit()
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_EMAIL, email)
            .apply()
    }

    fun getApiKey(context: Context): String? =
        prefs(context).getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun getEmail(context: Context): String =
        prefs(context).getString(KEY_EMAIL, "") ?: ""

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
