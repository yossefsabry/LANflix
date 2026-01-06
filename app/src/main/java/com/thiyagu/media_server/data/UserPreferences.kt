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
    
    suspend fun saveSubfolderScanning(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SUBFOLDER_SCANNING_KEY] = enabled
        }
    }
    
    // History Retention
    companion object {
        val USERNAME_KEY = stringPreferencesKey("username")
        val SELECTED_FOLDER_KEY = stringPreferencesKey("selected_folder_uri")
        val THEME_KEY = stringPreferencesKey("app_theme") 
        val SUBFOLDER_SCANNING_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("subfolder_scanning")
        val HISTORY_RETENTION_KEY = androidx.datastore.preferences.core.intPreferencesKey("history_retention_days")
        val SERVER_NAME_KEY = stringPreferencesKey("server_name")
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
        
    val subfolderScanningFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[SUBFOLDER_SCANNING_KEY] ?: false
        }

    val historyRetentionFlow: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[HISTORY_RETENTION_KEY] ?: 10 // Default 10 days
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

    suspend fun saveHistoryRetention(days: Int) {
        context.dataStore.edit { preferences ->
            preferences[HISTORY_RETENTION_KEY] = days
        }
    }

    val serverNameFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[SERVER_NAME_KEY] ?: "LANflix Server"
        }

    suspend fun saveServerName(name: String) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_NAME_KEY] = name
        }
    }
    
    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
