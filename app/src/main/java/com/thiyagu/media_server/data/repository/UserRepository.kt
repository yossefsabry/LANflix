package com.thiyagu.media_server.data.repository

import com.thiyagu.media_server.data.UserPreferences
import kotlinx.coroutines.flow.Flow

class UserRepository(private val userPreferences: UserPreferences) {

    val username: Flow<String> = userPreferences.usernameFlow

    suspend fun setUsername(name: String) {
        userPreferences.saveUsername(name)
    }

    suspend fun clear() {
        userPreferences.clearUserData()
    }
}
