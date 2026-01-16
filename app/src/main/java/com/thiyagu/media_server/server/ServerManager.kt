package com.thiyagu.media_server.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.thiyagu.media_server.service.MediaServerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class ServerManager(private val context: Context, private val userPreferences: com.thiyagu.media_server.data.UserPreferences) {

    private var server: KtorMediaStreamingServer? = null
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state

    private var serverStartTime: Long = 0L
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    fun getStartTime(): Long {
        return serverStartTime
    }

    fun getConnectionStats(): ConnectionStats {
        return server?.getConnectionStats() ?: ConnectionStats(0, 0, 0)
    }

    fun startServer(folderUri: Uri, port: Int = 8888) {
        // Prevent starting if already running
        if (_state.value is ServerState.Running || _state.value is ServerState.Starting || _state.value is ServerState.Stopping) return
        
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

            server = KtorMediaStreamingServer(context, folderUri, userPreferences, port)
            server?.start()

            serverStartTime = System.currentTimeMillis() // Track start time

            val url = "http://$ip:$port"
            _state.value = ServerState.Running(url, server!!.scanStatus)
            
            // Register NSD
            registerService(port)
            
        } catch (e: Exception) {
            e.printStackTrace()
            stopServer() // Cleanup will also stop service
            _state.value = ServerState.Error("Failed to start server: ${e.message}")
        }
    }

    fun stopServer() {
        if (_state.value is ServerState.Stopped || _state.value is ServerState.Stopping) return

        _state.value = ServerState.Stopping
        scope.launch {
            try {
                server?.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                server = null
                serverStartTime = 0L // Reset logic
                _state.value = ServerState.Stopped
                
                // Unregister NSD
                unregisterService()
                
                // Stop Service
                val intent = Intent(context, MediaServerService::class.java)
                intent.action = MediaServerService.ACTION_STOP
                context.startService(intent) // Send stop action
            }
        }
    }
    
    fun isRunning(): Boolean {
        return _state.value is ServerState.Running
    }
    
    suspend fun refreshCache() {
         server?.refreshCache()
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

    // --- NSD (Network Service Discovery) ---

    private var registrationListener: android.net.nsd.NsdManager.RegistrationListener? = null

    private fun registerService(port: Int) {
        try {
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager
            
            val serviceNameStr = kotlinx.coroutines.runBlocking {
                userPreferences.serverNameFlow.first()
            }
            
            val serviceInfo = android.net.nsd.NsdServiceInfo().apply {
                serviceName = serviceNameStr
                serviceType = "_lanflix._tcp"
                setPort(port)
                // In the future, we can add attributes here like:
                // setAttribute("secured", "false")
            }

            val authEnabled = kotlinx.coroutines.runBlocking {
                val enabled = userPreferences.serverAuthEnabledFlow.first()
                val hash = userPreferences.serverAuthPinHashFlow.first()
                enabled && !hash.isNullOrBlank()
            }
            serviceInfo.setAttribute("auth", if (authEnabled) "1" else "0")

            registrationListener = object : android.net.nsd.NsdManager.RegistrationListener {
                override fun onServiceRegistered(NsdServiceInfo: android.net.nsd.NsdServiceInfo) {
                    // Save the registered name (it might change if conflict)
                    // _serviceName = NsdServiceInfo.serviceName
                }

                override fun onRegistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                   // Registration failed
                }

                override fun onServiceUnregistered(arg0: android.net.nsd.NsdServiceInfo) {
                    // Service has been unregistered
                }

                override fun onUnregistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                    // Unregistration failed
                }
            }

            nsdManager.registerService(serviceInfo, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unregisterService() {
        try {
            if (registrationListener != null) {
                val nsdManager = context.getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager
                nsdManager.unregisterService(registrationListener)
                registrationListener = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
