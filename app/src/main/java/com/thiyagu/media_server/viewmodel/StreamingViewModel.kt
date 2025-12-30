package com.thiyagu.media_server.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thiyagu.media_server.data.repository.MediaRepository
import com.thiyagu.media_server.server.ServerManager
import com.thiyagu.media_server.server.ServerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class StreamingViewModel(
    private val serverManager: ServerManager,
    private val mediaRepository: MediaRepository
) : ViewModel() {

    // Server State
    val serverState: StateFlow<ServerState> = serverManager.state

    // Library Stats (Reactive from DB)
    val librarySize: StateFlow<Int> = mediaRepository.allMedia
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    // Uptime Logic
    private val _uptimeString = MutableStateFlow("0m")
    val uptimeString: StateFlow<String> = _uptimeString.asStateFlow()
    
    private var uptimeSeconds = 0L

    init {
        // Observe server state to manage timer
        viewModelScope.launch {
            serverState.collectLatest { state ->
                if (state is ServerState.Running) {
                    startUptimeCounter()
                } else {
                    stopUptimeCounter()
                }
            }
        }
    }

    fun startServer(folderUri: Uri) {
        serverManager.startServer(folderUri)
    }

    fun stopServer() {
        serverManager.stopServer()
    }
    
    fun rescanLibrary(folderUri: Uri) {
        viewModelScope.launch {
            mediaRepository.scanAndSync(folderUri)
        }
    }



    // Stats logic
    private val _networkSpeed = MutableStateFlow(0f)
    val networkSpeed: StateFlow<Float> = _networkSpeed.asStateFlow()
    
    private val _activeConnections = MutableStateFlow(0)
    val activeConnections: StateFlow<Int> = _activeConnections.asStateFlow()

    private suspend fun startUptimeCounter() {
        var lastTxBytes = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
        
        while (currentCoroutineContext().isActive) {
            // 1. Uptime
            val startTime = serverManager.getStartTime()
            if (startTime > 0) {
                val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
                uptimeSeconds = elapsedSeconds
                
                val hours = uptimeSeconds / 3600
                val minutes = (uptimeSeconds % 3600) / 60
                val seconds = uptimeSeconds % 60
                
                val timeString = if (hours > 0) {
                     String.format("%dh %dm %ds", hours, minutes, seconds)
                } else {
                     String.format("%dm %ds", minutes, seconds)
                }
                _uptimeString.value = timeString
            } else {
                _uptimeString.value = "0m 0s"
            }
            
            // 2. Network Speed
            val currentTxBytes = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
            val deltaBytes = currentTxBytes - lastTxBytes
            lastTxBytes = currentTxBytes
            
            // Convert bytes/sec to Mbps (Megabits per second)
            // (bytes * 8) / (1000 * 1000)
            val mbps = (deltaBytes * 8f) / 1_000_000f
            _networkSpeed.value = mbps
            
            // 3. Active Connections
            _activeConnections.value = serverManager.getActiveConnections()
            
            delay(1000)
        }
    }
    
    private fun stopUptimeCounter() {
        uptimeSeconds = 0
        _uptimeString.value = "0m"
        _networkSpeed.value = 0f
        _activeConnections.value = 0
    }
}
