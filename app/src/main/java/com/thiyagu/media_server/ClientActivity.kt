package com.thiyagu.media_server

import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class ClientActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var connectionContainer: LinearLayout
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var etIpAddress: TextInputEditText
    private lateinit var rvServers: androidx.recyclerview.widget.RecyclerView
    private lateinit var discoveryManager: com.thiyagu.media_server.server.ServerDiscoveryManager
    private lateinit var serverAdapter: com.thiyagu.media_server.server.ServerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)

        etIpAddress = findViewById(R.id.et_ip_address)
        val btnConnect = findViewById<MaterialButton>(R.id.btn_connect)
        webView = findViewById(R.id.webview)
        connectionContainer = findViewById(R.id.connection_container)
        loadingIndicator = findViewById(R.id.loading_indicator)
        rvServers = findViewById(R.id.rv_servers)
        
        val btnBack = findViewById<android.view.View>(R.id.btn_back_home)
        
        btnBack.setOnClickListener {
            if (webView.visibility == android.view.View.VISIBLE) {
                // Return to input mode
                webView.visibility = android.view.View.GONE
                webView.loadUrl("about:blank")
                findViewById<android.view.View>(R.id.connection_container).visibility = android.view.View.VISIBLE
            } else {
                // Return to Home
                finish()
            }
        }

        // Configure WebView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                
                if (url.endsWith("/exit") || url == "http://exit/") {
                    // Handle Exit: Stops loading, hides webview, shows connection screen
                    webView.stopLoading()
                    webView.clearHistory()
                    webView.visibility = View.GONE
                    connectionContainer.visibility = View.VISIBLE
                    webView.loadUrl("about:blank")
                    return true
                }
                
                if (url.endsWith("/profile") || url == "http://profile/") {
                    // Handle Profile: Navigate to ProfileActivity
                    try {
                        val intent = android.content.Intent(this@ClientActivity, ProfileActivity::class.java)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@ClientActivity, "Could not open profile", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                
                return false // Keep navigation inside WebView
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Prevent white screen flash by ignoring about:blank
                if (url != "about:blank") {
                    loadingIndicator.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    connectionContainer.visibility = View.GONE
                }
            }
            
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                Toast.makeText(this@ClientActivity, "Error: $description", Toast.LENGTH_SHORT).show()
                loadingIndicator.visibility = View.GONE
                connectionContainer.visibility = View.VISIBLE
            }
        }

        btnConnect.setOnClickListener {
            val ipAddress = etIpAddress.text.toString().trim()
            if (ipAddress.isNotEmpty()) {
                val url = "http://$ipAddress:8888"
                loadUrl(url)
            }
        }
        
        // --- Server Discovery Setup ---
        setupDiscovery()
    }
    
    private fun setupDiscovery() {
        discoveryManager = com.thiyagu.media_server.server.ServerDiscoveryManager(this)
        serverAdapter = com.thiyagu.media_server.server.ServerAdapter { server ->
            // Click Handler
            if (server.isSecured) {
                showPasswordDialog(server)
            } else {
                // Direct Connect
                etIpAddress.setText(server.ip)
                loadUrl("http://${server.ip}:${server.port}")
            }
        }
        
        rvServers.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        rvServers.adapter = serverAdapter
        
        // Observe
        lifecycleScope.launchWhenStarted {
            discoveryManager.discoveredServers.collect { list ->
                serverAdapter.submitList(list)
            }
        }
    }
    
    private fun showPasswordDialog(server: com.thiyagu.media_server.server.DiscoveredServer) {
        val input = com.google.android.material.textfield.TextInputEditText(this)
        input.hint = "Password"
        val layout = LinearLayout(this)
        layout.setPadding(50, 20, 50, 20)
        layout.addView(input)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Password Required")
            .setMessage("Enter password for ${server.name}")
            .setView(layout)
            .setPositiveButton("Connect") { _, _ ->
                 val password = input.text.toString()
                 // TODO: Verify password with server. 
                 // For now, we assume success if password is "admin"
                 if (password == "admin") {
                     etIpAddress.setText(server.ip)
                     loadUrl("http://${server.ip}:${server.port}")
                 } else {
                     Toast.makeText(this, "Incorrect Password", Toast.LENGTH_SHORT).show()
                 }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        discoveryManager.startDiscovery()
    }

    override fun onPause() {
        super.onPause()
        discoveryManager.stopDiscovery()
    }

    private fun loadUrl(url: String) {
        loadingIndicator.visibility = View.VISIBLE
        connectionContainer.visibility = View.GONE
        webView.loadUrl(url)
    }

    override fun onBackPressed() {
        if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
        } else if (webView.visibility == View.VISIBLE) {
            // If at root of WebView, go back to connection screen
            webView.visibility = View.GONE
            connectionContainer.visibility = View.VISIBLE
            webView.stopLoading()
            webView.loadUrl("about:blank")
        } else {
            super.onBackPressed()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
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
