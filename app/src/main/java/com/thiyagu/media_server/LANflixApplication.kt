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
}
