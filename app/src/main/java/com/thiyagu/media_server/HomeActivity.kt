package com.thiyagu.media_server

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.thiyagu.media_server.data.UserPreferences
import com.thiyagu.media_server.databinding.ActivityHomeBinding
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var userPreferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        userPreferences = UserPreferences(this)

        // Reactive Username Loading
        lifecycleScope.launch {
            userPreferences.usernameFlow.collect { username ->
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
    }
}
