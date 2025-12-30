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
        
        setupOption(R.id.opt_permissions, R.drawable.ic_shield, "App Permissions", "Storage, Network") {
            showAppPermissionsInfo()
        }
        
        setupOption(R.id.opt_privacy_policy, R.drawable.ic_shield, "Privacy Policy", "View our privacy policy") {
            showPrivacyPolicyInfo()
        }
    }

    private fun showAppPermissionsInfo() {
        AlertDialog.Builder(this)
            .setTitle("App Permissions")
            .setMessage(
                "LANflix requires the following permissions:\n\n" +
                "• Storage: To access and stream your local media files\n" +
                "• Network: To broadcast and connect to servers on your local network\n\n" +
                "You can manage these permissions in your device settings."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showPrivacyPolicyInfo() {
        AlertDialog.Builder(this)
            .setTitle("Privacy Policy")
            .setMessage(
                "LANflix Privacy Summary:\n\n" +
                "• All data stays on your local network\n" +
                "• No data is sent to external servers\n" +
                "• No tracking or analytics\n" +
                "• No user data collection\n\n" +
                "LANflix is a fully offline, privacy-focused local streaming application. " +
                "Your media never leaves your network."
            )
            .setPositiveButton("OK", null)
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
