package com.thiyagu.media_server.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.thiyagu.media_server.model.MediaFile
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_files ORDER BY name ASC")
    fun getAllMedia(): Flow<List<MediaFile>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(mediaFiles: List<MediaFile>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mediaFile: MediaFile)

    @Delete
    suspend fun delete(mediaFile: MediaFile)

    @Query("DELETE FROM media_files")
    suspend fun clearAll()
}
