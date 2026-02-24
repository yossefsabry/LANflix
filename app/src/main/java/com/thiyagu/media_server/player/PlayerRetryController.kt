package com.thiyagu.media_server.player

import android.net.Uri
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.thiyagu.media_server.server.ServerDiscoveryManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class PlayerRetryController(
    private val scope: LifecycleCoroutineScope,
    private val discoveryManager: ServerDiscoveryManager,
    private val playerProvider: () -> ExoPlayer?,
    private val urlProvider: () -> String?,
    private val setUrl: (String) -> Unit,
    private val historyController: PlayerHistoryController,
    private val pinProvider: () -> String?,
    private val clientIdProvider: () -> String?,
    private val defaultPort: Int,
    private val maxRetryCount: Int,
    private val retryBaseDelayMs: Long,
    private val retryMaxDelayMs: Long,
    private val rediscoveryTimeoutMs: Long,
    private val rediscoveryPollMs: Long,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int
) {
    private var retryCount = 0
    private var retryJob: Job? = null
    private var pendingSeekPosition: Long? = null
    private var parserRecoveryAttempted = false
    private val serverResolver = PlayerServerResolver(
        discoveryManager = discoveryManager,
        pinProvider = pinProvider,
        clientIdProvider = clientIdProvider,
        defaultPort = defaultPort,
        rediscoveryTimeoutMs = rediscoveryTimeoutMs,
        rediscoveryPollMs = rediscoveryPollMs,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs
    )

    fun resetForNewMedia() {
        retryCount = 0
        retryJob?.cancel()
        retryJob = null
        pendingSeekPosition = null
        parserRecoveryAttempted = false
    }

    fun setPendingSeek(position: Long) {
        pendingSeekPosition = position
    }

    fun onPlayerReady() {
        retryCount = 0
        pendingSeekPosition?.let {
            historyController.maybeResumeFromPosition(it)
            pendingSeekPosition = null
        }
    }

    fun cancel() {
        retryJob?.cancel()
        retryJob = null
    }

    fun handleError(error: PlaybackException): Boolean {
        val httpError =
            error.cause as? HttpDataSource
                .InvalidResponseCodeException
        val outOfRange =
            error.errorCode ==
                PlaybackException
                    .ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
                (error.errorCode ==
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
                    httpError?.responseCode == 416)
        if (outOfRange) {
            recoverFromOutOfRange()
            return true
        }

        val malformed =
            error.errorCode ==
                PlaybackException
                    .ERROR_CODE_PARSING_CONTAINER_MALFORMED
        if (malformed && !parserRecoveryAttempted) {
            recoverFromMalformedContainer()
            return true
        }

        if (shouldRetry(error)) {
            scheduleRetry()
            return true
        }

        return false
    }

    private fun shouldRetry(error: PlaybackException): Boolean {
        if (retryCount >= maxRetryCount) return false
        return when (error.errorCode) {
            PlaybackException
                .ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException
                .ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException
                .ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> true
            else -> false
        }
    }

    private fun recoverFromOutOfRange() {
        pendingSeekPosition = null
        cancel()
        historyController.clearResumePosition()
        val player = playerProvider() ?: return
        player.seekTo(0)
        player.prepare()
        player.playWhenReady = true
    }

    private fun recoverFromMalformedContainer() {
        parserRecoveryAttempted = true
        pendingSeekPosition = null
        cancel()
        historyController.clearResumePosition()
        val url = urlProvider() ?: return
        val player = playerProvider() ?: return
        player.stop()
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        player.prepare()
        player.playWhenReady = true
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryCount += 1
        val attempt = retryCount
        val retryDelayMs =
            (retryBaseDelayMs * (1L shl (attempt - 1)))
                .coerceAtMost(retryMaxDelayMs)
        retryJob = scope.launch {
            delay(retryDelayMs)
            val currentUrl = urlProvider() ?: return@launch
            val resolved = serverResolver.resolve(currentUrl)
            if (!resolved.isNullOrEmpty() && resolved != currentUrl) {
                val position = playerProvider()?.currentPosition ?: 0L
                if (position > 0L) {
                    pendingSeekPosition = position
                }
                setUrl(resolved)
                playerProvider()?.setMediaItem(
                    MediaItem.fromUri(Uri.parse(resolved))
                )
            }
            val player = playerProvider() ?: return@launch
            player.prepare()
            player.playWhenReady = true
        }
    }

}
