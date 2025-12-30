package com.thiyagu.media_server.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

class UserPreferences(private val context: Context) {

    suspend fun saveUsername(username: String) {
        context.dataStore.edit { preferences ->
            preferences[USERNAME_KEY] = username
        }
    }
    
    companion object {
        val USERNAME_KEY = stringPreferencesKey("username")
        val SELECTED_FOLDER_KEY = stringPreferencesKey("selected_folder_uri")
        val THEME_KEY = stringPreferencesKey("app_theme") // system, light, dark
    }

    val usernameFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[USERNAME_KEY] ?: "User"
        }
        
    val selectedFolderFlow: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[SELECTED_FOLDER_KEY]
        }
        
    val themeFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[THEME_KEY] ?: "dark"
        }

    suspend fun saveSelectedFolder(uri: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_FOLDER_KEY] = uri
        }
    }
    
    suspend fun saveTheme(theme: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_KEY] = theme
        }
    }
    
    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
