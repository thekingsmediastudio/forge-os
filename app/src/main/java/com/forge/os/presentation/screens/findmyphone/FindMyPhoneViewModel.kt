package com.forge.os.presentation.screens.findmyphone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.findmyphone.FindMyPhoneManager
import com.forge.os.domain.findmyphone.FindMyPhoneResponder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FindMyPhoneViewModel @Inject constructor(
    private val findMyPhoneManager: FindMyPhoneManager,
    private val findMyPhoneResponder: FindMyPhoneResponder,
) : ViewModel() {

    val state: StateFlow<FindMyPhoneManager.FindMyPhoneState> = findMyPhoneManager.state

    fun setEnabled(enabled: Boolean) {
        findMyPhoneManager.setEnabled(enabled)
    }

    fun setResponseType(type: String) {
        findMyPhoneManager.setResponseType(type)
    }

    fun setTtsMessage(message: String) {
        findMyPhoneManager.setTtsMessage(message)
    }

    fun setDurationSeconds(seconds: Int) {
        findMyPhoneManager.setDurationSeconds(seconds)
    }

    fun testResponse() {
        viewModelScope.launch {
            findMyPhoneResponder.triggerManualResponse()
        }
    }
}
