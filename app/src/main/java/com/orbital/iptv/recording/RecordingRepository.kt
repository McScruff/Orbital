package com.orbital.iptv.recording

import android.content.Context
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordingRepository {

    fun getDir(context: Context): File {
        val dirs = context.getExternalFilesDirs(null)
        val best = dirs.filterNotNull()
            .filter { it.canWrite() }
            .maxByOrNull { it.freeSpace }
            ?: context.filesDir
        return File(best, "Orbital/Recordings").also { it.mkdirs() }
    }

    fun availableGb(context: Context): Double {
        val dir = getDir(context)
        val stat = StatFs(dir.path)
        return stat.availableBlocksLong * stat.blockSizeLong / (1024.0 * 1024.0 * 1024.0)
    }

    fun buildFilename(channelName: String, epgTitle: String, startMs: Long): String {
        fun safe(s: String) = s.replace(Regex("[^a-zA-Z0-9_-]"), "_").trimEnd('_').take(40)
        val date = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(startMs))
        return "${safe(channelName)}_${safe(epgTitle)}_$date.ts"
    }
}
