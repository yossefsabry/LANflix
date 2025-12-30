package com.thiyagu.media_server.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.thiyagu.media_server.data.db.MediaDao
import com.thiyagu.media_server.model.MediaFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MediaRepository(
    private val context: Context,
    private val mediaDao: MediaDao
) {

    val allMedia: Flow<List<MediaFile>> = mediaDao.getAllMedia()

    suspend fun scanAndSync(folderUri: Uri) {
        withContext(Dispatchers.IO) {
            val tree = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext
            val files = tree.listFiles()
            
            val mediaFiles = files.filter { 
                !it.isDirectory && isValidVideoFile(it.name) 
            }.map { file ->
                MediaFile(
                    name = file.name ?: "Unknown",
                    path = file.uri.toString(),
                    size = file.length(),
                    duration = 0L, // Duration extraction would require MediaMetadataRetriever (slow)
                    mimeType = file.type ?: "video/*",
                    dateAdded = System.currentTimeMillis()
                )
            }
            
            // Sync with DB: Clear old and insert new (simplest sync strategy)
            mediaDao.clearAll()
            mediaDao.insertAll(mediaFiles)
        }
    }
    
    suspend fun clearLibrary() {
        mediaDao.clearAll()
    }
    
    private fun isValidVideoFile(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".mov") || n.endsWith(".webm")
    }
}
