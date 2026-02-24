package com.thiyagu.media_server.player

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer

internal object PlayerDialogs {
    fun showFinishDialog(
        activity: AppCompatActivity,
        playerProvider: () -> ExoPlayer?,
        onClose: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle("Playback Finished")
            .setPositiveButton("Replay") { _, _ ->
                playerProvider()?.seekTo(0)
                playerProvider()?.prepare()
            }
            .setNegativeButton("Close") { _, _ -> onClose() }
            .show()
    }

    fun showPlaybackError(
        activity: AppCompatActivity,
        error: PlaybackException,
        urlProvider: () -> String?,
        onClose: () -> Unit
    ) {
        val cause = error.cause?.message ?: "Unknown Cause"
        val errorCode = error.errorCodeName
        val message =
            "Error: $errorCode\n" +
                "Details: ${error.message}\n" +
                "Cause: $cause\n\n" +
                "URL: ${urlProvider()}"
        AlertDialog.Builder(activity)
            .setTitle("Playback Error")
            .setMessage(message)
            .setPositiveButton("Close") { _, _ -> onClose() }
            .setCancelable(false)
            .show()
    }
}
