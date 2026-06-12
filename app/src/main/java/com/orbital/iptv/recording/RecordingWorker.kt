package com.orbital.iptv.recording

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class RecordingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getInt("recording_id", -1)
        if (id < 0) return Result.failure()

        androidx.core.content.ContextCompat.startForegroundService(
            applicationContext,
            Intent(applicationContext, RecordingService::class.java).apply {
                putExtra(RecordingService.EXTRA_RECORDING_ID, id)
            }
        )
        return Result.success()
    }
}
