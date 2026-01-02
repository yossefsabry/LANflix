package com.thiyagu.media_server.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class VideoVisibilityManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    
    // In-memory cache for fast lookups. Thread-safe set.
    private val cachedHiddenVideos: MutableSet<String> = ConcurrentHashMap.newKeySet()

    companion object {
        private const val PREF_NAME = "video_visibility_prefs"
        private const val KEY_HIDDEN_VIDEOS = "hidden_video_paths"
    }

    init {
        // Initialize cache on creation
        cachedHiddenVideos.addAll(getHiddenVideosFromPrefs())
    }

    fun isVideoHidden(path: String): Boolean {
        // O(1) lookup
        return cachedHiddenVideos.contains(path)
    }

    fun setVideoHidden(path: String, hidden: Boolean) {
        if (hidden) {
            cachedHiddenVideos.add(path)
        } else {
            cachedHiddenVideos.remove(path)
        }
        saveToPrefs()
    }

    fun setVideosHidden(paths: Collection<String>, hidden: Boolean) {
        if (hidden) {
            cachedHiddenVideos.addAll(paths)
        } else {
            cachedHiddenVideos.removeAll(paths.toSet())
        }
        saveToPrefs()
    }

    private fun getHiddenVideosFromPrefs(): Set<String> {
        return prefs.getStringSet(KEY_HIDDEN_VIDEOS, emptySet()) ?: emptySet()
    }
    
    private fun saveToPrefs() {
        // Apply asynchronously
        prefs.edit().putStringSet(KEY_HIDDEN_VIDEOS, cachedHiddenVideos.toSet()).apply()
    }
}
