package com.orbital.iptv.recording

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters

enum class RecordingStatus { SCHEDULED, RECORDING, COMPLETED, FAILED, SKIPPED }

class RecordingConverters {
    @TypeConverter fun toStr(s: RecordingStatus) = s.name
    @TypeConverter fun fromStr(s: String) = RecordingStatus.valueOf(s)
}

@Entity(tableName = "recordings")
@TypeConverters(RecordingConverters::class)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val channelName: String = "",
    val channelUrl: String = "",
    val streamId: Int = -1,
    val epgTitle: String = "",
    val scheduledStart: Long = 0L,
    val scheduledEnd: Long = 0L,   // 0 = unlimited (Record Now)
    val filePath: String = "",
    val status: RecordingStatus = RecordingStatus.SCHEDULED
)
