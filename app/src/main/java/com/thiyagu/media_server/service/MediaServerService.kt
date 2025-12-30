package com.thiyagu.media_server.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.thiyagu.media_server.R
import com.thiyagu.media_server.StreamingActivity
import com.thiyagu.media_server.server.ServerManager
import org.koin.android.ext.android.inject

class MediaServerService : Service() {

    private val serverManager: ServerManager by inject()
    
    companion object {
        const val CHANNEL_ID = "MediaServerChannel"
        const val NOTIFICATION_ID = 1001
        
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Default: Start/Keep Running
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Note: The actual Ktor start logic is currently triggered by the ViewModel calling ServerManager. 
        // Ideally, we might want to move that trigger here, but for now, 
        // the Service acts as a "Life Support" to keep the process alive while ServerManager runs kTor.
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        // If the service is destroyed, we ensure the server is stopped
        serverManager.stopServer()
        super.onDestroy()
    }

    private fun createNotification(): Notification {
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
            .setContentText("Media streaming is active in background")
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
