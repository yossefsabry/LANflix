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
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.thiyagu.media_server.server.KtorMediaStreamingServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

import com.thiyagu.media_server.databinding.ActivityStreamingBinding

class StreamingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStreamingBinding

    private var server: KtorMediaStreamingServer? = null
    private var selectedFolderUri: Uri? = null
    private var isLogsExpanded: Boolean = false
    
    // Logic vars
    private var uptimeSeconds = 0L
    private var uptimeJob: kotlinx.coroutines.Job? = null
    private var serverUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupListeners()
        
        // Initial State
        updateStats(false)
        val ip = getLocalIpAddress() ?: "0.0.0.0"
        binding.serverUrlText.text = ip
        log("System initialized. Local IP: $ip")
    }

    private fun setupListeners() {
        val folderPickerAction = {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            folderPickerLauncher.launch(intent)
        }

        binding.btnRescan.setOnClickListener { folderPickerAction() }
        
        binding.btnAddMedia.setOnClickListener {
             if (selectedFolderUri == null) {
                 Toast.makeText(this, "Please select a media folder first", Toast.LENGTH_SHORT).show()
             } else {
                 val intent = Intent(this, MediaManagementActivity::class.java)
                 intent.putExtra("FOLDER_URI", selectedFolderUri.toString())
                 startActivity(intent)
             }
        }
        
        binding.btnLogs.setOnClickListener {
             isLogsExpanded = !isLogsExpanded
             binding.cardLogs.visibility = if (isLogsExpanded) View.VISIBLE else View.GONE
        }
        
        binding.btnBackDashboard.setOnClickListener {
            finish()
        }

        binding.btnProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        binding.btnStartServer.setOnClickListener {
            if (server != null) {
                stopServer()
            } else {
                startServer()
            }
        }

        binding.btnOpenDashboard.setOnClickListener {
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
            binding.serverUrlText.text = serverUrl
            log("Server started at $serverUrl")
            
            // Allow copying URL by clicking the text
            binding.serverUrlText.setOnClickListener {
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
            binding.statusBadge.text = "ONLINE"
            binding.statusBadge.setTextColor(ContextCompat.getColor(this, R.color.lanflix_primary))
            binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.lanflix_primary)
            
            binding.btnStartServer.text = "Stop"
            binding.btnStartServer.icon = ContextCompat.getDrawable(this, R.drawable.ic_pause)
            binding.btnStartServer.backgroundTintList = ContextCompat.getColorStateList(this, R.color.lanflix_surface_hover)
            
            binding.btnOpenDashboard.isEnabled = true
            binding.btnOpenDashboard.alpha = 1.0f
        } else {
            binding.statusBadge.text = "OFFLINE"
            binding.statusBadge.setTextColor(ContextCompat.getColor(this, R.color.lanflix_text_sub))
            binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.lanflix_text_sub)
            
            binding.btnStartServer.text = "Start"
            binding.btnStartServer.icon = ContextCompat.getDrawable(this, R.drawable.ic_play_arrow)
            binding.btnStartServer.backgroundTintList = ContextCompat.getColorStateList(this, R.color.lanflix_primary)
            
            binding.serverUrlText.text = getLocalIpAddress() ?: "Offline"
            binding.serverUrlText.setOnClickListener(null)
            
            binding.btnOpenDashboard.isEnabled = false
            binding.btnOpenDashboard.alpha = 0.5f
        }
    }

    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val newLog = "> $timestamp: $message\n${binding.logText.text}"
        binding.logText.text = newLog
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
                binding.tvStatUptime.text = timeString
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
            binding.tvStatUptime.text = "0m"
            binding.tvStatLoad.text = "0%"
            binding.tvStatSpeed.text = "0"
            binding.tvStatActive.text = "0"
            // Keep library text as is if possible, or reset? Reset for now.
            // libraryText.text = "0 GB" 
        } else {
            // Mock Real Data for things we can't easily measure without root/natives
            binding.tvStatLoad.text = "4%" // Low load generally
            binding.tvStatSpeed.text = "120" // Mbps placeholder
            binding.tvStatActive.text = "0" // No session tracking implemented yet
        }
    }
    
    private fun updateLibrarySize(uri: Uri) {
         binding.tvStatLibrary.text = "Scanning..."
         lifecycleScope.launch(Dispatchers.IO) {
             try {
                 val dir = DocumentFile.fromTreeUri(applicationContext, uri)
                 val files = dir?.listFiles() ?: emptyArray()
                 
                 // Count video files
                 val videoCount = files.count { file ->
                     if (file.isDirectory) return@count false
                     val name = file.name?.lowercase() ?: ""
                     name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") || name.endsWith(".mov") || name.endsWith(".webm")
                 }
                 
                 withContext(Dispatchers.Main) {
                     binding.tvStatLibrary.text = "$videoCount Videos"
                 }
             } catch (e: Exception) {
                 e.printStackTrace()
                 withContext(Dispatchers.Main) {
                     binding.tvStatLibrary.text = "Error"
                 }
             }
         }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Cleanup server and uptime counter
        stopServer()
        stopUptimeCounter()
    }
}
