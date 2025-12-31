package com.thiyagu.media_server.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.thiyagu.media_server.data.db.MediaDao
import com.thiyagu.media_server.model.MediaFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class MediaRepository(
    private val context: Context,
    private val mediaDao: MediaDao
) {

    val allMedia: Flow<List<MediaFile>> = mediaDao.getAllMedia()

    suspend fun scanAndSync(folderUri: Uri, includeSubfolders: Boolean = false) {
        withContext(Dispatchers.IO) {
            val tree = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext
            
            // Collect all files recursively or flat
            val allFiles = if (includeSubfolders) {
                scanRecursively(tree)
            } else {
                tree.listFiles().toList()
            }
            
            // 1. Get current DB state
            val currentDbFiles = mediaDao.getAllMedia().first()
            val dbPaths = currentDbFiles.map { it.path }.toSet()
            
            // 2. Process scanned files
            val scannedFiles = allFiles.filter { 
                !it.isDirectory && isValidVideoFile(it.name) 
            }.map { file ->
                MediaFile(
                    name = file.name ?: "Unknown",
                    path = file.uri.toString(),
                    size = file.length(),
                    duration = 0L, 
                    mimeType = file.type ?: "video/*",
                    dateAdded = System.currentTimeMillis()
                )
            }
            val scannedPaths = scannedFiles.map { it.path }.toSet()
            
            // 3. Diffing Strategy
            // Insert only new files (path not in DB)
            val toInsert = scannedFiles.filter { it.path !in dbPaths }
            
            // Delete only missing files (path in DB but not in Scan)
            val toDelete = currentDbFiles.filter { it.path !in scannedPaths }
            
            // 4. Apply Updates
            if (toInsert.isNotEmpty()) {
                mediaDao.insertAll(toInsert)
            }
            if (toDelete.isNotEmpty()) {
                mediaDao.deleteAll(toDelete)
            }
        }
    }
    
    // Helper for recursive scanning (Stack-based iteration)
    private fun scanRecursively(root: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        val stack = java.util.ArrayDeque<DocumentFile>()
        stack.push(root)

        while (stack.isNotEmpty()) {
            val current = stack.pop()
            val children = current.listFiles()
            
            for (child in children) {
                if (child.isDirectory) {
                    stack.push(child)
                } else {
                    result.add(child)
                }
            }
        }
        return result
    }
    
    suspend fun clearLibrary() {
        mediaDao.clearAll()
    }
    
    private fun isValidVideoFile(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".mov") || n.endsWith(".webm")
    }
}
