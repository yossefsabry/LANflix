package com.thiyagu.media_server

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

import androidx.lifecycle.lifecycleScope
import com.thiyagu.media_server.data.UserPreferences
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

import android.graphics.Color
import androidx.core.view.WindowCompat
import android.view.View
import android.view.WindowManager

class WelcomeActivity : AppCompatActivity() {

    private val userPreferences: UserPreferences by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make full screen (Edge-to-Edge)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        
        setContentView(R.layout.activity_welcome)

        // Handle Window Insets
        // Handle Window Insets
        val rootView = findViewById<View>(R.id.welcome_root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, windowInsets ->
            val insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            
            // Apply insets to the scrolling content container, preserving its original padding
            // We want the ScrollView to span the whole screen (so you can scroll behind bars), 
            // but the content insider needs to be padded.
            
            val contentContainer = findViewById<View>(R.id.branding_content).parent as View // The LinearLayout inside ScrollView
            
            // Original padding is 32dp
            val originalPadding = resources.getDimensionPixelSize(R.dimen.welcome_screen_padding)
            
            contentContainer.setPadding(
                originalPadding + insets.left, 
                originalPadding + insets.top, // Add top inset to top padding
                originalPadding + insets.right, 
                originalPadding + insets.bottom // Add bottom inset to bottom padding
            )
            
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }

        val etUsername = findViewById<TextInputEditText>(R.id.et_username)
        val btnGetStarted = findViewById<MaterialButton>(R.id.btn_get_started)

        btnGetStarted.setOnClickListener {
            val username = etUsername.text.toString().trim()
            if (username.isNotEmpty()) {
                saveUsername(username)
            } else {
                Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveUsername(username: String) {
        lifecycleScope.launch {
            userPreferences.saveUsername(username)
            navigateToHome()
        }
    }

    private fun navigateToHome() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
