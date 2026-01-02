package com.thiyagu.media_server.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "video_history")
data class VideoHistoryEntity(
    @PrimaryKey val videoUrl: String,
    val position: Long,
    val lastPlayedTime: Long
)
