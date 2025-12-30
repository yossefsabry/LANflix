package com.thiyagu.media_server.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thiyagu.media_server.data.repository.UserRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val userRepository: UserRepository
) : ViewModel() {

    val username: StateFlow<String> = userRepository.username
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    fun updateUsername(newName: String) {
        viewModelScope.launch {
            userRepository.setUsername(newName)
        }
    }

    fun logout() {
        viewModelScope.launch {
            userRepository.clear()
        }
    }
}
