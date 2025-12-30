package com.thiyagu.media_server

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.thiyagu.media_server.data.UserPreferences
import com.thiyagu.media_server.databinding.ActivityProfileBinding
import com.thiyagu.media_server.databinding.DialogEditUsernameBinding
import com.thiyagu.media_server.databinding.ItemProfileOptionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.Exception

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var userPreferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            binding = ActivityProfileBinding.inflate(layoutInflater)
            setContentView(binding.root)
            
            userPreferences = UserPreferences(this)

            // Reactive Username Loading
            lifecycleScope.launch {
                userPreferences.usernameFlow.collect { username ->
                    binding.profileUsername.text = username
                }
            }

            binding.btnBack.setOnClickListener { finish() }

            binding.btnLogout.setOnClickListener { logout() }
            
            binding.btnDisconnect.setOnClickListener {
                Toast.makeText(this, "Disconnected from Local Network", Toast.LENGTH_SHORT).show()
            }
            
            binding.btnEditUsername.setOnClickListener {
                showEditUsernameDialog()
            }
            
            setupOption(binding.optSettings, R.drawable.ic_settings, "App Settings", "Playback, Theme, Notifications") {
                startActivity(Intent(this, AppSettingsActivity::class.java))
            }
            setupOption(binding.optPrivacy, R.drawable.ic_shield, "Privacy & Security", "History, Cache, Permissions") {
                startActivity(Intent(this, PrivacySecurityActivity::class.java))
            }
            setupOption(binding.optNetwork, R.drawable.ic_wifi, "Network Info", "192.168.1.45 • Port 8080") {
                startActivity(Intent(this, NetworkInfoActivity::class.java))
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error loading profile: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun showEditUsernameDialog() {
        val dialogBinding = DialogEditUsernameBinding.inflate(layoutInflater)
        
        // Pre-fill with current username
        lifecycleScope.launch {
            val currentUsername = userPreferences.usernameFlow.first()
            dialogBinding.usernameInput.setText(currentUsername)
        }
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        dialogBinding.btnSave.setOnClickListener {
            val newUsername = dialogBinding.usernameInput.text.toString().trim()
            
            // Validate username
            when {
                newUsername.isEmpty() -> {
                    dialogBinding.usernameInputLayout.error = "Username cannot be empty"
                }
                newUsername.length < 3 -> {
                    dialogBinding.usernameInputLayout.error = "Username must be at least 3 characters"
                }
                newUsername.length > 20 -> {
                    dialogBinding.usernameInputLayout.error = "Username must be 20 characters or less"
                }
                !newUsername.matches(Regex("^[a-zA-Z0-9_]+$")) -> {
                    dialogBinding.usernameInputLayout.error = "Only letters, numbers, and underscore allowed"
                }
                else -> {
                    // Save username
                    lifecycleScope.launch {
                        userPreferences.saveUsername(newUsername)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@ProfileActivity, "Username updated successfully", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        }
                    }
                }
            }
        }
        
        dialog.show()
    }
    
    private fun setupOption(optionBinding: ItemProfileOptionBinding, iconRes: Int, title: String, subtitle: String, onClick: () -> Unit) {
        optionBinding.icon.setImageResource(iconRes)
        optionBinding.title.text = title
        optionBinding.subtitle.text = subtitle
        optionBinding.root.setOnClickListener { onClick() }
    }

    private fun logout() {
        lifecycleScope.launch {
            userPreferences.clear()
            withContext(Dispatchers.Main) {
                // Navigate to WelcomeActivity (simulated) or just finish for now if it doesn't exist yet
                try {
                    val intent = Intent(this@ProfileActivity, Class.forName("com.thiyagu.media_server.WelcomeActivity"))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                } catch (e: ClassNotFoundException) {
                    Toast.makeText(this@ProfileActivity, "Logged out (Welcome screen not found)", Toast.LENGTH_SHORT).show()
                    finish()
                } catch (e: Exception) {
                    finish()
                }
            }
        }
    }
}
