package com.thiyagu.media_server

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.lang.Exception

class ProfileActivity : AppCompatActivity() {

    private lateinit var tvUsername: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            setContentView(R.layout.activity_profile)

            tvUsername = findViewById(R.id.profile_username)
            val btnBack = findViewById<View>(R.id.btn_back)
            val btnLogout = findViewById<TextView>(R.id.btn_logout) 
            val btnDisconnect = findViewById<MaterialButton>(R.id.btn_disconnect)
            val btnEdit = findViewById<View>(R.id.btn_edit_username)

            // Load and display username
            loadUsername()

            btnBack?.setOnClickListener { finish() }

            btnLogout?.setOnClickListener { logout() }
            
            btnDisconnect?.setOnClickListener {
                Toast.makeText(this, "Disconnected from Local Network", Toast.LENGTH_SHORT).show()
            }
            
            btnEdit?.setOnClickListener {
                showEditUsernameDialog()
            }
            
            setupOption(R.id.opt_settings, R.drawable.ic_settings, "App Settings", "Playback, Theme, Notifications") {
                startActivity(Intent(this, AppSettingsActivity::class.java))
            }
            setupOption(R.id.opt_privacy, R.drawable.ic_shield, "Privacy & Security", "History, Cache, Permissions") {
                startActivity(Intent(this, PrivacySecurityActivity::class.java))
            }
            setupOption(R.id.opt_network, R.drawable.ic_wifi, "Network Info", "192.168.1.45 • Port 8080") {
                startActivity(Intent(this, NetworkInfoActivity::class.java))
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error loading profile: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun loadUsername() {
        val sharedPref = getSharedPreferences("LANflixPrefs", Context.MODE_PRIVATE)
        tvUsername.text = sharedPref.getString("username", "localUser_99")
    }
    
    private fun showEditUsernameDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_username, null)
        val usernameInput = dialogView.findViewById<TextInputEditText>(R.id.username_input)
        val usernameLayout = dialogView.findViewById<TextInputLayout>(R.id.username_input_layout)
        
        // Pre-fill with current username
        val sharedPref = getSharedPreferences("LANflixPrefs", Context.MODE_PRIVATE)
        usernameInput.setText(sharedPref.getString("username", "localUser_99"))
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        dialogView.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
        }
        
        dialogView.findViewById<MaterialButton>(R.id.btn_save).setOnClickListener {
            val newUsername = usernameInput.text.toString().trim()
            
            // Validate username
            when {
                newUsername.isEmpty() -> {
                    usernameLayout.error = "Username cannot be empty"
                }
                newUsername.length < 3 -> {
                    usernameLayout.error = "Username must be at least 3 characters"
                }
                newUsername.length > 20 -> {
                    usernameLayout.error = "Username must be 20 characters or less"
                }
                !newUsername.matches(Regex("^[a-zA-Z0-9_]+$")) -> {
                    usernameLayout.error = "Only letters, numbers, and underscore allowed"
                }
                else -> {
                    // Save username
                    with(sharedPref.edit()) {
                        putString("username", newUsername)
                        apply()
                    }
                    
                    // Update UI
                    loadUsername()
                    Toast.makeText(this, "Username updated successfully", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }
        
        dialog.show()
    }
    
    private fun setupOption(viewId: Int, iconRes: Int, title: String, subtitle: String, onClick: () -> Unit) {
        val view = findViewById<View>(viewId)
        if (view == null) return
        
        val iconView = view.findViewById<ImageView>(R.id.icon)
        val titleView = view.findViewById<TextView>(R.id.title)
        val subtitleView = view.findViewById<TextView>(R.id.subtitle)
        
        iconView?.setImageResource(iconRes)
        titleView?.text = title
        subtitleView?.text = subtitle
        
        view.setOnClickListener { onClick() }
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
