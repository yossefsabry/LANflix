package com.thiyagu.media_server.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_files",
    indices = [
        androidx.room.Index(value = ["path"], unique = true),
        androidx.room.Index(value = ["name"])
    ]
)
data class MediaFile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val path: String,
    val size: Long,
    val duration: Long,
    val mimeType: String,
    val dateAdded: Long
)
