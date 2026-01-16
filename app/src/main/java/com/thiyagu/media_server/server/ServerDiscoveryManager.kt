package com.thiyagu.media_server.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URL

data class DiscoveredServer(
    val name: String,
    val ip: String,
    val port: Int,
    val isSecured: Boolean = false, // Placeholder for future
    val isOnline: Boolean = true
)

class ServerDiscoveryManager(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()
    
    // Internal list to avoid duplicates
    private val serverSet = mutableSetOf<DiscoveredServer>()
    private val lastSeenByKey = mutableMapOf<String, Long>()
    private val failureCounts = mutableMapOf<String, Int>()
    private val handler = Handler(Looper.getMainLooper())
    private var pruneRunning = false
    private val pingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var healthJob: Job? = null

    private val pruneRunnable = object : Runnable {
        override fun run() {
            pruneStaleServers()
            if (pruneRunning) {
                handler.postDelayed(this, PRUNE_INTERVAL_MS)
            }
        }
    }

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isScanning = false

    fun startDiscovery() {
        if (isScanning) return
        startPruning()
        startHealthChecks()
        
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
                            val attrs = serviceInfo.attributes
                            val isSecured = attrs?.get("auth")?.let { String(it, Charsets.UTF_8) == "1" } ?: false
                            val server = DiscoveredServer(name, ip, port, isSecured, true)
                            val key = serverKey(ip, port)
                            val now = System.currentTimeMillis()
                            
                            // Update StateFlow on 'add'
                            synchronized(serverSet) {
                                val existing = serverSet.firstOrNull { it.ip == ip && it.port == port }
                                if (existing != null && existing != server) {
                                    serverSet.remove(existing)
                                }
                                if (existing == null || existing != server) {
                                    serverSet.add(server)
                                }
                                lastSeenByKey[key] = now
                                failureCounts[key] = 0
                                _discoveredServers.value = serverSet.toList()
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val name = service.serviceName
                synchronized(serverSet) {
                    val removed = serverSet.removeAll { it.name == name }
                    if (removed) {
                        lastSeenByKey.entries.removeIf { entry ->
                            serverSet.none { serverKey(it.ip, it.port) == entry.key }
                        }
                        _discoveredServers.value = serverSet.toList()
                    }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isScanning = false
                stopPruning()
                stopHealthChecks()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
                stopPruning()
                stopHealthChecks()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
                stopPruning()
                stopHealthChecks()
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
            stopPruning()
            stopHealthChecks()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startHealthChecks() {
        if (healthJob != null) return
        healthJob = pingScope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val snapshot = synchronized(serverSet) { serverSet.toList() }
                snapshot.forEach { server ->
                    val online = pingServer(server)
                    val key = serverKey(server.ip, server.port)
                    synchronized(serverSet) {
                        val existing = serverSet.firstOrNull { serverKey(it.ip, it.port) == key }
                        if (existing != null) {
                            if (online) {
                                failureCounts[key] = 0
                                lastSeenByKey[key] = System.currentTimeMillis()
                                if (!existing.isOnline) {
                                    serverSet.remove(existing)
                                    serverSet.add(existing.copy(isOnline = true))
                                    _discoveredServers.value = serverSet.toList()
                                }
                            } else {
                                val fails = (failureCounts[key] ?: 0) + 1
                                failureCounts[key] = fails
                                if (fails >= MAX_PING_FAILURES) {
                                    serverSet.remove(existing)
                                    failureCounts.remove(key)
                                    lastSeenByKey.remove(key)
                                    _discoveredServers.value = serverSet.toList()
                                } else if (existing.isOnline) {
                                    serverSet.remove(existing)
                                    serverSet.add(existing.copy(isOnline = false))
                                    _discoveredServers.value = serverSet.toList()
                                }
                            }
                        }
                    }
                }
                delay(HEALTH_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun stopHealthChecks() {
        healthJob?.cancel()
        healthJob = null
    }

    private fun pingServer(server: DiscoveredServer): Boolean {
        return try {
            val url = URL("http://${server.ip}:${server.port}/api/ping?client=discovery")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1500
                readTimeout = 1500
                setRequestProperty("X-Lanflix-Discovery", "1")
                setRequestProperty("X-Lanflix-Client", "discovery")
            }
            val code = conn.responseCode
            code == 200 || code == 401 || code == 503
        } catch (_: Exception) {
            false
        }
    }

    private fun startPruning() {
        if (pruneRunning) return
        pruneRunning = true
        handler.postDelayed(pruneRunnable, PRUNE_INTERVAL_MS)
    }

    private fun stopPruning() {
        pruneRunning = false
        handler.removeCallbacks(pruneRunnable)
    }

    private fun pruneStaleServers() {
        val now = System.currentTimeMillis()
        val staleKeys = lastSeenByKey.filterValues { now - it > STALE_THRESHOLD_MS }.keys
        if (staleKeys.isEmpty()) return

        synchronized(serverSet) {
            serverSet.removeAll { staleKeys.contains(serverKey(it.ip, it.port)) }
            staleKeys.forEach { lastSeenByKey.remove(it) }
            _discoveredServers.value = serverSet.toList()
        }
    }

    private fun serverKey(ip: String, port: Int): String = "$ip:$port"

    companion object {
        private const val PRUNE_INTERVAL_MS = 10_000L
        private const val STALE_THRESHOLD_MS = 20_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 7_000L
        private const val MAX_PING_FAILURES = 2
    }
}
