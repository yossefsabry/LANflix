package com.thiyagu.media_server.player

import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.exoplayer.ExoPlayer
import com.thiyagu.media_server.R
import com.thiyagu.media_server.subtitles.SubtitleRepository
import com.thiyagu.media_server.subtitles.refreshPlayerSubtitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal class VideoPlayerSubtitleHandler(
    private val activity: AppCompatActivity,
    private val scope: LifecycleCoroutineScope,
    private val subtitleRepository: SubtitleRepository,
    private val playerProvider: () -> ExoPlayer?,
    private val historyKeyProvider: () -> String?,
    private val urlProvider: () -> String?
) {
    fun handleSubtitlePicked(uri: Uri) {
        val key = historyKeyProvider() ?: return
        scope.launch(Dispatchers.IO) {
            val saved = subtitleRepository.saveUserSubtitle(key, uri)
            val messageId = if (saved) {
                R.string.subtitle_upload_done
            } else {
                R.string.subtitle_upload_failed
            }
            withContext(Dispatchers.Main) {
                if (saved) {
                    updatePlayerSubtitles()
                }
                Toast.makeText(
                    activity,
                    activity.getString(messageId),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updatePlayerSubtitles() {
        val player = playerProvider() ?: return
        val key = historyKeyProvider() ?: return
        val url = urlProvider() ?: return
        refreshPlayerSubtitles(
            player = player,
            context = activity,
            url = url,
            videoKey = key
        )
    }

    fun checkSubtitlesInBackground() {
        val key = historyKeyProvider() ?: return
        val url = urlProvider() ?: return
        val title = deriveTitle(url)
        val lang = Locale.getDefault().language.ifBlank { "en" }
        scope.launch(Dispatchers.IO) {
            subtitleRepository.checkAndCache(key, title, lang)
        }
    }
}
