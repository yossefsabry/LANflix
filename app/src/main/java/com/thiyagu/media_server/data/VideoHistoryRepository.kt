package com.thiyagu.media_server.data

import com.thiyagu.media_server.data.db.VideoHistoryDao
import com.thiyagu.media_server.data.db.VideoHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class VideoHistoryRepository(private val dao: VideoHistoryDao) {

    suspend fun savePosition(videoUrl: String, position: Long) = withContext(Dispatchers.IO) {
        if (position > 5000) {
            val entity = VideoHistoryEntity(
                videoUrl = videoUrl,
                position = position,
                lastPlayedTime = System.currentTimeMillis()
            )
            dao.insert(entity)
        }
    }

    suspend fun getPosition(videoUrl: String): Long? = withContext(Dispatchers.IO) {
        val entity = dao.getHistory(videoUrl)
        entity?.position
    }
    
    suspend fun pruneHistory(daysToKeep: Int) = withContext(Dispatchers.IO) {
        if (daysToKeep >= 0) {
            val cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysToKeep.toLong())
            dao.deleteOldHistory(cutoffTime)
        }
    }
    
    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }
}
