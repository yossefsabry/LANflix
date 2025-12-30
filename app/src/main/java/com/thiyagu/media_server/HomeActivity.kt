package com.thiyagu.media_server

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val tvUsername = findViewById<TextView>(R.id.tv_username)
        val btnProfile = findViewById<MaterialCardView>(R.id.btn_profile)
        val cardHost = findViewById<MaterialCardView>(R.id.card_host)
        val cardClient = findViewById<MaterialCardView>(R.id.card_client)

        // Load Username
        val sharedPref = getSharedPreferences("LANflixPrefs", Context.MODE_PRIVATE)
        val username = sharedPref.getString("username", "User")
        tvUsername.text = username

        // Navigation
        cardHost.setOnClickListener {
            startActivity(Intent(this, StreamingActivity::class.java))
        }

        cardClient.setOnClickListener {
             startActivity(Intent(this, ClientActivity::class.java))
        }
        
        btnProfile.setOnClickListener {
            // TODO: Navigate to ProfileActivity
            startActivity(Intent(this, ProfileActivity::class.java))
        }
    }
}
