package com.thiyagu.media_server

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class ClientActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var connectionContainer: LinearLayout
    private lateinit var loadingOverlay: android.widget.FrameLayout
    private lateinit var etIpAddress: TextInputEditText
    private lateinit var rvServers: androidx.recyclerview.widget.RecyclerView
    private val discoveryManager: com.thiyagu.media_server.server.ServerDiscoveryManager by inject()
    private lateinit var serverAdapter: com.thiyagu.media_server.server.ServerAdapter
    
    // Security: Track connected host strictness
    private var currentHost: String? = null
    
    // Connection timeout handling
    private val connectionTimeoutHandler = Handler(Looper.getMainLooper())
    private var connectionTimeoutRunnable: Runnable? = null
    private val CONNECTION_TIMEOUT_MS = 30000L // 30 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)

        etIpAddress = findViewById(R.id.et_ip_address)
        val btnConnect = findViewById<MaterialButton>(R.id.btn_connect)
        webView = findViewById(R.id.webview)
        connectionContainer = findViewById(R.id.connection_container)
        loadingOverlay = findViewById(R.id.loading_overlay)
        rvServers = findViewById(R.id.rv_servers)
        
        val btnBack = findViewById<android.view.View>(R.id.btn_back_home)
        
        btnBack.setOnClickListener {
            if (webView.visibility == android.view.View.VISIBLE) {
                // Return to input mode with full reset
                resetWebViewState()
            } else {
                // Return to Home
                finish()
            }
        }

        // Configure WebView with performance optimizations
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null) // Hardware acceleration
        
        // Optimize Render Priority (API 26+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            
            // Performance improvements
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            databaseEnabled = true
            
            // Media optimizations
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false // Security
            allowContentAccess = false
            
            // Zoom and viewport
            setSupportZoom(false)
            builtInZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        
        webView.webViewClient = object : WebViewClient() {
            
            // Show blocking loader when page starts loading
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url != "about:blank") {
                    loadingOverlay.visibility = View.VISIBLE
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                
                if (url.endsWith("/exit") || url == "http://exit/") {
                    // Handle Exit: Full reset to connection screen
                    resetWebViewState()
                    return true
                }
                
                // Allow internal app navigation BEFORE security check
                if (url.endsWith(".mp4", true) || url.endsWith(".mkv", true) || url.endsWith(".avi", true) || url.endsWith(".mov", true) || url.endsWith(".webm", true)) {
                    // Handle Video: Launch Native Player
                    val intent = android.content.Intent(this@ClientActivity, VideoPlayerActivity::class.java)
                    intent.putExtra("VIDEO_URL", url)
                    startActivity(intent)
                    return true
                }
                
                if (url.endsWith("/profile") || url == "http://profile/") {
                    // Handle Profile: Navigate to ProfileActivity
                    try {
                        val intent = android.content.Intent(this@ClientActivity, ProfileActivity::class.java)
                        intent.putExtra("SOURCE_ACTIVITY", "ClientActivity")
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@ClientActivity, getString(R.string.error_open_profile), Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                
                if (url.endsWith("/settings") || url == "http://settings/") {
                    // Handle Settings: Navigate to AppSettingsActivity
                    try {
                        val intent = android.content.Intent(this@ClientActivity, AppSettingsActivity::class.java)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@ClientActivity, getString(R.string.error_open_settings), Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                // Security Check: Strict Host Enforcement
                val requestHost = android.net.Uri.parse(url).host
                if (currentHost != null && requestHost != null && requestHost != currentHost) {
                    // Unauthorized Redirect Detected! Disconnect immediately.
                    resetWebViewState()
                    Toast.makeText(this@ClientActivity, "Disconnected: External redirect blocked", Toast.LENGTH_LONG).show()
                    return true
                }

                return false // Keep navigation inside WebView
            }

            // Optimization: Show WebView as soon as content is visible (API 23+)
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                cancelConnectionTimeout() // Connection succeeded
                loadingOverlay.visibility = View.GONE // Always hide loader
                if (url != "about:blank") {
                    webView.visibility = View.VISIBLE
                    connectionContainer.visibility = View.GONE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                cancelConnectionTimeout() // Connection succeeded
                loadingOverlay.visibility = View.GONE // Always hide loader
                // Prevent white screen flash by ignoring about:blank
                if (url != "about:blank") {
                    webView.visibility = View.VISIBLE
                    connectionContainer.visibility = View.GONE
                }
            }
            
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                loadingOverlay.visibility = View.GONE
                connectionContainer.visibility = View.VISIBLE
                
                // Show helpful error message
                val errorMsg = when (errorCode) {
                    android.webkit.WebViewClient.ERROR_HOST_LOOKUP -> 
                        this@ClientActivity.getString(R.string.error_host_lookup)
                    android.webkit.WebViewClient.ERROR_CONNECT -> 
                        this@ClientActivity.getString(R.string.error_connect)
                    android.webkit.WebViewClient.ERROR_TIMEOUT -> 
                        this@ClientActivity.getString(R.string.error_timeout)
                    else -> this@ClientActivity.getString(R.string.error_connection_general, description ?: "Unknown error")
                }
                
                androidx.appcompat.app.AlertDialog.Builder(this@ClientActivity)
                    .setTitle(this@ClientActivity.getString(R.string.dialog_connection_failed_title))
                    .setMessage(errorMsg)
                    .setPositiveButton(this@ClientActivity.getString(R.string.action_retry)) { _, _ ->
                        failingUrl?.let { loadUrl(it) }
                    }
                    .setNegativeButton(this@ClientActivity.getString(R.string.action_cancel), null)
                    .show()
            }
        }

        btnConnect.setOnClickListener {
            val ipAddress = etIpAddress.text.toString().trim()
            if (ipAddress.isNotEmpty()) {
                // Show loading immediately for instant feedback
                loadingOverlay.visibility = View.VISIBLE
                connectionContainer.visibility = View.GONE
                
                // Dismiss keyboard
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(etIpAddress.windowToken, 0)

                currentHost = ipAddress // Set strict host
                val theme = if (isDarkMode()) "dark" else "light"
                val url = "http://$ipAddress:8888?theme=$theme"
                loadUrl(url)
            }
        }
        
        // --- Server Discovery Setup ---
        setupDiscovery()
        
        // Modern back press handling
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
                    webView.goBack()
                } else if (webView.visibility == View.VISIBLE) {
                    // If at root of WebView, go back to connection screen with full reset
                    resetWebViewState()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }
    
    private fun isDarkMode(): Boolean {
        val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    
    private fun setupDiscovery() {
        // discoveryManager injected via Koin
        serverAdapter = com.thiyagu.media_server.server.ServerAdapter { server ->
            // Click Handler
            if (server.isSecured) {
                showPasswordDialog(server)
            } else {
                // Direct Connect
                etIpAddress.setText(server.ip)
                currentHost = server.ip // Set strict host
                val theme = if (isDarkMode()) "dark" else "light"
                loadUrl("http://${server.ip}:${server.port}?theme=$theme")
            }
        }
        
        rvServers.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        rvServers.adapter = serverAdapter
        
        // Observe with lifecycle-aware collection
        lifecycleScope.launch {
            this@ClientActivity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                discoveryManager.discoveredServers.collect { list ->
                    serverAdapter.submitList(list)
                }
            }
        }
    }
    
    private fun showPasswordDialog(server: com.thiyagu.media_server.server.DiscoveredServer) {
        val input = com.google.android.material.textfield.TextInputEditText(this)
        input.hint = getString(R.string.hint_password)
        val layout = LinearLayout(this)
        layout.setPadding(50, 20, 50, 20)
        layout.addView(input)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_password_title))
            .setMessage(getString(R.string.dialog_password_message, server.name))
            .setView(layout)
            .setPositiveButton(getString(R.string.action_connect)) { _, _ ->
                 val password = input.text.toString()
                 // TODO: Verify password with server. 
                 // For now, we assume success if password is "admin"
                 if (password == "admin") {
                     etIpAddress.setText(server.ip)
                     currentHost = server.ip // Set strict host
                     val theme = if (isDarkMode()) "dark" else "light"
                     loadUrl("http://${server.ip}:${server.port}?theme=$theme")
                 } else {
                     Toast.makeText(this, getString(R.string.error_incorrect_password), Toast.LENGTH_SHORT).show()
                 }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        discoveryManager.startDiscovery()
        // Ensure overlay is hidden if we are in connection mode
        if (webView.visibility != View.VISIBLE) {
            loadingOverlay.visibility = View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        discoveryManager.stopDiscovery()
    }

    private fun resetWebViewState() {
        // Comprehensive WebView reset for clean reconnections
        webView.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            clearCache(true)
            clearFormData()
            
            // Clear all WebView storage
            clearSslPreferences()
            clearMatches()
            
            // Clear cookies
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            
            // Clear all storage types (requires API 21+)
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
        
        // Reset connection state
        currentHost = null
        
        // Ensure UI is in correct state
        webView.visibility = View.GONE
        connectionContainer.visibility = View.VISIBLE
        loadingOverlay.visibility = View.GONE
    }
    
    private fun startConnectionTimeout() {
        cancelConnectionTimeout()
        
        connectionTimeoutRunnable = Runnable {
            // Timeout fired - connection hung
            webView.stopLoading()
            loadingOverlay.visibility = View.GONE
            
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Connection Timeout")
                .setMessage("Unable to connect to server. The server may be:\n\n• Still scanning your video library\n• Not running or unreachable\n• On a different Wi-Fi network\n\nTip: Large video libraries may take time to scan on first connection.")
                .setPositiveButton("Retry") { _, _ ->
                    val ipAddress = etIpAddress.text.toString().trim()
                    if (ipAddress.isNotEmpty()) {
                        currentHost = ipAddress
                        val theme = if (isDarkMode()) "dark" else "light"
                        loadUrl("http://$ipAddress:8888?theme=$theme")
                    }
                }
                .setNegativeButton("Cancel") { _, _ ->
                    resetWebViewState()
                }
                .show()
        }
        
        connectionTimeoutHandler.postDelayed(connectionTimeoutRunnable!!, CONNECTION_TIMEOUT_MS)
    }
    
    private fun cancelConnectionTimeout() {
        connectionTimeoutRunnable?.let {
            connectionTimeoutHandler.removeCallbacks(it)
            connectionTimeoutRunnable = null
        }
    }

    private fun loadUrl(url: String) {
        // Clear cache before new connection to ensure fresh start
        webView.clearCache(true)
        
        loadingOverlay.visibility = View.VISIBLE
        connectionContainer.visibility = View.GONE
        
        // Start timeout timer
        startConnectionTimeout()
        
        webView.loadUrl(url)
    }


    
    override fun onDestroy() {
        super.onDestroy()
        
        // Cancel any pending timeout
        cancelConnectionTimeout()
        
        // Proper WebView cleanup to prevent memory leaks
        webView.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            clearCache(true)
            clearFormData()
            onPause()
            removeAllViews()
            destroy()
        }
    }
}
