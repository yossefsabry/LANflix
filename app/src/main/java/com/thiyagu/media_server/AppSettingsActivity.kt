package com.thiyagu.media_server

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AppSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_settings)

        val btnBack = findViewById<View>(R.id.btn_back)
        btnBack?.setOnClickListener { finish() }

        setupOption(R.id.opt_video_quality, R.drawable.ic_video, "Video Quality", "Auto (Recommended)")
        setupOption(R.id.opt_theme, R.drawable.ic_settings, "Theme", "System Default")
        setupOption(R.id.opt_notifications, R.drawable.ic_settings, "Notifications", "Enabled")
        setupOption(R.id.opt_autoplay, R.drawable.ic_video, "Auto-play Next", "On")
    }

    private fun setupOption(viewId: Int, iconRes: Int, title: String, subtitle: String) {
        val view = findViewById<View>(viewId) ?: return
        
        val iconView = view.findViewById<ImageView>(R.id.icon)
        val titleView = view.findViewById<TextView>(R.id.title)
        val subtitleView = view.findViewById<TextView>(R.id.subtitle)
        
        iconView?.setImageResource(iconRes)
        titleView?.text = title
        subtitleView?.text = subtitle
        
        view.setOnClickListener {
            Toast.makeText(this, "Opening $title settings...", Toast.LENGTH_SHORT).show()
        }
    }
}
