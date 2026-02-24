package com.thiyagu.media_server.subtitles

import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import com.thiyagu.media_server.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal class SubtitleMenuController(
    private val activity: AppCompatActivity,
    private val scope: LifecycleCoroutineScope,
    private val playerProvider: () -> ExoPlayer?,
    private val videoUrlProvider: () -> String?,
    private val videoKeyProvider: () -> String?
) {
    private var selectedPath: String? = null

    fun showMenu() {
        val key = videoKeyProvider() ?: return
        scope.launch(Dispatchers.IO) {
            val files = listLocalSubtitles(activity, key)
            val options = files.map { file ->
                buildOption(file)
            }
            withContext(Dispatchers.Main) {
                showOptions(options)
            }
        }
    }

    private fun showOptions(options: List<SubtitleOption>) {
        val labels = ArrayList<String>()
        labels.add(
            activity.getString(
                R.string.lanflix_subtitles_off
            )
        )
        for (option in options) {
            labels.add(option.label)
        }
        val checked = selectedIndex(options)
        AlertDialog.Builder(activity)
            .setTitle(R.string.lanflix_subtitles_title)
            .setSingleChoiceItems(
                labels.toTypedArray(),
                checked
            ) { dialog, which ->
                if (which == 0) {
                    applySubtitle(null)
                    dialog.dismiss()
                    return@setSingleChoiceItems
                }
                val option = options[which - 1]
                applySubtitle(option)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun selectedIndex(
        options: List<SubtitleOption>
    ): Int {
        val path = selectedPath ?: return 0
        val index = options.indexOfFirst { option ->
            option.file.absolutePath == path
        }
        return if (index == -1) 0 else index + 1
    }

    private fun applySubtitle(option: SubtitleOption?) {
        val path = option?.file?.absolutePath
        if (path == selectedPath) return
        val player = playerProvider() ?: return
        val url = videoUrlProvider() ?: return
        val position = player.currentPosition
        val playWhenReady = player.playWhenReady
        val builder = MediaItem.Builder().setUri(url)
        if (option != null) {
            val config = MediaItem.SubtitleConfiguration
                .Builder(Uri.fromFile(option.file))
                .setMimeType(mimeTypeFor(option.file))
                .setLanguage(option.language)
                .setLabel(option.label)
                .build()
            builder.setSubtitleConfigurations(listOf(config))
            selectedPath = option.file.absolutePath
        } else {
            selectedPath = null
        }
        player.setMediaItem(builder.build(), position)
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    private fun buildOption(file: File): SubtitleOption {
        val base = file.nameWithoutExtension
        val tokens = splitTokens(base)
        val code = detectLanguage(tokens)
        val label = if (code == null) {
            if (base.isNotBlank()) base else file.name
        } else {
            languageLabel(code)
        }
        return SubtitleOption(file, code, label)
    }

    private fun splitTokens(value: String): List<String> {
        return value.lowercase(Locale.getDefault())
            .split(
                '.', '_', '-', ' ', '[', ']',
                '(', ')'
            )
            .filter { token -> token.isNotBlank() }
    }

    private fun detectLanguage(tokens: List<String>): String? {
        if (tokens.any { token ->
                token == "ar" ||
                    token == "ara" ||
                    token == "arabic"
            }
        ) {
            return "ar"
        }
        if (tokens.any { token ->
                token == "en" ||
                    token == "eng" ||
                    token == "english"
            }
        ) {
            return "en"
        }
        return null
    }

    private fun languageLabel(code: String): String {
        val locale = Locale(code)
        val label = locale.getDisplayLanguage(Locale.getDefault())
        return if (label.isBlank()) code else label
    }

    private fun mimeTypeFor(file: File): String {
        return when (file.extension.lowercase()) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "srt", "sub" -> MimeTypes.APPLICATION_SUBRIP
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private data class SubtitleOption(
        val file: File,
        val language: String?,
        val label: String
    )
}
