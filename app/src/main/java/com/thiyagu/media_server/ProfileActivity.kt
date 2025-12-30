package com.thiyagu.media_server

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.lang.Exception

class ProfileActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            setContentView(R.layout.activity_profile)

            val tvUsername = findViewById<TextView>(R.id.profile_username)
            val btnBack = findViewById<View>(R.id.btn_back)
            val btnLogout = findViewById<TextView>(R.id.btn_logout) 
            val btnDisconnect = findViewById<MaterialButton>(R.id.btn_disconnect)
            val btnEdit = findViewById<View>(R.id.btn_edit_username)

            val sharedPref = getSharedPreferences("LANflixPrefs", Context.MODE_PRIVATE)
            if (tvUsername != null) {
                // In a real app we'd get this from the pref, defaulting for now
                tvUsername.text = sharedPref.getString("username", "localUser_99")
            }

            btnBack?.setOnClickListener { finish() }

            btnLogout?.setOnClickListener { logout() }
            
            btnDisconnect?.setOnClickListener {
                Toast.makeText(this, "Disconnected from Local Network", Toast.LENGTH_SHORT).show()
            }
            
            btnEdit?.setOnClickListener {
                 Toast.makeText(this, "Edit Username feature coming soon", Toast.LENGTH_SHORT).show()
            }
            
            setupOption(R.id.opt_settings, R.drawable.ic_settings, "App Settings", "Playback, Theme, Notifications")
            setupOption(R.id.opt_privacy, R.drawable.ic_shield, "Privacy & Security", "History, Cache, Permissions")
            setupOption(R.id.opt_network, R.drawable.ic_wifi, "Network Info", "192.168.1.45 • Port 8080")
            setupOption(R.id.opt_downloads, R.drawable.ic_download, "Local Downloads", "3.2 GB Used")
            
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error loading profile: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun setupOption(viewId: Int, iconRes: Int, title: String, subtitle: String) {
        val view = findViewById<View>(viewId)
        if (view == null) return
        
        val iconView = view.findViewById<ImageView>(R.id.icon)
        val titleView = view.findViewById<TextView>(R.id.title)
        val subtitleView = view.findViewById<TextView>(R.id.subtitle)
        
        iconView?.setImageResource(iconRes)
        titleView?.text = title
        subtitleView?.text = subtitle
        
        view.setOnClickListener {
            Toast.makeText(this, "Opening $title...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun logout() {
        val sharedPref = getSharedPreferences("LANflixPrefs", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            clear()
            apply()
        }
        
        // Navigate to WelcomeActivity (simulated) or just finish for now if it doesn't exist yet
        try {
            val intent = Intent(this, Class.forName("com.thiyagu.media_server.WelcomeActivity"))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        } catch (e: ClassNotFoundException) {
            Toast.makeText(this, "Logged out (Welcome screen not found)", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            finish()
        }
    }
}
