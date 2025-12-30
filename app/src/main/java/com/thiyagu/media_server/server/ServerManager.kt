package com.thiyagu.media_server.server

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class ServerManager(private val context: Context) {

    private var server: KtorMediaStreamingServer? = null
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state

    fun startServer(folderUri: Uri, port: Int = 8888) {
        // Prevent starting if already running
        if (_state.value is ServerState.Running) return
        
        try {
            _state.value = ServerState.Starting
            
            val ip = getLocalIpAddress()
            if (ip == null) {
                _state.value = ServerState.Error("No network connection found. Connect to Wi-Fi.")
                return
            }

            server = KtorMediaStreamingServer(context, folderUri, port)
            server?.start()

            val url = "http://$ip:$port"
            _state.value = ServerState.Running(url)
            
        } catch (e: Exception) {
            e.printStackTrace()
            stopServer() // Cleanup
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
            _state.value = ServerState.Stopped
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
