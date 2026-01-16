package com.thiyagu.media_server

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.thiyagu.media_server.data.UserPreferences
import com.thiyagu.media_server.databinding.ActivityHomeBinding
import com.thiyagu.media_server.viewmodel.HomeViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by viewModel()
    private val userPreferences: UserPreferences by inject()
    
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Reactive Username Loading
        lifecycleScope.launch {
            viewModel.username.collect { username ->
                binding.tvUsername.text = username
            }
        }

        // Navigation
        binding.cardHost.setOnClickListener {
            startActivity(Intent(this, StreamingActivity::class.java))
        }

        binding.cardClient.setOnClickListener {
             startActivity(Intent(this, ClientActivity::class.java))
        }
        
        binding.btnProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        
        maybeRequestNotificationPermissionOnFirstLaunch()
    }
    
    private fun maybeRequestNotificationPermissionOnFirstLaunch() {
        lifecycleScope.launch {
            val prompted = userPreferences.notificationsPromptedFlow.first()
            if (prompted) return@launch

            userPreferences.saveNotificationsPrompted(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    this@HomeActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                
                if (!hasPermission) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}
