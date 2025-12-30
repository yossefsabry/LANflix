package com.thiyagu.media_server.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.thiyagu.media_server.model.MediaFile

@Database(entities = [MediaFile::class], version = 1, exportSchema = false)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
}
