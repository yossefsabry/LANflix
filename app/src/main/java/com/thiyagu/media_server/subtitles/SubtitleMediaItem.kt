package com.thiyagu.media_server.subtitles

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

internal fun buildMediaItemWithSubtitles(
    context: Context,
    videoKey: String?,
    url: String
): MediaItem {
    val builder = MediaItem.Builder().setUri(url)
    val key = videoKey ?: return builder.build()
    val files = listLocalSubtitles(context, key)
    if (files.isEmpty()) {
        return builder.build()
    }
    val configs = files.map { file ->
        val descriptor = buildSubtitleDescriptor(file)
        buildSubtitleConfiguration(descriptor)
    }
    builder.setSubtitleConfigurations(configs)
    return builder.build()
}

internal fun refreshPlayerSubtitles(
    player: ExoPlayer,
    context: Context,
    url: String,
    videoKey: String?
) {
    val item = buildMediaItemWithSubtitles(
        context = context,
        videoKey = videoKey,
        url = url
    )
    val position = player.currentPosition
    val playWhenReady = player.playWhenReady
    player.setMediaItem(item, position)
    player.prepare()
    player.playWhenReady = playWhenReady
}
