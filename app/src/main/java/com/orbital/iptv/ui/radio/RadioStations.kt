package com.orbital.iptv.ui.radio

import android.content.Context
import com.orbital.iptv.R

/** Loads the bundled radio station playlist (res/raw/radio_stations). */
object RadioStations {

    fun load(context: Context): List<RadioStation> {
        val stations = mutableListOf<RadioStation>()
        val lines = context.resources.openRawResource(R.raw.radio_stations)
            .bufferedReader()
            .readLines()

        var pendingName: String? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF")) {
                val commaIdx = trimmed.indexOf(',')
                if (commaIdx >= 0) {
                    var name = trimmed.substring(commaIdx + 1).trim()
                    // strip leading " - " prefix common in this playlist
                    if (name.startsWith("- ")) name = name.removePrefix("- ").trim()
                    pendingName = name.ifBlank { null }
                }
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && pendingName != null) {
                stations.add(RadioStation(name = pendingName, url = trimmed))
                pendingName = null
            }
        }
        return stations
    }
}
