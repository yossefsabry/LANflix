package com.thiyagu.media_server.di

import androidx.room.Room
import com.thiyagu.media_server.data.UserPreferences
import com.thiyagu.media_server.data.db.MediaDatabase
import com.thiyagu.media_server.data.repository.MediaRepository
import com.thiyagu.media_server.data.repository.UserRepository
import com.thiyagu.media_server.server.ServerManager
import com.thiyagu.media_server.viewmodel.HomeViewModel
import com.thiyagu.media_server.viewmodel.ProfileViewModel
import com.thiyagu.media_server.viewmodel.StreamingViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    // Database
    single {
        Room.databaseBuilder(
            androidContext(),
            MediaDatabase::class.java,
            "lanflix_db"
        ).build()
    }

    // DAO
    single { get<MediaDatabase>().mediaDao() }

    // UserPreferences
    single { UserPreferences(androidContext()) }

    // Managers
    single { ServerManager(androidContext()) }

    // Repositories
    single { MediaRepository(androidContext(), get()) }
    single { UserRepository(get()) }

    // ViewModels
    viewModel { HomeViewModel(get()) }
    viewModel { ProfileViewModel(get()) }
    viewModel { StreamingViewModel(get(), get()) }
}
