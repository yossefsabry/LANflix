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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import com.thiyagu.media_server.data.UserPreferences

class StreamingViewModel(
    private val serverManager: ServerManager,
    private val mediaRepository: MediaRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    // Saved Folder
    val savedFolderUri: StateFlow<String?> = userPreferences.selectedFolderFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // Subfolder Scanning Preference
    val isSubfolderScanningEnabled: StateFlow<Boolean> = userPreferences.subfolderScanningFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

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

    // Live Scanning Status
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val scanStatus = serverState // serverState is already a separate StateFlow
        .flatMapLatest { state ->
             if (state is ServerState.Running) {
                 state.scanStatusFlow
             } else {
                 kotlinx.coroutines.flow.flowOf(com.thiyagu.media_server.server.KtorMediaStreamingServer.ScanStatus(false, 0))
             }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = com.thiyagu.media_server.server.KtorMediaStreamingServer.ScanStatus(false, 0)
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
        viewModelScope.launch {
            userPreferences.saveSelectedFolder(folderUri.toString())
        }
        serverManager.requestStartServer(folderUri)
    }

    fun stopServer() {
        serverManager.requestStopServer()
    }
    
    fun toggleSubfolderScanning(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.saveSubfolderScanning(enabled)
            
            // Auto-restart server if running
            val wasRunning = serverState.value is ServerState.Running
            if (wasRunning) {
                savedFolderUri.value?.let { uriString ->
                    val uri = Uri.parse(uriString)
                    serverManager.requestRestartServer(uri)
                }
            }
            
            // Trigger rescan if we have a folder selected
            savedFolderUri.value?.let { uriString ->
                 rescanLibrary(Uri.parse(uriString), restartServer = false) // Don't restart again
            }
        }
    }
    
    fun rescanLibrary(folderUri: Uri, restartServer: Boolean = true) {
        viewModelScope.launch {
            userPreferences.saveSelectedFolder(folderUri.toString())
            val includeSubfolders = userPreferences.subfolderScanningFlow.first()
            
            // Auto-restart server if running and folder changed
            val wasRunning = serverState.value is ServerState.Running
            if (wasRunning && restartServer) {
                serverManager.requestRestartServer(folderUri)
            }
            
            // 1. Sync Database
            mediaRepository.scanAndSync(folderUri, includeSubfolders)
            
            // 2. Refresh Running Server Cache (if not restarted)
            if (!wasRunning || !restartServer) {
                serverManager.refreshCache()
            }
        }
    }



    // Stats logic
    private val _networkSpeed = MutableStateFlow(0f)
    val networkSpeed: StateFlow<Float> = _networkSpeed.asStateFlow()
    
    private val _connectedDevices = MutableStateFlow(0)
    val connectedDevices: StateFlow<Int> = _connectedDevices.asStateFlow()
    
    private val _streamingDevices = MutableStateFlow(0)
    val streamingDevices: StateFlow<Int> = _streamingDevices.asStateFlow()

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
            val stats = serverManager.getConnectionStats()
            _connectedDevices.value = stats.connectedDevices
            _streamingDevices.value = stats.streamingDevices
            
            delay(1000)
        }
    }
    
    private fun stopUptimeCounter() {
        uptimeSeconds = 0
        _uptimeString.value = "0m"
        _networkSpeed.value = 0f
        _connectedDevices.value = 0
        _streamingDevices.value = 0
    }
}
