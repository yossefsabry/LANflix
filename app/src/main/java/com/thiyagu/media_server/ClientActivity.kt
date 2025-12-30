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
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class ClientActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var connectionContainer: LinearLayout
    private lateinit var loadingIndicator: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)

        val etIpAddress = findViewById<TextInputEditText>(R.id.et_ip_address)
        val btnConnect = findViewById<MaterialButton>(R.id.btn_connect)
        webView = findViewById(R.id.webview)
        connectionContainer = findViewById(R.id.connection_container)
        loadingIndicator = findViewById(R.id.loading_indicator)
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
        
        // Force dark mode in WebView if supported, or let CSS handle it (CSS is already dark)
        
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false // Keep navigation inside WebView
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loadingIndicator.visibility = View.GONE
                webView.visibility = View.VISIBLE
                connectionContainer.visibility = View.GONE
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
}
