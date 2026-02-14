package com.thiyagu.media_server.player

import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.exoplayer.ExoPlayer
import com.thiyagu.media_server.data.VideoHistoryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

internal class PlayerHistoryController(
    private val scope: LifecycleCoroutineScope,
    private val videoHistoryRepository: VideoHistoryRepository,
    private val playerProvider: () -> ExoPlayer?,
    private val historyKeyProvider: () -> String?,
    private val resumeMinPositionMs: Long,
    private val resumeSaveIntervalMs: Long,
    private val resumeMinDeltaMs: Long,
    private val resumeEndGuardMs: Long
) {
    private var progressJob: Job? = null
    private var lastSavedPosition: Long = 0L

    fun startProgressUpdates() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (true) {
                delay(resumeSaveIntervalMs)
                val p = playerProvider() ?: continue
                if (!p.isPlaying) continue
                val position = p.currentPosition
                val duration = p.duration
                val key = historyKeyProvider() ?: continue

                if (position < resumeMinPositionMs) continue
                if (duration > 0 && duration - position <= resumeEndGuardMs) {
                    videoHistoryRepository.clearPosition(key)
                    lastSavedPosition = 0L
                    continue
                }

                if (abs(position - lastSavedPosition) >= resumeMinDeltaMs) {
                    videoHistoryRepository.savePosition(key, position)
                    lastSavedPosition = position
                }
            }
        }
    }

    fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    fun persistPlaybackPosition() {
        val p = playerProvider() ?: return
        val key = historyKeyProvider() ?: return
        val position = p.currentPosition
        val duration = p.duration
        scope.launch {
            if (duration > 0 && duration - position <= resumeEndGuardMs) {
                videoHistoryRepository.clearPosition(key)
            } else {
                videoHistoryRepository.savePosition(key, position)
            }
        }
    }

    fun clearResumePosition() {
        val key = historyKeyProvider() ?: return
        scope.launch {
            videoHistoryRepository.clearPosition(key)
        }
    }

    fun maybeResumeFromPosition(position: Long) {
        val p = playerProvider() ?: return
        val duration = p.duration
        if (duration > 0 && duration - position <= resumeEndGuardMs) {
            clearResumePosition()
            return
        }
        p.seekTo(position)
    }
}
