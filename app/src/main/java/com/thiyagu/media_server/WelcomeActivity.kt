package com.thiyagu.media_server

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class WelcomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        val etUsername = findViewById<TextInputEditText>(R.id.et_username)
        val btnGetStarted = findViewById<MaterialButton>(R.id.btn_get_started)

        btnGetStarted.setOnClickListener {
            val username = etUsername.text.toString().trim()
            if (username.isNotEmpty()) {
                saveUsername(username)
                navigateToHome()
            } else {
                Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveUsername(username: String) {
        val sharedPref = getSharedPreferences("LANflixPrefs", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("username", username)
            apply()
        }
    }

    private fun navigateToHome() {
        val intent = Intent(this, StreamingActivity::class.java)
        startActivity(intent)
        finish()
    }
}
