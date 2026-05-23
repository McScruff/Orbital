package com.skyretro.iptv.utils

import android.content.Context
import com.skyretro.iptv.data.model.SkyCategory
import org.json.JSONObject

object CategoryPrefs {
    private const val PREF        = "category_prefs"
    private const val KEY_NAMES   = "custom_names"
    private const val KEY_MAPPING = "custom_mapping"

    fun getCategoryName(context: Context, sky: SkyCategory): String {
        val json = prefs(context).getString(KEY_NAMES, "{}") ?: "{}"
        return try { JSONObject(json).optString(sky.name).ifBlank { sky.displayName } }
        catch (_: Exception) { sky.displayName }
    }

    fun setCategoryName(context: Context, sky: SkyCategory, name: String) {
        val obj = loadJson(context, KEY_NAMES)
        obj.put(sky.name, name)
        prefs(context).edit().putString(KEY_NAMES, obj.toString()).apply()
    }

    fun getCustomMapping(context: Context): Map<String, SkyCategory> {
        val json = prefs(context).getString(KEY_MAPPING, "{}") ?: "{}"
        return try {
            val obj = JSONObject(json)
            val result = mutableMapOf<String, SkyCategory>()
            obj.keys().forEach { key ->
                try { result[key] = SkyCategory.valueOf(obj.getString(key)) } catch (_: Exception) {}
            }
            result
        } catch (_: Exception) { emptyMap() }
    }

    fun setServerCategoryMapping(context: Context, serverCategoryId: String, sky: SkyCategory?) {
        val obj = loadJson(context, KEY_MAPPING)
        if (sky == null) obj.remove(serverCategoryId) else obj.put(serverCategoryId, sky.name)
        prefs(context).edit().putString(KEY_MAPPING, obj.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun loadJson(context: Context, key: String): JSONObject =
        try { JSONObject(prefs(context).getString(key, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
}
