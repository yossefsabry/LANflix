package com.thiyagu.media_server

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.thiyagu.media_server.client.ClientConnectionController
import com.thiyagu.media_server.client.ClientDiagnosticsController
import com.thiyagu.media_server.client.ClientWebViewController
import com.thiyagu.media_server.client.ConnectReason
import com.thiyagu.media_server.client.ConnectionPhase
import com.thiyagu.media_server.client.PingResult
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

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
    private var connectJob: Job? = null
    private var retryRunnable: Runnable? = null
    private var isLaunchingPlayer = false
    private var lastDisconnectAtMs = 0L
    private val DISCONNECT_DEBOUNCE_MS = 1000L

    private var phase: ConnectionPhase = ConnectionPhase.IDLE
    private var autoReconnectAttempted = false
    private var retryCount = 0
    private val maxRetry = 3
    private val baseRetryMs = 1000L
    private var lastErrorMessage: String? = null
    private lateinit var diagnosticsController: ClientDiagnosticsController
    private lateinit var connectionController: ClientConnectionController
    private lateinit var webViewController: ClientWebViewController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)

        etIpAddress = findViewById(R.id.et_ip_address)
        val btnConnect = findViewById<MaterialButton>(R.id.btn_connect)
        val btnDiagnostics = findViewById<MaterialButton>(R.id.btn_diagnostics)
        val btnDisconnect = findViewById<MaterialButton>(R.id.btn_disconnect)
        webView = findViewById(R.id.webview)
        connectionContainer = findViewById(R.id.connection_container)
        loadingOverlay = findViewById(R.id.loading_overlay)
        loadingText = findViewById(R.id.loading_text)
        rvServers = findViewById(R.id.rv_servers)

        diagnosticsController = ClientDiagnosticsController(
            context = this,
            prefs = clientPrefs,
            lastServerIpKey = LAST_SERVER_IP,
            lastServerPortKey = LAST_SERVER_PORT,
            phaseProvider = { phase },
            lastErrorProvider = { lastErrorMessage },
            clientIdProvider = { getClientId() }
        )
        connectionController = ClientConnectionController(
            prefs = clientPrefs,
            defaultPort = DEFAULT_PORT,
            pinPrefPrefix = PIN_PREF_PREFIX,
            clientIdPref = CLIENT_ID_PREF,
            lastServerIpKey = LAST_SERVER_IP,
            lastServerPortKey = LAST_SERVER_PORT
        )
        webViewController = ClientWebViewController(
            activity = this,
            scope = lifecycleScope,
            webView = webView,
            loadingOverlay = loadingOverlay,
            connectionContainer = connectionContainer,
            getCurrentHost = { currentHost },
            getLastPort = { lastPort },
            defaultPort = DEFAULT_PORT,
            getClientId = { getClientId() },
            setLaunchingPlayer = { isLaunchingPlayer = it },
            setPhase = { phase, message -> setPhase(phase, message) },
            cancelConnectionTimeout = { cancelConnectionTimeout() },
            attemptConnect = { ip, port, reason, forceFresh -> attemptConnect(ip, port, reason, forceFresh) },
            disconnect = { clearDefault, reason -> disconnectFromServer(clearDefault, reason) },
            clearSavedPin = { host, port -> clearSavedPin(host, port) }
        )
        
        val btnBack = findViewById<android.view.View>(R.id.btn_back_home)
        
        btnBack.setOnClickListener {
            if (webView.visibility == android.view.View.VISIBLE) {
                // Return to input mode with full reset
                disconnectFromServer(clearDefault = true, reason = "back")
            } else {
                // Return to Home
                finish()
            }
        }

        btnDisconnect.setOnClickListener {
            disconnectFromServer(clearDefault = true, reason = "user_disconnect")
        }

        btnDiagnostics.setOnClickListener {
            showDiagnosticsDialog()
        }

        webViewController.configure()

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
                    disconnectFromServer(clearDefault = true, reason = "back_root")
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
        cancelPendingConnect()
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

        connectJob = lifecycleScope.launch {
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
        return connectionController.pingWithRetry(ip, port, pin, maxRetry, baseRetryMs)
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
        retryRunnable?.let { connectionTimeoutHandler.removeCallbacks(it) }
        retryRunnable = Runnable { attemptConnect(ip, port, reason, forceFresh) }
        connectionTimeoutHandler.postDelayed(retryRunnable!!, delayMs)
    }

    private fun getClientId(): String {
        return connectionController.getClientId()
    }

    private fun buildServerUrl(ip: String, port: Int, pin: String?, forceFresh: Boolean): String {
        val theme = if (isDarkMode()) "dark" else "light"
        return connectionController.buildServerUrl(ip, port, pin, forceFresh, theme)
    }

    private fun saveLastServer(ip: String, port: Int) {
        connectionController.saveLastServer(ip, port)
    }

    private fun clearLastServer() {
        connectionController.clearLastServer()
    }

    private fun getLastServer(): Pair<String, Int>? {
        return connectionController.getLastServer()
    }

    private fun clearWebViewCacheForRetry() {
        webView.stopLoading()
        webView.clearHistory()
        webView.clearCache(true)
    }

    private fun getSavedPin(ip: String, port: Int): String? {
        return connectionController.getSavedPin(ip, port)
    }

    private fun savePin(ip: String, port: Int, pin: String) {
        connectionController.savePin(ip, port, pin)
    }

    private fun clearSavedPin(ip: String, port: Int) {
        connectionController.clearSavedPin(ip, port)
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

    private fun cancelPendingConnect() {
        connectJob?.cancel()
        connectJob = null
        retryRunnable?.let { connectionTimeoutHandler.removeCallbacks(it) }
        retryRunnable = null
        cancelConnectionTimeout()
    }

    private fun disconnectFromServer(clearDefault: Boolean, reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDisconnectAtMs < DISCONNECT_DEBOUNCE_MS) return
        lastDisconnectAtMs = now
        logEvent("Disconnect: $reason")
        cancelPendingConnect()
        val host = currentHost
        val port = lastPort
        val pin = host?.let { getSavedPin(it, port) }
        if (!host.isNullOrBlank()) {
            sendDisconnectSignal(host, port, pin)
        }
        if (clearDefault) {
            clearLastServer()
        }
        autoReconnectAttempted = false
        resetWebViewState()
    }

    private fun sendDisconnectSignal(host: String, port: Int, pin: String?) {
        connectionController.sendDisconnectSignal(host, port, pin)
    }

    private fun logEvent(message: String) {
        diagnosticsController.logEvent(message)
    }

    private fun showDiagnosticsDialog() {
        diagnosticsController.showDiagnosticsDialog()
    }

    override fun onResume() {
        super.onResume()
        isLaunchingPlayer = false
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

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        if (isLaunchingPlayer) return

        val shouldDisconnect = webView.visibility == View.VISIBLE
            || phase == ConnectionPhase.PINGING
            || phase == ConnectionPhase.LOADING

        if (shouldDisconnect) {
            disconnectFromServer(clearDefault = true, reason = "stop")
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
                    disconnectFromServer(clearDefault = true, reason = "timeout_cancel")
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
        if (isFinishing && !currentHost.isNullOrBlank()) {
            val pin = currentHost?.let { getSavedPin(it, lastPort) }
            sendDisconnectSignal(currentHost!!, lastPort, pin)
        }

        // Cancel any pending timeout/connection
        cancelPendingConnect()
        
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

        super.onDestroy()
    }
}
