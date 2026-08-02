package com.forge.os.presentation.screens.antitheft

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.security.antitheft.AntiTheftManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AntiTheftViewModel @Inject constructor(
    private val antiTheftManager: AntiTheftManager,
) : ViewModel() {

    val state: StateFlow<AntiTheftManager.AntiTheftState> = antiTheftManager.state

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun refreshState() {
        // Force reload state
        antiTheftManager.state.value.let { current ->
            _message.value = null
        }
    }

    fun getDeviceAdminIntent(): Intent {
        return antiTheftManager.requestDeviceAdmin()
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled && !state.value.deviceAdminActive) {
            _message.value = "Please activate device admin first"
            return
        }
        antiTheftManager.setEnabled(enabled)
        _message.value = if (enabled) "Anti-theft enabled" else "Anti-theft disabled"
    }

    fun clearTriggered() {
        antiTheftManager.clearTriggered()
        _message.value = "Theft alert cleared"
    }

    fun lockDevice() {
        val success = antiTheftManager.lockDevice()
        _message.value = if (success) "Device locked" else "Failed to lock device"
    }

    fun wipeDevice() {
        val success = antiTheftManager.wipeDevice()
        _message.value = if (success) "Device wipe initiated" else "Failed to wipe device"
    }

    fun updateLocation() {
        val loc = antiTheftManager.updateLocation()
        _message.value = if (loc != null) {
            "Location: ${loc.first}, ${loc.second}"
        } else {
            "Location unavailable"
        }
    }

    fun sendTestAlert() {
        antiTheftManager.sendAlerts()
        _message.value = "Test alert sent to ${state.value.trustedContacts.size} contacts"
    }

    fun addTrustedContact(phoneNumber: String) {
        antiTheftManager.addTrustedContact(phoneNumber)
        _message.value = "Added $phoneNumber"
    }

    fun removeTrustedContact(phoneNumber: String) {
        antiTheftManager.removeTrustedContact(phoneNumber)
        _message.value = "Removed $phoneNumber"
    }

    fun setAlertMessage(message: String) {
        antiTheftManager.setAlertMessage(message)
    }

    fun showMessage(msg: String) {
        _message.value = msg
    }

    fun clearMessage() {
        _message.value = null
    }
}
