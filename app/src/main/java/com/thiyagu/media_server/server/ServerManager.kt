package com.thiyagu.media_server.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.thiyagu.media_server.service.MediaServerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class ServerManager(private val context: Context) {

    private var server: KtorMediaStreamingServer? = null
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state

    private var serverStartTime: Long = 0L

    fun getStartTime(): Long {
        return serverStartTime
    }

    fun getActiveConnections(): Int {
        return KtorMediaStreamingServer.activeConnections.get()
    }

    fun startServer(folderUri: Uri, port: Int = 8888) {
        // Prevent starting if already running
        if (_state.value is ServerState.Running) return
        
        // Start Service first
        val intent = Intent(context, MediaServerService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        
        // Then start Ktor logic
        startKtorInternal(folderUri, port)
    }

    private fun startKtorInternal(folderUri: Uri, port: Int) {
        try {
            _state.value = ServerState.Starting
            
            val ip = getLocalIpAddress()
            if (ip == null) {
                _state.value = ServerState.Error("No network connection found. Connect to Wi-Fi.")
                return
            }

            server = KtorMediaStreamingServer(context, folderUri, port)
            server?.start()

            serverStartTime = System.currentTimeMillis() // Track start time

            val url = "http://$ip:$port"
            _state.value = ServerState.Running(url)
            
        } catch (e: Exception) {
            e.printStackTrace()
            stopServer() // Cleanup will also stop service
            _state.value = ServerState.Error("Failed to start server: ${e.message}")
        }
    }

    fun stopServer() {
        try {
            server?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            server = null
            serverStartTime = 0L // Reset logic
            _state.value = ServerState.Stopped
            
            // Stop Service
            val intent = Intent(context, MediaServerService::class.java)
            intent.action = MediaServerService.ACTION_STOP
            context.startService(intent) // Send stop action
        }
    }
    
    fun isRunning(): Boolean {
        return _state.value is ServerState.Running
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return null
    }
}
