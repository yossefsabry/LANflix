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

class WelcomeActivity : AppCompatActivity() {

    private val userPreferences: UserPreferences by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        // Apply Native Blur on API 31+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val brandingContainer = findViewById<android.widget.LinearLayout>(R.id.branding_container)
            brandingContainer.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    30f, 30f, android.graphics.Shader.TileMode.MIRROR
                )
            )
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
