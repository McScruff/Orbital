package com.orbital.iptv.data.plex

data class PlexItem(
    val ratingKey: String,
    val title: String,
    val type: String,
    val year: Int?,
    val thumb: String?,
    val parentTitle: String?,
    val grandparentTitle: String?,
    val parentIndex: Int?,
    val index: Int?,
    val duration: Long?,
    val viewOffset: Long?,
    val partKey: String?,
    val videoResolution: String? = null
) {
    fun resumeMs(): Long = viewOffset ?: 0L

    fun videoResolutionLabel(): String? = when (videoResolution?.lowercase()) {
        "4k"   -> "4K"
        "1080" -> "1080p"
        "720"  -> "720p"
        "480"  -> "480p"
        "sd"   -> null
        null   -> null
        else   -> videoResolution.uppercase()
    }

    fun subtitle(): String = when (type) {
        "episode" -> {
            val s  = parentIndex?.let { "S$it" } ?: ""
            val e  = index?.let { "E%02d".format(it) } ?: ""
            val se = "$s$e".ifBlank { "" }
            listOfNotNull(grandparentTitle, se.ifBlank { null }).joinToString("  ")
        }
        "season"  -> parentTitle ?: ""
        "show"    -> year?.toString() ?: ""
        "movie"   -> year?.toString() ?: ""
        "album"   -> parentTitle ?: ""
        "track"   -> listOfNotNull(grandparentTitle, parentTitle).joinToString("  ").ifBlank { "" }
        else      -> ""
    }
}

data class PlexServer(
    val name: String,
    val connections: List<PlexConnection>,
    val accessToken: String = ""
) {
    fun bestUrl(): String =
        connections.firstOrNull { it.local && !it.relay }?.uri
            ?: connections.firstOrNull { !it.local && !it.relay && it.uri.startsWith("https") }?.uri
            ?: connections.firstOrNull { !it.local && it.uri.startsWith("https") }?.uri
            ?: connections.firstOrNull { !it.local && !it.relay }?.uri
            ?: connections.firstOrNull()?.uri
            ?: ""
}

data class PlexConnection(
    val uri: String,
    val local: Boolean,
    val relay: Boolean,
    val address: String = "",
    val port: Int = 32400
)

data class PlexGenre(val title: String, val ratingKey: String, val sectionKey: String)
