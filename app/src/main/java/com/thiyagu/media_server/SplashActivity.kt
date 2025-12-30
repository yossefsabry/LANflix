package com.thiyagu.media_server

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.view.View
import com.thiyagu.media_server.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        checkNetworkAndProceed()
    }

    private fun checkNetworkAndProceed() {
        if (isNetworkAvailable()) {
            Handler().postDelayed({
                // Check if user is logged in (has username)
                val sharedPref = getSharedPreferences("LANflixPrefs", android.content.Context.MODE_PRIVATE)
                val username = sharedPref.getString("username", null)

                val intent = if (username.isNullOrEmpty()) {
                    Intent(this, WelcomeActivity::class.java)
                } else {
                    Intent(this, HomeActivity::class.java)
                }
                startActivity(intent)
                finish() // Prevent going back to splash
            }, 3000)
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