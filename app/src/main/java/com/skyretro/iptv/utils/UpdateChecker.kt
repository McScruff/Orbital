package com.skyretro.iptv.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String
)

object UpdateChecker {

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/McScruff/SkyRetroIPTV-releases/master/version.json"

    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val json = URL(VERSION_URL).readText()
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

    fun downloadAndInstall(context: Context, url: String) {
        val dest = File(context.getExternalFilesDir(null), "skyretro-update.apk")
        if (dest.exists()) dest.delete()

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("SkyRetro Update")
            setDescription("Downloading...")
            setDestinationUri(Uri.fromFile(dest))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == downloadId) {
                    context.unregisterReceiver(this)
                    installApk(context, dest)
                }
            }
        }
        @Suppress("UnspecifiedRegisterReceiverFlag")
        context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
