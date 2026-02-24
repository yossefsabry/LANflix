package com.thiyagu.media_server.subtitles

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import com.thiyagu.media_server.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SubtitleMenuController(
    private val activity: AppCompatActivity,
    private val scope: LifecycleCoroutineScope,
    private val playerProvider: () -> ExoPlayer?,
    private val videoUrlProvider: () -> String?,
    private val videoKeyProvider: () -> String?
) {
    private var selectedTrackId: String? = null

    fun showMenu() {
        val key = videoKeyProvider() ?: return
        scope.launch(Dispatchers.IO) {
            val files = listLocalSubtitles(activity, key)
            val options = files.map { file ->
                buildSubtitleDescriptor(file)
            }
            withContext(Dispatchers.Main) {
                showOptions(options)
            }
        }
    }

    private fun showOptions(
        options: List<SubtitleDescriptor>
    ) {
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
        options: List<SubtitleDescriptor>
    ): Int {
        val trackId = selectedTrackId ?: return 0
        val index = options.indexOfFirst { option ->
            option.trackId == trackId
        }
        return if (index == -1) 0 else index + 1
    }

    private fun applySubtitle(option: SubtitleDescriptor?) {
        val player = playerProvider() ?: return
        if (option == null) {
            selectedTrackId = null
            disableTextTracks(player)
            return
        }
        val target = findTrack(player, option.trackId)
        if (target != null) {
            selectTextTrack(player, target)
            selectedTrackId = option.trackId
            return
        }
        val url = videoUrlProvider() ?: return
        reprepareWithSubtitle(player, url, option)
        selectedTrackId = option.trackId
    }

    private fun disableTextTracks(player: ExoPlayer) {
        val builder = player.trackSelectionParameters.buildUpon()
        builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        player.trackSelectionParameters = builder.build()
    }

    private fun selectTextTrack(
        player: ExoPlayer,
        target: TrackTarget
    ) {
        val builder = player.trackSelectionParameters.buildUpon()
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        val override = TrackSelectionOverride(
            target.group,
            target.index
        )
        builder.setOverrideForType(override)
        player.trackSelectionParameters = builder.build()
    }

    private fun findTrack(
        player: ExoPlayer,
        trackId: String
    ): TrackTarget? {
        val tracks = player.currentTracks
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            val mediaGroup = group.mediaTrackGroup
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                if (format.id == trackId) {
                    return TrackTarget(mediaGroup, index)
                }
            }
        }
        return null
    }

    private fun reprepareWithSubtitle(
        player: ExoPlayer,
        url: String,
        option: SubtitleDescriptor
    ) {
        val position = player.currentPosition
        val playWhenReady = player.playWhenReady
        val config = buildSubtitleConfiguration(option)
        val item = MediaItem.Builder()
            .setUri(url)
            .setSubtitleConfigurations(listOf(config))
            .build()
        player.setMediaItem(item, position)
        player.prepare()
        player.playWhenReady = playWhenReady
        val builder = player.trackSelectionParameters.buildUpon()
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        player.trackSelectionParameters = builder.build()
    }

    private data class TrackTarget(
        val group: TrackGroup,
        val index: Int
    )
}
