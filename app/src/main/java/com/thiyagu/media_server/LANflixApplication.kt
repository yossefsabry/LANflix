package com.thiyagu.media_server

import android.app.Application
import com.thiyagu.media_server.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class LANflixApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        startKoin {
            androidLogger()
            androidContext(this@LANflixApplication)
            modules(appModule)
        }
    }
}
