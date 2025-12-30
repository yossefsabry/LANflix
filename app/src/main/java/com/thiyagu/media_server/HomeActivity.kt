package com.thiyagu.media_server

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.thiyagu.media_server.databinding.ActivityHomeBinding
import com.thiyagu.media_server.viewmodel.HomeViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by viewModel()

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
    }
}
