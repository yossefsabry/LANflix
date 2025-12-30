package com.thiyagu.media_server.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

data class DiscoveredServer(
    val name: String,
    val ip: String,
    val port: Int,
    val isSecured: Boolean = false // Placeholder for future
)

class ServerDiscoveryManager(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()
    
    // Internal list to avoid duplicates
    private val serverSet = mutableSetOf<DiscoveredServer>()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isScanning = false

    fun startDiscovery() {
        if (isScanning) return
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
               isScanning = true
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains("_lanflix._tcp")) { // Check correct type
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            // Code
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host ?: return
                            val port = serviceInfo.port
                            val name = serviceInfo.serviceName
                            
                             // Ensure we iterate to find IPv4
                            val ip = host.hostAddress
                            
                            val server = DiscoveredServer(name, ip, port)
                            
                            // Update StateFlow on 'add'
                            synchronized(serverSet) {
                                if (serverSet.none { it.ip == ip }) {
                                    serverSet.add(server)
                                    _discoveredServers.value = serverSet.toList()
                                }
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                // Handle logic if needed, but simple scan list is fine
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isScanning = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
        }

        nsdManager.discoverServices("_lanflix._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        try {
            if (discoveryListener != null) {
                nsdManager.stopServiceDiscovery(discoveryListener)
                discoveryListener = null
                isScanning = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
