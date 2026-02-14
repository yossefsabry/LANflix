package com.thiyagu.media_server.client

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.thiyagu.media_server.AppSettingsActivity
import com.thiyagu.media_server.ClientActivity
import com.thiyagu.media_server.ProfileActivity
import com.thiyagu.media_server.R
import com.thiyagu.media_server.VideoPlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal class ClientWebViewController(
    private val activity: ClientActivity,
    private val scope: CoroutineScope,
    private val webView: WebView,
    private val loadingOverlay: FrameLayout,
    private val connectionContainer: LinearLayout,
    private val getCurrentHost: () -> String?,
    private val getLastPort: () -> Int,
    private val defaultPort: Int,
    private val getClientId: () -> String,
    private val setLaunchingPlayer: (Boolean) -> Unit,
    private val setPhase: (ConnectionPhase, String?) -> Unit,
    private val cancelConnectionTimeout: () -> Unit,
    private val attemptConnect: (String, Int, ConnectReason, Boolean) -> Unit,
    private val disconnect: (Boolean, String) -> Unit,
    private val clearSavedPin: (String, Int) -> Unit
) {

    fun configure() {
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
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
                    disconnect(true, "exit")
                    return true
                }

                if (isVideo) {
                    val filename = uri.lastPathSegment
                    if (filename.isNullOrEmpty()) return true
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

                    loadingOverlay.visibility = View.VISIBLE

                    scope.launch(Dispatchers.IO) {
                        try {
                            val urlUri = Uri.parse(url)
                            val host = urlUri.host
                            val port = if (urlUri.port != -1) urlUri.port else defaultPort
                            val checkUrl = "http://${host}:$port/api/exists/$filename$query"
                            val connection = URL(checkUrl).openConnection() as HttpURLConnection
                            connection.requestMethod = "GET"
                            connection.connectTimeout = 5000
                            connection.readTimeout = 5000
                            connection.setRequestProperty("X-Lanflix-Client", getClientId())
                            if (!pinParam.isNullOrEmpty()) {
                                connection.setRequestProperty("X-Lanflix-Pin", pinParam)
                            }

                            val responseCode = connection.responseCode

                            withContext(Dispatchers.Main) {
                                loadingOverlay.visibility = View.GONE

                                if (responseCode == 200) {
                                    setLaunchingPlayer(true)
                                    val intent = android.content.Intent(activity, VideoPlayerActivity::class.java)
                                    intent.putExtra("VIDEO_URL", url)
                                    intent.putExtra("CLIENT_ID", getClientId())
                                    if (!pinParam.isNullOrEmpty()) {
                                        intent.putExtra("PIN", pinParam)
                                    }
                                    activity.startActivity(intent)
                                } else {
                                    AlertDialog.Builder(activity)
                                        .setTitle("Video Not Found")
                                        .setMessage("The video file could not be found on the server. It might have been moved, deleted, or the server is still scanning.")
                                        .setPositiveButton("OK", null)
                                        .show()
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                loadingOverlay.visibility = View.GONE
                                Toast.makeText(activity, "Error calling server: ${'$'}{e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    return true
                }

                if (url.endsWith("/profile") || url == "http://profile/") {
                    try {
                        val intent = android.content.Intent(activity, ProfileActivity::class.java)
                        intent.putExtra("SOURCE_ACTIVITY", "ClientActivity")
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(activity, activity.getString(R.string.error_open_profile), Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                if (url.endsWith("/settings") || url == "http://settings/") {
                    try {
                        val intent = android.content.Intent(activity, AppSettingsActivity::class.java)
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(activity, activity.getString(R.string.error_open_settings), Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                val requestHost = Uri.parse(url).host
                if (getCurrentHost() != null && requestHost != null && requestHost != getCurrentHost()) {
                    disconnect(true, "redirect_blocked")
                    Toast.makeText(activity, "Disconnected: External redirect blocked", Toast.LENGTH_LONG).show()
                    return true
                }

                return false
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                cancelConnectionTimeout()
                loadingOverlay.visibility = View.GONE
                if (url != "about:blank") {
                    setPhase(ConnectionPhase.CONNECTED, "Connected")
                    webView.visibility = View.VISIBLE
                    connectionContainer.visibility = View.GONE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                cancelConnectionTimeout()
                loadingOverlay.visibility = View.GONE
                if (url != "about:blank") {
                    setPhase(ConnectionPhase.CONNECTED, "Connected")
                    webView.visibility = View.VISIBLE
                    connectionContainer.visibility = View.GONE
                }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                cancelConnectionTimeout()
                loadingOverlay.visibility = View.GONE
                connectionContainer.visibility = View.VISIBLE
                setPhase(ConnectionPhase.ERROR, "WebView error: $errorCode")

                val errorMsg = when (errorCode) {
                    WebViewClient.ERROR_HOST_LOOKUP -> activity.getString(R.string.error_host_lookup)
                    WebViewClient.ERROR_CONNECT -> activity.getString(R.string.error_connect)
                    WebViewClient.ERROR_TIMEOUT -> activity.getString(R.string.error_timeout)
                    else -> activity.getString(R.string.error_connection_general, description ?: "Unknown error")
                }

                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.dialog_connection_failed_title))
                    .setMessage(errorMsg)
                    .setPositiveButton(activity.getString(R.string.action_retry)) { _, _ ->
                        val urlToRetry = failingUrl ?: return@setPositiveButton
                        val retryUri = Uri.parse(urlToRetry)
                        val host = retryUri.host ?: return@setPositiveButton
                        val port = if (retryUri.port != -1) retryUri.port else getLastPort()
                        attemptConnect(host, port, ConnectReason.RETRY, true)
                    }
                    .setNegativeButton(activity.getString(R.string.action_cancel), null)
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
                if (statusCode == 401) {
                    val host = getCurrentHost()
                    if (!host.isNullOrEmpty()) {
                        clearSavedPin(host, getLastPort())
                    }
                }

                val message = if (statusCode == 401) {
                    "Unauthorized. Please enter the server PIN again."
                } else {
                    "Server returned HTTP $statusCode. Please try again."
                }

                AlertDialog.Builder(activity)
                    .setTitle("Connection Failed")
                    .setMessage(message)
                    .setPositiveButton("Retry") { _, _ ->
                        val ipAddress = activity.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_ip_address)
                            ?.text?.toString()?.trim().orEmpty()
                        if (ipAddress.isNotEmpty()) {
                            attemptConnect(ipAddress, getLastPort(), ConnectReason.RETRY, true)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
}
