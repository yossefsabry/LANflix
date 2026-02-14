package com.thiyagu.media_server.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.thiyagu.media_server.R
import com.thiyagu.media_server.StreamingActivity
import com.thiyagu.media_server.data.UserPreferences
import com.thiyagu.media_server.server.ServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MediaServerService : Service() {

    private val serverManager: ServerManager by inject()
    private val userPreferences: UserPreferences by inject()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var retryJob: kotlinx.coroutines.Job? = null
    
    companion object {
        const val CHANNEL_ID = "MediaServerChannel"
        const val NOTIFICATION_ID = 1001
        
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_RESTART = "ACTION_RESTART"

        const val EXTRA_FOLDER_URI = "EXTRA_FOLDER_URI"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_BOOT = "EXTRA_BOOT"

        private const val RETRY_DELAY_MS = 10_000L
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            serviceScope.launch {
                stopServerAndCleanup(userInitiated = true)
            }
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification("Starting LANflix server..."))

        when (action) {
            ACTION_START -> serviceScope.launch { startFromIntent(intent) }
            ACTION_RESTART -> serviceScope.launch { restartFromIntent(intent) }
            else -> serviceScope.launch { restoreIfNeeded() }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        retryJob?.cancel()
        serviceScope.cancel()
        releaseLocks()
        serverManager.stopServerInternal()
        super.onDestroy()
    }

    private suspend fun startFromIntent(intent: Intent?) {
        val folderUri = intent?.getStringExtra(EXTRA_FOLDER_URI)
        val portExtra = intent?.getIntExtra(EXTRA_PORT, -1) ?: -1
        startServerWithConfig(folderUri, portExtra, markRunning = true)
    }

    private suspend fun restartFromIntent(intent: Intent?) {
        retryJob?.cancel()
        userPreferences.saveServerRunning(true)
        serverManager.stopServerInternal()
        startFromIntent(intent)
    }

    private suspend fun restoreIfNeeded() {
        val shouldRun = userPreferences.serverRunningFlow.first()
        if (!shouldRun) {
            stopSelfSafely()
            return
        }
        val folderUri = userPreferences.selectedFolderFlow.first()
        val port = userPreferences.serverPortFlow.first()
        startServerWithConfig(folderUri, port, markRunning = false)
    }

    private suspend fun startServerWithConfig(folderUriString: String?, portCandidate: Int, markRunning: Boolean) {
        if (folderUriString.isNullOrBlank()) {
            userPreferences.saveServerRunning(false)
            updateNotification("Server stopped: no media folder")
            stopSelfSafely()
            return
        }

        val port = if (portCandidate > 0) portCandidate else userPreferences.serverPortFlow.first()

        if (markRunning) {
            userPreferences.saveServerRunning(true)
        }
        userPreferences.saveServerPort(port)
        userPreferences.saveSelectedFolder(folderUriString)

        acquireLocks()
        val started = serverManager.startServerInternal(Uri.parse(folderUriString), port)
        if (started) {
            retryJob?.cancel()
            updateNotification("Server running on port $port")
        } else {
            releaseLocks()
            updateNotification("Waiting for network...")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob = serviceScope.launch {
            delay(RETRY_DELAY_MS)
            restoreIfNeeded()
        }
    }

    private suspend fun stopServerAndCleanup(userInitiated: Boolean) {
        retryJob?.cancel()
        if (userInitiated) {
            userPreferences.saveServerRunning(false)
        }
        serverManager.stopServerInternal()
        releaseLocks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
    }

    private fun stopSelfSafely() {
        retryJob?.cancel()
        releaseLocks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
    }

    private fun acquireLocks() {
        if (wifiLock?.isHeld != true) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LANflix:WiFiLock")
            wifiLock?.setReferenceCounted(false)
            try {
                wifiLock?.acquire()
            } catch (_: Exception) {
            }
        }

        if (wakeLock?.isHeld != true) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LANflix:WakeLock")
            wakeLock?.setReferenceCounted(false)
            try {
                wakeLock?.acquire()
            } catch (_: Exception) {
            }
        }
    }

    private fun releaseLocks() {
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        } finally {
            wifiLock = null
        }

        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        } finally {
            wakeLock = null
        }
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(message))
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, StreamingActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, MediaServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, 
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LANflix Server Online")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_lanflix_logo)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Server", stopPendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Media Server Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
