package com.orbital.iptv.utils

object ChannelQueue {
    data class Entry(val streamId: Int, val name: String, val num: Int)

    var entries: List<Entry> = emptyList()
    var currentIndex: Int = 0
}
