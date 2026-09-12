package com.omi4wos.mobile.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omi4wos.mobile.omi.OmiConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val apiKey: String = "",
    val appId: String = "",
    val userId: String = "",
    val firebaseToken: String = "",
    val firebaseRefreshToken: String = "",
    val firebaseWebApiKey: String = "",
    val uploadUrl: String = "http://124.222.91.138:8081/upload-audio",
    val uploadApiKey: String = "***",
    val isSaving: Boolean = false,
    val saveSuccess: Boolean? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val omiConfig = OmiConfig(application)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val config = omiConfig.getConfig()
            _uiState.value = _uiState.value.copy(
                apiKey = config.apiKey,
                appId = config.appId,
                userId = config.userId,
                firebaseToken = config.firebaseToken,
                firebaseRefreshToken = config.firebaseRefreshToken,
                firebaseWebApiKey = config.firebaseWebApiKey,
                uploadUrl = config.uploadUrl,
                uploadApiKey = config.uploadApiKey
            )
        }
    }

    fun updateApiKey(value: String) {
        _uiState.value = _uiState.value.copy(apiKey = value)
    }

    fun updateAppId(value: String) {
        _uiState.value = _uiState.value.copy(appId = value)
    }

    fun updateUserId(value: String) {
        _uiState.value = _uiState.value.copy(userId = value)
    }

    fun updateFirebaseToken(value: String) {
        _uiState.value = _uiState.value.copy(firebaseToken = value)
    }

    fun updateFirebaseRefreshToken(value: String) {
        _uiState.value = _uiState.value.copy(firebaseRefreshToken = value)
    }

    fun updateFirebaseWebApiKey(value: String) {
        _uiState.value = _uiState.value.copy(firebaseWebApiKey = value)
    }

    fun updateUploadUrl(value: String) {
        _uiState.value = _uiState.value.copy(uploadUrl = value)
    }

    fun updateUploadApiKey(value: String) {
        _uiState.value = _uiState.value.copy(uploadApiKey = value)
    }

    fun saveSettings() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                val state = _uiState.value
                val existingConfig = omiConfig.getConfig()
                omiConfig.saveConfig(
                    OmiConfig.Config(
                        apiKey = state.apiKey,
                        appId = state.appId,
                        userId = state.userId,
                        firebaseToken = state.firebaseToken,
                        firebaseRefreshToken = state.firebaseRefreshToken,
                        firebaseWebApiKey = state.firebaseWebApiKey,
                        firebaseTokenExpiresAt = existingConfig.firebaseTokenExpiresAt, // Preserve expiry tracking
                        uploadUrl = state.uploadUrl,
                        uploadApiKey = state.uploadApiKey
                    )
                )
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveSuccess = true
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveSuccess = false
                )
            }
        }
    }
}
