package com.orbital.iptv.recording

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [RecordingEntity::class], version = 1, exportSchema = false)
@TypeConverters(RecordingConverters::class)
abstract class RecordingDatabase : RoomDatabase() {
    abstract fun dao(): RecordingDao

    companion object {
        @Volatile private var instance: RecordingDatabase? = null
        fun get(context: Context): RecordingDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                RecordingDatabase::class.java,
                "orbital_recordings.db"
            ).build().also { instance = it }
        }
    }
}
