package com.thiyagu.media_server.utils

import android.content.Context
import android.content.SharedPreferences

class VideoVisibilityManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "video_visibility_prefs"
        private const val KEY_HIDDEN_VIDEOS = "hidden_video_paths"
    }

    fun isVideoHidden(path: String): Boolean {
        return getHiddenVideos().contains(path)
    }

    fun setVideoHidden(path: String, hidden: Boolean) {
        val hiddenVideos = getHiddenVideos().toMutableSet()
        if (hidden) {
            hiddenVideos.add(path)
        } else {
            hiddenVideos.remove(path)
        }
        prefs.edit().putStringSet(KEY_HIDDEN_VIDEOS, hiddenVideos).apply()
    }

    fun setVideosHidden(paths: Collection<String>, hidden: Boolean) {
        val hiddenVideos = getHiddenVideos().toMutableSet()
        if (hidden) {
            hiddenVideos.addAll(paths)
        } else {
            hiddenVideos.removeAll(paths.toSet())
        }
        prefs.edit().putStringSet(KEY_HIDDEN_VIDEOS, hiddenVideos).apply()
    }

    private fun getHiddenVideos(): Set<String> {
        return prefs.getStringSet(KEY_HIDDEN_VIDEOS, emptySet()) ?: emptySet()
    }
}
