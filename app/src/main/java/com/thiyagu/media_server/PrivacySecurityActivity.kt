package com.thiyagu.media_server

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class PrivacySecurityActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy_security)

        val btnBack = findViewById<View>(R.id.btn_back)
        btnBack?.setOnClickListener { finish() }

        setupOption(R.id.opt_clear_history, R.drawable.ic_settings, "Clear Watch History", "Remove all viewing history") {
            showClearHistoryDialog()
        }
        
        setupOption(R.id.opt_clear_cache, R.drawable.ic_settings, "Clear Cache", "524 MB stored") {
            showClearCacheDialog()
        }
        
        setupOption(R.id.opt_permissions, R.drawable.ic_shield, "App Permissions", "Storage, Network") {
            Toast.makeText(this, "Opening app permissions...", Toast.LENGTH_SHORT).show()
        }
        
        setupOption(R.id.opt_privacy_policy, R.drawable.ic_shield, "Privacy Policy", "View our privacy policy") {
            Toast.makeText(this, "Opening privacy policy...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showClearHistoryDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear Watch History")
            .setMessage("Are you sure you want to clear all your watch history? This action cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                Toast.makeText(this, "Watch history cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClearCacheDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear Cache")
            .setMessage("This will clear 524 MB of cached data. The app may run slower until the cache is rebuilt.")
            .setPositiveButton("Clear") { _, _ ->
                Toast.makeText(this, "Cache cleared successfully", Toast.LENGTH_SHORT).show()
                // Update the subtitle to show 0 MB
                val view = findViewById<View>(R.id.opt_clear_cache)
                val subtitleView = view.findViewById<TextView>(R.id.subtitle)
                subtitleView?.text = "0 MB stored"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupOption(viewId: Int, iconRes: Int, title: String, subtitle: String, onClick: () -> Unit) {
        val view = findViewById<View>(viewId) ?: return
        
        val iconView = view.findViewById<ImageView>(R.id.icon)
        val titleView = view.findViewById<TextView>(R.id.title)
        val subtitleView = view.findViewById<TextView>(R.id.subtitle)
        
        iconView?.setImageResource(iconRes)
        titleView?.text = title
        subtitleView?.text = subtitle
        
        view.setOnClickListener { onClick() }
    }
}
