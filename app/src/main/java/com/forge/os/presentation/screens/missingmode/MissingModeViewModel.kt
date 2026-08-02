package com.forge.os.presentation.screens.missingmode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.missingmode.AutoResponder
import com.forge.os.domain.missingmode.MissingModeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MissingModeViewModel @Inject constructor(
    private val missingModeManager: MissingModeManager,
    private val autoResponder: AutoResponder,
) : ViewModel() {

    val state: StateFlow<MissingModeManager.MissingModeState> = missingModeManager.state

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun setEnabled(enabled: Boolean) {
        missingModeManager.setEnabled(enabled)
        _message.value = if (enabled) "Missing Mode enabled" else "Missing Mode disabled"
    }

    fun setResponseType(type: String) {
        missingModeManager.setResponseType(type)
        _message.value = "Response type updated"
    }

    fun setSmsTemplate(template: String) {
        missingModeManager.setSmsTemplate(template)
    }

    fun setTtsMessage(message: String) {
        missingModeManager.setTtsMessage(message)
    }

    fun addTrustedContact(phoneNumber: String) {
        missingModeManager.addTrustedContact(phoneNumber)
        _message.value = "Added $phoneNumber to trusted contacts"
    }

    fun removeTrustedContact(phoneNumber: String) {
        missingModeManager.removeTrustedContact(phoneNumber)
        _message.value = "Removed $phoneNumber from trusted contacts"
    }

    fun testResponse() {
        viewModelScope.launch {
            val state = missingModeManager.state.value
            if (state.trustedContacts.isEmpty()) {
                _message.value = "Add a trusted contact first"
                return@launch
            }

            // Test with the first trusted contact
            val testNumber = state.trustedContacts.first()
            _message.value = "Testing response to $testNumber..."

            // Just send SMS for testing (don't actually answer calls)
            val success = missingModeManager.sendAutoReplySms(testNumber)
            _message.value = if (success) "Test SMS sent to $testNumber" else "Failed to send test SMS"
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
