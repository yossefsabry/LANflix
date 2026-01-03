package com.thiyagu.media_server

import android.app.Application
import com.thiyagu.media_server.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

import androidx.appcompat.app.AppCompatDelegate
import com.thiyagu.media_server.data.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent

class LANflixApplication : Application(), KoinComponent {
    
    private val userPreferences: UserPreferences by inject()

    override fun onCreate() {
        super.onCreate()
        
        // Set up global crash handler
        setupCrashHandler()
        
        startKoin {
            androidLogger()
            androidContext(this@LANflixApplication)
            modules(appModule)
        }
        
        // Apply Theme
        CoroutineScope(Dispatchers.Main).launch {
            userPreferences.themeFlow.collect { theme ->
                val mode = when(theme) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        }
    }
    
    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Prepare error details
                val stackTrace = android.util.Log.getStackTraceString(throwable)
                val errorDetails = "Thread: ${thread.name}\n\n$stackTrace"
                
                // Limit to 2000 chars to avoid Intent size limits
                val limitedDetails = if (errorDetails.length > 2000) {
                    errorDetails.substring(0, 2000) + "\n...(truncated)"
                } else {
                    errorDetails
                }
                
                // Launch crash activity
                val intent = android.content.Intent(this, CrashActivity::class.java).apply {
                    putExtra(CrashActivity.EXTRA_ERROR_DETAILS, limitedDetails)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
                
                // Kill the process
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            } catch (e: Exception) {
                // If our crash handler fails, fall back to default
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
