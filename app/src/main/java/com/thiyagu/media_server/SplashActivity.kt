package com.thiyagu.media_server

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.view.View
import com.thiyagu.media_server.databinding.ActivitySplashBinding

import androidx.lifecycle.lifecycleScope
import com.thiyagu.media_server.data.UserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val userPreferences: UserPreferences by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        checkNetworkAndProceed()
    }

    private fun checkNetworkAndProceed() {
        if (isNetworkAvailable()) {
                // Check if user is logged in using DataStore
                lifecycleScope.launch {
                    val username = userPreferences.usernameFlow.first()
                    
                    val intent = if (username.isEmpty() || username == "User") {
                        Intent(this@SplashActivity, WelcomeActivity::class.java)
                    } else {
                        Intent(this@SplashActivity, HomeActivity::class.java)
                    }
                    startActivity(intent)
                    finish() // Prevent going back to splash
                }
        } else {
            showNoConnectionDialog()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
            return when {
                activeNetwork.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> true
                activeNetwork.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                activeNetwork.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> true
                else -> false
            }
        } else {
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false
            return networkInfo.isConnected
        }
    }

    private fun showNoConnectionDialog() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("No Network Connection")
        builder.setMessage("You must connect to a network (WiFi/Ethernet) to use LANflix.")
        builder.setCancelable(false)
        builder.setPositiveButton("Retry") { _, _ ->
            checkNetworkAndProceed()
        }
        builder.setNegativeButton("Close App") { _, _ ->
            finish()
        }
        builder.show()
    }
}