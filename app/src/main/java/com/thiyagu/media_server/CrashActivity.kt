package com.thiyagu.media_server

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import kotlin.system.exitProcess

class CrashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash)

        val errorDetails = intent.getStringExtra(EXTRA_ERROR_DETAILS) ?: "Unknown error"
        
        val btnRestart = findViewById<MaterialButton>(R.id.btn_restart)
        val btnDetails = findViewById<MaterialButton>(R.id.btn_details)
        val errorDetailsContainer = findViewById<LinearLayout>(R.id.error_details_container)
        val tvErrorDetails = findViewById<TextView>(R.id.tv_error_details)

        tvErrorDetails.text = errorDetails

        btnRestart.setOnClickListener {
            // Restart the app by launching the splash/main activity
            val intent = Intent(this, SplashActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finish()
            exitProcess(0)
        }

        btnDetails.setOnClickListener {
            if (errorDetailsContainer.visibility == View.VISIBLE) {
                errorDetailsContainer.visibility = View.GONE
                btnDetails.text = "View Details"
            } else {
                errorDetailsContainer.visibility = View.VISIBLE
                btnDetails.text = "Hide Details"
            }
        }
    }

    companion object {
        const val EXTRA_ERROR_DETAILS = "error_details"
    }
}
