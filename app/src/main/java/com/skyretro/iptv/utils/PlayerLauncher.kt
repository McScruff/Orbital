package com.skyretro.iptv.utils

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.skyretro.iptv.ui.player.PlayerActivity

object PlayerLauncher {

    fun launch(
        activity: Activity,
        streamUrl: String,
        title: String,
        streamId: Int,
        isLive: Boolean = false,
        favId: String = "",
        artUrl: String = "",
        resumeMs: Long = 0L,
        seriesId: Int = -1,
        season: String = "",
        episodeNum: Int = 0,
        episodeId: String = "",
        nextEpUrl: String = "",
        nextEpTitle: String = "",
        nextEpNum: Int = 0,
        nextEpSeason: String = "",
        nextEpId: String = ""
    ) {
        if (!isLive && PrefsManager.getPlayerType(activity) == PlayerType.EXTERNAL) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(streamUrl), "video/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                Toast.makeText(activity, "NO EXTERNAL PLAYER FOUND — INSTALL VLC OR MX PLAYER", Toast.LENGTH_LONG).show()
            }
            return
        }

        activity.startActivity(Intent(activity, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL,     streamUrl)
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME,   title)
            putExtra(PlayerActivity.EXTRA_STREAM_ID,      streamId)
            putExtra(PlayerActivity.EXTRA_IS_LIVE,        isLive)
            putExtra(PlayerActivity.EXTRA_FAV_ID,         favId)
            putExtra(PlayerActivity.EXTRA_ART_URL,        artUrl)
            putExtra(PlayerActivity.EXTRA_RESUME_MS,      resumeMs)
            putExtra(PlayerActivity.EXTRA_SERIES_ID,      seriesId)
            putExtra(PlayerActivity.EXTRA_SEASON,         season)
            putExtra(PlayerActivity.EXTRA_EPISODE_NUM,    episodeNum)
            putExtra(PlayerActivity.EXTRA_EPISODE_ID,     episodeId)
            putExtra(PlayerActivity.EXTRA_NEXT_EP_URL,    nextEpUrl)
            putExtra(PlayerActivity.EXTRA_NEXT_EP_TITLE,  nextEpTitle)
            putExtra(PlayerActivity.EXTRA_NEXT_EP_NUM,    nextEpNum)
            putExtra(PlayerActivity.EXTRA_NEXT_EP_SEASON, nextEpSeason)
            putExtra(PlayerActivity.EXTRA_NEXT_EP_ID,     nextEpId)
        })
    }
}
