package com.thiyagu.media_server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.thiyagu.media_server.data.UserPreferences
import com.thiyagu.media_server.service.MediaServerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = UserPreferences(context.applicationContext)
                val shouldRun = prefs.serverRunningFlow.first()
                val folderUri = prefs.selectedFolderFlow.first()
                val port = prefs.serverPortFlow.first()

                if (shouldRun && !folderUri.isNullOrBlank()) {
                    val serviceIntent = Intent(context, MediaServerService::class.java).apply {
                        action = MediaServerService.ACTION_START
                        putExtra(MediaServerService.EXTRA_FOLDER_URI, folderUri)
                        putExtra(MediaServerService.EXTRA_PORT, port)
                        putExtra(MediaServerService.EXTRA_BOOT, true)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
