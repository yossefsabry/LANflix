package com.thiyagu.media_server.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VideoHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: VideoHistoryEntity)

    @Query("SELECT * FROM video_history WHERE videoUrl = :videoUrl")
    suspend fun getHistory(videoUrl: String): VideoHistoryEntity?

    @Query("DELETE FROM video_history WHERE lastPlayedTime < :cutoffTime")
    suspend fun deleteOldHistory(cutoffTime: Long)

    @Query("DELETE FROM video_history")
    suspend fun clearAll()
}
