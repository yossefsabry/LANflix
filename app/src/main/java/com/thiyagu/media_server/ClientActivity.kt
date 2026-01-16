package com.thiyagu.media_server

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.content.ClipData
import android.content.ClipboardManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClientActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var connectionContainer: LinearLayout
    private lateinit var loadingOverlay: android.widget.FrameLayout
    private lateinit var loadingText: TextView
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
    private val clientPrefs by lazy { getSharedPreferences("lanflix_client_prefs", MODE_PRIVATE) }
    private val PIN_PREF_PREFIX = "server_pin_"
    private val CLIENT_ID_PREF = "client_id"
    private val LAST_SERVER_IP = "last_server_ip"
    private val LAST_SERVER_PORT = "last_server_port"
    private val DEFAULT_PORT = 8888
    private var lastPort: Int = DEFAULT_PORT

    private data class PingResult(
        val ok: Boolean,
        val ready: Boolean,
        val authRequired: Boolean,
        val authorized: Boolean,
        val statusCode: Int,
        val errorMessage: String? = null
    )

    private enum class ConnectionPhase {
        IDLE,
        PINGING,
        AUTH_REQUIRED,
        LOADING,
        CONNECTED,
        ERROR
    }

    private enum class ConnectReason {
        USER,
        AUTO,
        RETRY
    }

    private var phase: ConnectionPhase = ConnectionPhase.IDLE
    private var autoReconnectAttempted = false
    private var retryCount = 0
    private val maxRetry = 3
    private val baseRetryMs = 1000L
    private var lastErrorMessage: String? = null
    private val eventLog = ArrayDeque<String>(50)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)

        etIpAddress = findViewById(R.id.et_ip_address)
        val btnConnect = findViewById<MaterialButton>(R.id.btn_connect)
        val btnDiagnostics = findViewById<MaterialButton>(R.id.btn_diagnostics)
        webView = findViewById(R.id.webview)
        connectionContainer = findViewById(R.id.connection_container)
        loadingOverlay = findViewById(R.id.loading_overlay)
        loadingText = findViewById(R.id.loading_text)
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

        btnDiagnostics.setOnClickListener {
            showDiagnosticsDialog()
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
                val uri = request?.url ?: return false
                val url = uri.toString()
                val path = uri.path ?: ""
                val isVideo = path.endsWith(".mp4", true)
                    || path.endsWith(".mkv", true)
                    || path.endsWith(".avi", true)
                    || path.endsWith(".mov", true)
                    || path.endsWith(".webm", true)
                
                if (url.endsWith("/exit") || url == "http://exit/") {
                    // Handle Exit: Full reset to connection screen
                    resetWebViewState()
                    return true
                }
                
                // Allow internal app navigation BEFORE security check
                if (isVideo) {
                    // Handle Video: Pre-check existence then Launch Native Player
                    val filename = uri.lastPathSegment
                    if (filename.isNullOrEmpty()) return true
                    // Extract path query param if tree mode
                    val pathParam = uri.getQueryParameter("path")
                    val pinParam = uri.getQueryParameter("pin")
                    val queryParts = mutableListOf<String>()
                    if (pathParam != null) {
                        queryParts.add("path=${URLEncoder.encode(pathParam, "UTF-8")}")
                    }
                    if (!pinParam.isNullOrEmpty()) {
                        queryParts.add("pin=${URLEncoder.encode(pinParam, "UTF-8")}")
                    }
                    val query = if (queryParts.isNotEmpty()) "?${queryParts.joinToString("&")}" else ""
                    
                    // Show Loading
                    loadingOverlay.visibility = View.VISIBLE
                    
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val uri = android.net.Uri.parse(url)
                            val host = uri.host
                            val port = if (uri.port != -1) uri.port else DEFAULT_PORT
                            val checkUrl = "http://${host}:$port/api/exists/$filename$query"
                            val connection = java.net.URL(checkUrl).openConnection() as java.net.HttpURLConnection
                            connection.requestMethod = "GET"
                            connection.connectTimeout = 5000
                            connection.readTimeout = 5000
                            connection.setRequestProperty("X-Lanflix-Client", getClientId())
                            if (!pinParam.isNullOrEmpty()) {
                                connection.setRequestProperty("X-Lanflix-Pin", pinParam)
                            }
                            
                            val responseCode = connection.responseCode
                            
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                loadingOverlay.visibility = View.GONE
                                
                                    if (responseCode == 200) {
                                        // File Exists! Launch Player
                                        val intent = android.content.Intent(this@ClientActivity, VideoPlayerActivity::class.java)
                                        intent.putExtra("VIDEO_URL", url)
                                        intent.putExtra("CLIENT_ID", getClientId())
                                        if (!pinParam.isNullOrEmpty()) {
                                            intent.putExtra("PIN", pinParam)
                                        }
                                        startActivity(intent)
                                } else {
                                    // File Not Found
                                    androidx.appcompat.app.AlertDialog.Builder(this@ClientActivity)
                                        .setTitle("Video Not Found")
                                        .setMessage("The video file could not be found on the server. It might have been moved, deleted, or the server is still scanning.")
                                        .setPositiveButton("OK", null)
                                        .show()
                                }
                            }
                        } catch (e: Exception) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                loadingOverlay.visibility = View.GONE
                                Toast.makeText(this@ClientActivity, "Error calling server: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
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
                    setPhase(ConnectionPhase.CONNECTED, "Connected")
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
                    setPhase(ConnectionPhase.CONNECTED, "Connected")
                    webView.visibility = View.VISIBLE
                    connectionContainer.visibility = View.GONE
                }
            }
            
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                cancelConnectionTimeout()
                loadingOverlay.visibility = View.GONE
                connectionContainer.visibility = View.VISIBLE
                setPhase(ConnectionPhase.ERROR, "WebView error: $errorCode")
                
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
                        val url = failingUrl ?: return@setPositiveButton
                        val uri = android.net.Uri.parse(url)
                        val host = uri.host ?: return@setPositiveButton
                        val port = if (uri.port != -1) uri.port else lastPort
                        attemptConnect(host, port, ConnectReason.RETRY, true)
                    }
                    .setNegativeButton(this@ClientActivity.getString(R.string.action_cancel), null)
                    .show()
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame != true) return

                cancelConnectionTimeout()
                loadingOverlay.visibility = View.GONE
                connectionContainer.visibility = View.VISIBLE

                val statusCode = errorResponse?.statusCode ?: 0
                setPhase(ConnectionPhase.ERROR, "HTTP error: $statusCode")
                if (statusCode == 401 && currentHost != null) {
                    clearSavedPin(currentHost!!, lastPort)
                }

                val message = if (statusCode == 401) {
                    "Unauthorized. Please enter the server PIN again."
                } else {
                    "Server returned HTTP $statusCode. Please try again."
                }

                androidx.appcompat.app.AlertDialog.Builder(this@ClientActivity)
                    .setTitle("Connection Failed")
                    .setMessage(message)
                    .setPositiveButton("Retry") { _, _ ->
                        val ipAddress = etIpAddress.text.toString().trim()
                        if (ipAddress.isNotEmpty()) {
                            attemptConnect(ipAddress, lastPort)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        btnConnect.setOnClickListener {
            val ipAddress = etIpAddress.text.toString().trim()
            if (ipAddress.isNotEmpty()) {
                // Dismiss keyboard
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(etIpAddress.windowToken, 0)
                attemptConnect(ipAddress, DEFAULT_PORT)
            }
        }
        
        // --- Server Discovery Setup ---
        setupDiscovery()

        setPhase(ConnectionPhase.IDLE, "Ready")
        
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
            etIpAddress.setText(server.ip)
            attemptConnect(server.ip, server.port)
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

    private fun attemptConnect(ip: String, port: Int, reason: ConnectReason = ConnectReason.USER, forceFresh: Boolean = false) {
        connectionContainer.visibility = View.GONE
        lastPort = port
        if (reason == ConnectReason.USER) {
            retryCount = 0
        }
        logEvent("Connect attempt to $ip:$port (${reason.name})")
        setPhase(ConnectionPhase.PINGING, "Checking server...")

        if (forceFresh) {
            clearWebViewCacheForRetry()
        }

        lifecycleScope.launch {
            val savedPin = getSavedPin(ip, port)
            val pinToUse = savedPin?.takeIf { it.isNotBlank() }
            val ping = pingWithRetry(ip, port, pinToUse)

            if (ping.authRequired && !ping.authorized) {
                if (!savedPin.isNullOrEmpty()) {
                    clearSavedPin(ip, port)
                }
                setPhase(ConnectionPhase.AUTH_REQUIRED, "PIN required")
                showPinDialog(ip, port)
                return@launch
            }

            if (ping.statusCode == 503 || !ping.ready) {
                val message = "Server starting, retrying..."
                setPhase(ConnectionPhase.PINGING, message)
                scheduleRetry(ip, port, reason, forceFresh, message)
                return@launch
            }

            if (!ping.ok) {
                val message = ping.errorMessage ?: "Unable to reach server."
                setPhase(ConnectionPhase.ERROR, message)
                if (reason != ConnectReason.AUTO) {
                    showConnectionError(message)
                }
                return@launch
            }

            retryCount = 0
            saveLastServer(ip, port)
            currentHost = ip
            setPhase(ConnectionPhase.LOADING, "Connecting...")
            val url = buildServerUrl(ip, port, pinToUse, forceFresh)
            loadUrl(url)
        }
    }

    private fun showPinDialog(ip: String, port: Int) {
        logEvent("PIN required for $ip:$port")
        val input = TextInputEditText(this).apply {
            hint = "Enter PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(8))
        }
        val layout = LinearLayout(this).apply {
            setPadding(50, 20, 50, 20)
            addView(input)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Server PIN Required")
            .setMessage("Enter the PIN to connect.")
            .setView(layout)
            .setPositiveButton("Connect") { _, _ ->
                val pin = input.text?.toString()?.trim().orEmpty()
                if (pin.isEmpty()) {
                    Toast.makeText(this, "PIN required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                logEvent("PIN entered")
                verifyPinAndConnect(ip, port, pin)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun verifyPinAndConnect(ip: String, port: Int, pin: String) {
        setPhase(ConnectionPhase.PINGING, "Verifying PIN...")
        lifecycleScope.launch {
            val ping = pingWithRetry(ip, port, pin)
            if (!ping.ok) {
                val message = ping.errorMessage ?: "Unable to reach server."
                setPhase(ConnectionPhase.ERROR, message)
                showConnectionError(message)
                return@launch
            }
            if (!ping.authorized) {
                setPhase(ConnectionPhase.ERROR, "Incorrect PIN.")
                showConnectionError("Incorrect PIN.")
                return@launch
            }

            savePin(ip, port, pin)
            currentHost = ip
            setPhase(ConnectionPhase.LOADING, "Connecting...")
            val url = buildServerUrl(ip, port, pin, false)
            loadUrl(url)
        }
    }

    private suspend fun pingWithRetry(ip: String, port: Int, pin: String?): PingResult {
        var last = PingResult(false, false, false, false, 0, null)
        repeat(maxRetry) { attempt ->
            last = pingServer(ip, port, pin)
            if ((last.ok && last.ready) || (last.authRequired && !last.authorized)) {
                return last
            }

            val backoff = baseRetryMs * (1L shl attempt).coerceAtMost(8000L / baseRetryMs)
            delay(backoff)
        }
        return last
    }

    private suspend fun pingServer(ip: String, port: Int, pin: String?): PingResult = withContext(Dispatchers.IO) {
        try {
            val clientId = getClientId()
            val url = URL("http://$ip:$port/api/ping?client=${URLEncoder.encode(clientId, "UTF-8")}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                if (!pin.isNullOrEmpty()) {
                    setRequestProperty("X-Lanflix-Pin", pin)
                }
                setRequestProperty("X-Lanflix-Client", clientId)
            }

            val code = conn.responseCode
            val body = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText()
            } catch (_: Exception) {
                null
            } ?: ""

            val json = runCatching { JSONObject(body) }.getOrNull()
            val authRequired = json?.optBoolean("authRequired", false) ?: false
            val authorized = json?.optBoolean("authorized", !authRequired) ?: (!authRequired)
            val ready = json?.optBoolean("ready", code == 200) ?: (code == 200)
            val ok = code == 200 || code == 401 || code == 503

            PingResult(
                ok = ok,
                ready = ready,
                authRequired = authRequired,
                authorized = authorized,
                statusCode = code,
                errorMessage = if (ok) null else json?.optString("error")
            )
        } catch (e: Exception) {
            PingResult(false, false, false, false, 0, e.message)
        }
    }

    private fun showConnectionError(message: String) {
        logEvent("Connection error: $message")
        loadingOverlay.visibility = View.GONE
        connectionContainer.visibility = View.VISIBLE
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Connection Failed")
            .setMessage(message)
            .setPositiveButton("Retry") { _, _ ->
                val ipAddress = etIpAddress.text.toString().trim()
                if (ipAddress.isNotEmpty()) {
                    attemptConnect(ipAddress, lastPort, ConnectReason.RETRY, true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scheduleRetry(
        ip: String,
        port: Int,
        reason: ConnectReason,
        forceFresh: Boolean,
        message: String
    ) {
        if (reason == ConnectReason.USER && retryCount >= maxRetry) {
            setPhase(ConnectionPhase.ERROR, message)
            showConnectionError(message)
            return
        }
        if (retryCount >= maxRetry) {
            setPhase(ConnectionPhase.ERROR, message)
            return
        }

        val delayMs = baseRetryMs * (1L shl retryCount).coerceAtMost(8000L / baseRetryMs)
        retryCount += 1
        logEvent("Retry #$retryCount in ${delayMs}ms")
        connectionTimeoutHandler.postDelayed({
            attemptConnect(ip, port, reason, forceFresh)
        }, delayMs)
    }

    private fun getClientId(): String {
        val existing = clientPrefs.getString(CLIENT_ID_PREF, null)
        if (!existing.isNullOrBlank()) return existing
        val newId = UUID.randomUUID().toString()
        clientPrefs.edit().putString(CLIENT_ID_PREF, newId).apply()
        return newId
    }

    private fun buildServerUrl(ip: String, port: Int, pin: String?, forceFresh: Boolean): String {
        val theme = if (isDarkMode()) "dark" else "light"
        val params = mutableListOf("theme=$theme")
        if (!pin.isNullOrEmpty()) {
            params.add("pin=${URLEncoder.encode(pin, "UTF-8")}")
        }
        params.add("client=${URLEncoder.encode(getClientId(), "UTF-8")}")
        if (forceFresh) {
            params.add("nocache=${System.currentTimeMillis()}")
        }
        return "http://$ip:$port?${params.joinToString("&")}"
    }

    private fun saveLastServer(ip: String, port: Int) {
        clientPrefs.edit()
            .putString(LAST_SERVER_IP, ip)
            .putInt(LAST_SERVER_PORT, port)
            .apply()
    }

    private fun getLastServer(): Pair<String, Int>? {
        val ip = clientPrefs.getString(LAST_SERVER_IP, null) ?: return null
        val port = clientPrefs.getInt(LAST_SERVER_PORT, DEFAULT_PORT)
        return ip to port
    }

    private fun clearWebViewCacheForRetry() {
        webView.stopLoading()
        webView.clearHistory()
        webView.clearCache(true)
    }

    private fun getSavedPin(ip: String, port: Int): String? {
        return clientPrefs.getString("$PIN_PREF_PREFIX$ip:$port", null)
    }

    private fun savePin(ip: String, port: Int, pin: String) {
        clientPrefs.edit().putString("$PIN_PREF_PREFIX$ip:$port", pin).apply()
    }

    private fun clearSavedPin(ip: String, port: Int) {
        clientPrefs.edit().remove("$PIN_PREF_PREFIX$ip:$port").apply()
    }

    private fun setPhase(newPhase: ConnectionPhase, message: String? = null) {
        phase = newPhase
        message?.let { lastErrorMessage = it }
        when (newPhase) {
            ConnectionPhase.IDLE -> {
                loadingOverlay.visibility = View.GONE
                loadingText.text = "Loading..."
                if (webView.visibility != View.VISIBLE) {
                    connectionContainer.visibility = View.VISIBLE
                }
            }
            ConnectionPhase.PINGING -> {
                loadingOverlay.visibility = View.VISIBLE
                loadingText.text = message ?: "Checking server..."
            }
            ConnectionPhase.AUTH_REQUIRED -> {
                loadingOverlay.visibility = View.GONE
                loadingText.text = "Loading..."
            }
            ConnectionPhase.LOADING -> {
                loadingOverlay.visibility = View.VISIBLE
                loadingText.text = message ?: "Connecting..."
            }
            ConnectionPhase.CONNECTED -> {
                loadingOverlay.visibility = View.GONE
                loadingText.text = "Loading..."
            }
            ConnectionPhase.ERROR -> {
                loadingOverlay.visibility = View.GONE
                loadingText.text = "Loading..."
                if (webView.visibility != View.VISIBLE) {
                    connectionContainer.visibility = View.VISIBLE
                }
            }
        }
        logEvent("Phase: $newPhase${message?.let { " - $it" } ?: ""}")
    }

    private fun logEvent(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        if (eventLog.size >= 50) {
            eventLog.removeFirst()
        }
        eventLog.addLast("$timestamp $message")
    }

    private fun showDiagnosticsDialog() {
        val logText = buildString {
            appendLine("Phase: $phase")
            lastErrorMessage?.let { appendLine("LastError: $it") }
            appendLine("ClientId: ${getClientId()}")
            appendLine("LastServer: ${clientPrefs.getString(LAST_SERVER_IP, "-")}:${clientPrefs.getInt(LAST_SERVER_PORT, -1)}")
            appendLine("---")
            eventLog.forEach { appendLine(it) }
        }.trim()

        val content = TextView(this).apply {
            text = if (logText.isBlank()) "No diagnostics yet." else logText
            setTextColor(ContextCompat.getColor(this@ClientActivity, R.color.lanflix_text_main))
            setPadding(40, 20, 40, 20)
            setTextIsSelectable(true)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Diagnostics")
            .setView(content)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("LANflix Diagnostics", content.text))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        discoveryManager.startDiscovery()
        // Ensure overlay is hidden if we are in connection mode
        if (webView.visibility != View.VISIBLE) {
            loadingOverlay.visibility = View.GONE
        }

        if (!autoReconnectAttempted && webView.visibility != View.VISIBLE) {
            val last = getLastServer()
            if (last != null) {
                val (ip, port) = last
                logEvent("Auto-reconnect to $ip:$port")
                autoReconnectAttempted = true
                attemptConnect(ip, port, ConnectReason.AUTO, false)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        discoveryManager.stopDiscovery()
        if (webView.visibility != View.VISIBLE) {
            autoReconnectAttempted = false
        }
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
        }
        
        // Reset connection state
        currentHost = null
        setPhase(ConnectionPhase.IDLE, "Reset")
        
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
            setPhase(ConnectionPhase.ERROR, "Connection timeout")
            
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Connection Timeout")
                .setMessage("Unable to connect to server. The server may be:\n\n• Still scanning your video library\n• Not running or unreachable\n• On a different Wi-Fi network\n\nTip: Large video libraries may take time to scan on first connection.")
                .setPositiveButton("Retry") { _, _ ->
                    val ipAddress = etIpAddress.text.toString().trim()
                    if (ipAddress.isNotEmpty()) {
                        attemptConnect(ipAddress, lastPort, ConnectReason.RETRY, true)
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
        logEvent("Loading URL: $url")
        
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
