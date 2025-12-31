package com.thiyagu.media_server

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.thiyagu.media_server.databinding.ActivityStreamingBinding
import com.thiyagu.media_server.server.ServerState
import com.thiyagu.media_server.viewmodel.StreamingViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class StreamingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStreamingBinding
    private val viewModel: StreamingViewModel by viewModel()

    private var selectedFolderUri: Uri? = null
    private var isLogsExpanded: Boolean = false
    private var serverUrl: String = "" // For click listener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupListeners()
        
        // --- View Model Observation ---
        
        // 1. Server State
        lifecycleScope.launch {
            viewModel.serverState.collectLatest { state ->
                when(state) {
                    is ServerState.Stopped -> {
                        setStatus(false)
                        binding.serverUrlText.text = getLocalIpAddress() ?: "Offline"
                        log("Server Stopped")
                    }
                    is ServerState.Starting -> {
                        binding.serverUrlText.text = "Starting..."
                        // Could disable buttons here
                    }
                    is ServerState.Running -> {
                        setStatus(true)
                        serverUrl = state.url
                        binding.serverUrlText.text = state.url
                        log("Server Running at ${state.url}")
                        
                        // Setup Copy Listener
                        binding.serverUrlText.setOnClickListener {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Server URL", state.url)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(this@StreamingActivity, "URL copied", Toast.LENGTH_SHORT).show()
                        }
                    }
                    is ServerState.Error -> {
                        setStatus(false)
                         binding.serverUrlText.text = "Error"
                        Toast.makeText(this@StreamingActivity, state.message, Toast.LENGTH_LONG).show()
                        log("Error: ${state.message}")
                    }
                }
            }
        }
        
        // 2. Uptime
        lifecycleScope.launch {
            viewModel.uptimeString.collect { timeString ->
                binding.tvStatUptime.text = timeString
            }
        }
        
        // 3. Library Stats
        lifecycleScope.launch {
            viewModel.librarySize.collect { count ->
                binding.tvStatLibrary.text = "$count Videos"
            }
        }
        
        // 4. Network Speed
        lifecycleScope.launch {
            viewModel.networkSpeed.collect { mbps ->
                binding.tvStatSpeed.text = String.format("%.1f", mbps)
            }
        }
        
        // 5. Active Connections
        lifecycleScope.launch {
            viewModel.activeConnections.collect { count ->
                binding.tvStatActive.text = count.toString()
            }
        }
        
        // Subfolder Scanning UI Link
        lifecycleScope.launch {
            viewModel.isSubfolderScanningEnabled.collect { enabled ->
                binding.switchSubfolders.setOnCheckedChangeListener(null)
                binding.switchSubfolders.isChecked = enabled
                binding.switchSubfolders.setOnCheckedChangeListener { _, isChecked ->
                    viewModel.toggleSubfolderScanning(isChecked)
                }
            }
        }
        
        // 6. Saved Folder Persistence
        lifecycleScope.launch {
            viewModel.savedFolderUri.collect { uriString ->
                if (!uriString.isNullOrEmpty()) {
                    selectedFolderUri = Uri.parse(uriString)
                    val folderName = try {
                        Uri.parse(uriString).path?.split(":")?.last() ?: "Select Folder"
                    } catch (e: Exception) { "Select Folder" }
                    
                    // Update Button Text (Legacy)
                    binding.btnAddMedia.text = folderName
                    
                    // Update Path Display (New)
                    try {
                        val pathRaw = Uri.parse(uriString).path ?: ""
                        val cleanPath = pathRaw
                            .replace("/tree/primary:", "/Internal Storage/")
                            .replace(":", "/")
                        
                        binding.tvSelectedFolderPath.text = cleanPath
                        binding.tvSelectedFolderPath.visibility = View.VISIBLE
                    } catch (e: Exception) {
                        binding.tvSelectedFolderPath.visibility = View.GONE
                    }

                    log("Loaded saved folder: $folderName")
                } else {
                    binding.tvSelectedFolderPath.visibility = View.GONE
                }
            }
        }
        
        // Initial Log
        log("System initialized.")
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
                 intent.putExtra("INCLUDE_SUBFOLDERS", viewModel.isSubfolderScanningEnabled.value)
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
            val state = viewModel.serverState.value
            if (state is ServerState.Running) {
                viewModel.stopServer()
            } else {
                startServer()
            }
        }

        // Dashboard button disabled
        // binding.btnOpenDashboard.setOnClickListener { ... }
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
                    // Ignore
                }
                selectedFolderUri = uri
                // Log handled by observer
                // log("Media source updated: ${uri.path?.split(":")?.last() ?: "Selected Folder"}")
                Toast.makeText(this, "Library Updated", Toast.LENGTH_SHORT).show()
                viewModel.rescanLibrary(uri)
            }
        }
    }

    private fun startServer() {
        if (selectedFolderUri == null) {
            Toast.makeText(this, "Please select a media folder first", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.startServer(selectedFolderUri!!)
    }

    private fun setStatus(running: Boolean) {
        if (running) {
            binding.statusBadge.text = "ONLINE"
            binding.statusBadge.setTextColor(ContextCompat.getColor(this, R.color.lanflix_primary))
            binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.lanflix_primary)
            
            binding.btnStartServer.text = "Stop"
            binding.btnStartServer.icon = ContextCompat.getDrawable(this, R.drawable.ic_pause)
            binding.btnStartServer.backgroundTintList = ContextCompat.getColorStateList(this, R.color.lanflix_surface_hover)
            
            binding.btnStartServer.backgroundTintList = ContextCompat.getColorStateList(this, R.color.lanflix_surface_hover)
            
            // Dashboard button disabled as per request
            binding.btnOpenDashboard.isEnabled = false
            binding.btnOpenDashboard.alpha = 0.5f
            
            // Stats updated by ViewModel flows (Speed & Active)
            binding.tvStatLoad.text = "4%"
            
        } else {
            binding.statusBadge.text = "OFFLINE"
            binding.statusBadge.setTextColor(ContextCompat.getColor(this, R.color.lanflix_text_sub))
            binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.lanflix_text_sub)
            
            binding.btnStartServer.text = "Start"
            binding.btnStartServer.icon = ContextCompat.getDrawable(this, R.drawable.ic_play_arrow)
            binding.btnStartServer.backgroundTintList = ContextCompat.getColorStateList(this, R.color.lanflix_primary)
            
            binding.serverUrlText.setOnClickListener(null)
            
            binding.btnOpenDashboard.isEnabled = false
            binding.btnOpenDashboard.alpha = 0.5f
            
            // Reset Stats (Speed & Active are reset by ViewModel)
            binding.tvStatLoad.text = "0%"
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
}
