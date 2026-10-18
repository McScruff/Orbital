package com.orbital.iptv.ui.sports

import android.content.Context
import com.orbital.iptv.data.model.LiveStream
import com.orbital.iptv.utils.ContentCache
import com.orbital.iptv.utils.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * The user's channel list, ready for the channel finder. Reading tens of thousands of channels
 * back and preparing their names takes seconds on a TV box, so it starts in the background when
 * the app opens ([preload] from the home screen) and is kept for the session, per server.
 */
object SportsChannels {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Deferred<List<LiveStream>>? = null
    @Volatile private var server: String? = null

    /** Starts loading for the signed-in server, unless it's already loaded or loading. */
    fun preload(context: Context) {
        val creds = PrefsManager.getCredentials(context) ?: return
        start(context.applicationContext, creds.serverUrl, creds.username, creds.password)
    }

    suspend fun get(context: Context): List<LiveStream> {
        val creds = PrefsManager.getCredentials(context) ?: return emptyList()
        val list = start(context.applicationContext, creds.serverUrl, creds.username, creds.password).await()
        // An empty list (no network, say) isn't kept: the next search tries again.
        if (list.isEmpty()) synchronized(this) { job = null }
        return list
    }

    @Synchronized
    private fun start(app: Context, serverUrl: String, username: String, password: String): Deferred<List<LiveStream>> {
        job?.takeIf { server == serverUrl }?.let { return it }
        server = serverUrl
        return scope.async {
            (ContentCache.getLiveStreams(app, serverUrl) ?: run {
                ContentCache.downloadAndSaveLiveStreams(app, serverUrl, username, password)
                ContentCache.getLiveStreams(app, serverUrl)
            } ?: emptyList()).also { MatchChannelFinder.prepare(it) }
        }.also { job = it }
    }
}
