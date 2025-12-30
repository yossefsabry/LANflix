package com.thiyagu.media_server

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.thiyagu.media_server.server.KtorMediaStreamingServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class StreamingActivity : AppCompatActivity() {

    private lateinit var btnStartServer: MaterialButton
    private lateinit var btnRescan: MaterialButton
    private lateinit var btnAddMedia: MaterialButton
    private lateinit var btnLogs: MaterialButton
    private lateinit var btnOpenDashboard: MaterialButton
    
    private lateinit var cardMediaCore: MaterialCardView
    private lateinit var cardLogs: MaterialCardView
    
    private lateinit var statusBadge: TextView
    private lateinit var statusDot: View
    private lateinit var serverUrlText: TextView
    private lateinit var logText: TextView

    // Stats
    private lateinit var uptimeText: TextView
    private lateinit var loadText: TextView
    private lateinit var speedText: TextView
    private lateinit var libraryText: TextView
    private lateinit var activeText: TextView

    private var server: KtorMediaStreamingServer? = null
    private var selectedFolderUri: Uri? = null
    private var isLogsExpanded: Boolean = false
    
    // Logic vars
    private var uptimeSeconds = 0L
    private var uptimeJob: kotlinx.coroutines.Job? = null
    private var serverUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_streaming)

        initViews()
        setupListeners()
        
        // Initial State
        updateStats(false)
        val ip = getLocalIpAddress() ?: "0.0.0.0"
        serverUrlText.text = ip
        log("System initialized. Local IP: $ip")
    }

    private fun initViews() {
        btnStartServer = findViewById(R.id.btn_start_server)
        btnRescan = findViewById(R.id.btn_rescan)
        btnAddMedia = findViewById(R.id.btn_add_media)
        btnLogs = findViewById(R.id.btn_logs)
        btnOpenDashboard = findViewById(R.id.btn_open_dashboard)
        
        cardMediaCore = findViewById(R.id.card_media_core)
        cardLogs = findViewById(R.id.card_logs)
        
        statusBadge = findViewById(R.id.status_badge)
        statusDot = findViewById(R.id.status_dot)
        serverUrlText = findViewById(R.id.server_url_text)
        logText = findViewById(R.id.log_text)
        
        uptimeText = findViewById(R.id.tv_stat_uptime)
        loadText = findViewById(R.id.tv_stat_load)
        speedText = findViewById(R.id.tv_stat_speed)
        libraryText = findViewById(R.id.tv_stat_library)
        activeText = findViewById(R.id.tv_stat_active)
    }

    private fun setupListeners() {
        val folderPickerAction = {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            folderPickerLauncher.launch(intent)
        }

        btnRescan.setOnClickListener { folderPickerAction() }
        
        btnAddMedia.setOnClickListener {
             if (selectedFolderUri == null) {
                 Toast.makeText(this, "Please select a media folder first", Toast.LENGTH_SHORT).show()
             } else {
                 val intent = Intent(this, MediaManagementActivity::class.java)
                 intent.putExtra("FOLDER_URI", selectedFolderUri.toString())
                 startActivity(intent)
             }
        }
        
        btnLogs.setOnClickListener {
             isLogsExpanded = !isLogsExpanded
             cardLogs.visibility = if (isLogsExpanded) View.VISIBLE else View.GONE
        }
        
        findViewById<View>(R.id.btn_back_dashboard).setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.btn_profile).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        btnStartServer.setOnClickListener {
            if (server != null) {
                stopServer()
            } else {
                startServer()
            }
        }

        btnOpenDashboard.setOnClickListener {
            if (serverUrl.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(serverUrl))
                startActivity(intent)
            }
        }
    }

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Ignore if already granted or failed
                }
                selectedFolderUri = uri
                log("Media source updated: ${uri.path?.split(":")?.last() ?: "Selected Folder"}")
                Toast.makeText(this, "Library Updated", Toast.LENGTH_SHORT).show()
                updateLibrarySize(uri)
            }
        }
    }

    private fun startServer() {
        if (selectedFolderUri == null) {
            Toast.makeText(this, "Please select a media folder first", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Check network
            val ip = getLocalIpAddress()
            if (ip == null) {
                 Toast.makeText(this, "Not connected to a network", Toast.LENGTH_SHORT).show()
                 log("Error: No network connection found.")
                 return
            }

            server = KtorMediaStreamingServer(applicationContext, selectedFolderUri!!, 8888)
            server?.start()
            
            setStatus(true)
            
            serverUrl = "http://$ip:8888"
            serverUrlText.text = serverUrl
            log("Server started at $serverUrl")
            
            // Allow copying URL by clicking the text
            serverUrlText.setOnClickListener {
                 val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                 val clip = ClipData.newPlainText("Server URL", serverUrl)
                 clipboard.setPrimaryClip(clip)
                 Toast.makeText(this, "URL copied", Toast.LENGTH_SHORT).show()
            }
            
            startUptimeCounter()
            updateStats(true)

        } catch (e: Exception) {
            e.printStackTrace()
            log("Error starting server: ${e.message}")
            setStatus(false)
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        setStatus(false)
        stopUptimeCounter()
        updateStats(false)
        log("Server stopped.")
    }

    private fun setStatus(running: Boolean) {
        if (running) {
            statusBadge.text = "ONLINE"
            statusBadge.setTextColor(ContextCompat.getColor(this, R.color.lanflix_primary))
            statusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.lanflix_primary)
            
            btnStartServer.text = "Stop"
            btnStartServer.icon = ContextCompat.getDrawable(this, R.drawable.ic_pause)
            btnStartServer.backgroundTintList = ContextCompat.getColorStateList(this, R.color.lanflix_surface_hover)
            
            btnOpenDashboard.isEnabled = true
            btnOpenDashboard.alpha = 1.0f
        } else {
            statusBadge.text = "OFFLINE"
            statusBadge.setTextColor(ContextCompat.getColor(this, R.color.lanflix_text_sub))
            statusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.lanflix_text_sub)
            
            btnStartServer.text = "Start"
            btnStartServer.icon = ContextCompat.getDrawable(this, R.drawable.ic_play_arrow)
            btnStartServer.backgroundTintList = ContextCompat.getColorStateList(this, R.color.lanflix_primary)
            
            serverUrlText.text = getLocalIpAddress() ?: "Offline"
            serverUrlText.setOnClickListener(null)
            
            btnOpenDashboard.isEnabled = false
            btnOpenDashboard.alpha = 0.5f
        }
    }

    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val newLog = "> $timestamp: $message\n${logText.text}"
        logText.text = newLog
    }

    // --- Helpers ---

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
    
    // Stats Logic
    private fun startUptimeCounter() {
        uptimeSeconds = 0
        // Using lifecycleScope ensures coroutine is cancelled when activity is destroyed
        uptimeJob = lifecycleScope.launch {
            while (isActive) {
                uptimeSeconds++
                val hours = uptimeSeconds / 3600
                val minutes = (uptimeSeconds % 3600) / 60
                val seconds = uptimeSeconds % 60
                
                val timeString = if (hours > 0) {
                     String.format("%dh %dm %ds", hours, minutes, seconds)
                } else {
                     String.format("%dm %ds", minutes, seconds)
                }
                uptimeText.text = timeString
                delay(1000) // Non-blocking delay
            }
        }
    }
    
    private fun stopUptimeCounter() {
        uptimeJob?.cancel()
        uptimeJob = null
    }
    
    private fun updateStats(isRunning: Boolean) {
        if (!isRunning) {
            uptimeText.text = "0m"
            loadText.text = "0%"
            speedText.text = "0"
            activeText.text = "0"
            // Keep library text as is if possible, or reset? Reset for now.
            // libraryText.text = "0 GB" 
        } else {
            // Mock Real Data for things we can't easily measure without root/natives
            loadText.text = "4%" // Low load generally
            speedText.text = "120" // Mbps placeholder
            activeText.text = "0" // No session tracking implemented yet
        }
    }
    
    private fun updateLibrarySize(uri: Uri) {
         // Calculating real folder size is heavy, for now just show a "Ready" state or basic count
         // This needs proper DocumentsContract logic which is complex for a tree. 
         // We'll set a placeholder or "Scanned"
         libraryText.text = "Ready" 
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Cleanup server and uptime counter
        stopServer()
        stopUptimeCounter()
    }
}
