package com.thiyagu.media_server

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

import androidx.lifecycle.lifecycleScope
import com.thiyagu.media_server.data.UserPreferences
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class AppSettingsActivity : AppCompatActivity() {

    private val userPreferences: UserPreferences by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_settings)

        val btnBack = findViewById<View>(R.id.btn_back)
        btnBack?.setOnClickListener { finish() }

        // Setup Options
        setupOption(R.id.opt_video_quality, R.drawable.ic_video, "Video Quality", "Auto (Recommended)")
        setupOption(R.id.opt_notifications, R.drawable.ic_settings, "Notifications", "Enabled")
        setupOption(R.id.opt_autoplay, R.drawable.ic_video, "Auto-play Next", "On")
        
        // Theme Option with Logic
        setupOption(R.id.opt_theme, R.drawable.ic_settings, "Theme", "System Default") {
            showThemeSelectionDialog()
        }
        
        // Observe Theme changes to update Subtitle
        lifecycleScope.launch {
            userPreferences.themeFlow.collect { theme ->
                val title = when(theme) {
                    "light" -> "Light"
                    "dark" -> "Dark"
                    else -> "System Default"
                }
                updateOptionSubtitle(R.id.opt_theme, title)
            }
        }
    }
    
    private fun showThemeSelectionDialog() {
        val themes = arrayOf("System Default", "Light", "Dark")
        val values = arrayOf("system", "light", "dark")
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Choose Theme")
            .setItems(themes) { _, which ->
                val selectedTheme = values[which]
                lifecycleScope.launch {
                    userPreferences.saveTheme(selectedTheme)
                }
            }
            .show()
    }
    
    private fun updateOptionSubtitle(viewId: Int, subtitle: String) {
        val view = findViewById<View>(viewId) ?: return
        val subtitleView = view.findViewById<TextView>(R.id.subtitle)
        subtitleView?.text = subtitle
    }

    private fun setupOption(viewId: Int, iconRes: Int, title: String, subtitle: String, onClick: (() -> Unit)? = null) {
        val view = findViewById<View>(viewId) ?: return
        
        val iconView = view.findViewById<ImageView>(R.id.icon)
        val titleView = view.findViewById<TextView>(R.id.title)
        val subtitleView = view.findViewById<TextView>(R.id.subtitle)
        
        iconView?.setImageResource(iconRes)
        titleView?.text = title
        subtitleView?.text = subtitle
        
        view.setOnClickListener {
            if (onClick != null) {
                onClick()
            } else {
                Toast.makeText(this, "Opening $title settings...", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
