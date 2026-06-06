package com.orbital.iptv.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String
)

object UpdateChecker {

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/McScruff/Orbital/master/version.json"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val json = http.newCall(Request.Builder().url(VERSION_URL).build())
                .execute().use { it.body?.string() ?: "" }
            val obj = JSONObject(json)
            val remoteCode = obj.getInt("version_code")
            @Suppress("DEPRECATION")
            val currentCode = context.packageManager
                .getPackageInfo(context.packageName, 0).versionCode
            if (remoteCode > currentCode) {
                UpdateInfo(
                    versionCode = remoteCode,
                    versionName = obj.getString("version_name"),
                    downloadUrl = obj.getString("download_url"),
                    releaseNotes = obj.optString("release_notes", "")
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Downloads the APK, calling [onProgress] with 0-100 on the calling dispatcher.
     * Returns the destination File on success, null on failure.
     */
    suspend fun downloadWithProgress(
        context: Context,
        url: String,
        onProgress: suspend (pct: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dest = File(context.getExternalFilesDir(null), "orbital-update.apk")
            if (dest.exists()) dest.delete()

            val response = http.newCall(Request.Builder().url(url).build()).execute()
            val body = response.body ?: return@withContext null
            val totalBytes = body.contentLength()

            var downloaded = 0L
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(32 * 1024)
                    var read = input.read(buf)
                    while (read >= 0) {
                        out.write(buf, 0, read)
                        downloaded += read
                        val pct = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else -1
                        withContext(Dispatchers.Main) { onProgress(pct, downloaded, totalBytes) }
                        read = input.read(buf)
                    }
                }
            }
            dest
        } catch (_: Exception) {
            null
        }
    }

    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
