package com.forge.os.presentation.screens.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.voice.VoiceInputManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for voice input functionality.
 */
@HiltViewModel
class VoiceInputViewModel @Inject constructor(
    private val voiceInputManager: VoiceInputManager
) : ViewModel() {
    
    val isListening: StateFlow<Boolean> = voiceInputManager.isListening
    val lastRecognizedText: StateFlow<String> = voiceInputManager.lastRecognizedText
    
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()
    
    init {
        checkAvailability()
    }
    
    /**
     * Check if voice input is available on this device.
     */
    private fun checkAvailability() {
        _isAvailable.value = voiceInputManager.isSTTAvailable()
    }
    
    /**
     * Start listening for voice input.
     */
    fun startListening() {
        try {
            voiceInputManager.startListening()
            Timber.i("Started voice input")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start voice input")
        }
    }
    
    /**
     * Stop listening for voice input.
     */
    fun stopListening() {
        try {
            voiceInputManager.stopListening()
            Timber.i("Stopped voice input")
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop voice input")
        }
    }
    
    /**
     * Speak text using TTS.
     */
    fun speak(text: String) {
        viewModelScope.launch {
            try {
                voiceInputManager.speak(text)
            } catch (e: Exception) {
                Timber.e(e, "Failed to speak text")
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        voiceInputManager.cleanup()
    }
}
