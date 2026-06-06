package com.orbital.iptv.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object PosterCache {

    private fun dir(context: Context): File =
        File(context.filesDir, "posters").also { it.mkdirs() }

    fun getFile(context: Context, streamId: Int): File =
        File(dir(context), "$streamId.jpg")

    suspend fun getBitmap(
        context: Context,
        streamId: Int,
        url: String,
        sampleSize: Int = 1
    ): Bitmap? = withContext(Dispatchers.IO) {
        val file = getFile(context, streamId)
        if (!file.exists() || file.length() == 0L) {
            download(url, file)
        }
        if (file.exists() && file.length() > 0L) {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            )
        } else null
    }

    private fun download(url: String, dest: File) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode == 200) {
                dest.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }
            }
            conn.disconnect()
        } catch (_: Exception) {}
    }
}
