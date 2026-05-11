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
    
    private val _isAvailable = MutableStateFlow(true) // Default to true, let user try
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()
    
    init {
        // Check availability asynchronously to avoid blocking
        viewModelScope.launch {
            try {
                _isAvailable.value = voiceInputManager.isSTTAvailable()
            } catch (e: Exception) {
                Timber.w(e, "Failed to check voice availability")
                _isAvailable.value = false
            }
        }
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
     * Speak text using TTS — strips markdown before sending to the TTS engine
     * so asterisks, backticks, headers etc. are not read aloud.
     */
    fun speak(text: String) {
        viewModelScope.launch {
            try {
                val clean = text
                    .replace(Regex("```[\\s\\S]*?```"), "code block")  // fenced code blocks
                    .replace(Regex("`[^`]+`"), "")                      // inline code
                    .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")        // bold
                    .replace(Regex("\\*([^*]+)\\*"), "$1")              // italic
                    .replace(Regex("__([^_]+)__"), "$1")                // bold underscore
                    .replace(Regex("_([^_]+)_"), "$1")                  // italic underscore
                    .replace(Regex("#+\\s+"), "")                       // headings
                    .replace(Regex("^[-*+]\\s+", RegexOption.MULTILINE), "") // list bullets
                    .replace(Regex("^>\\s+", RegexOption.MULTILINE), "")     // blockquotes
                    .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")    // links → label only
                    .replace(Regex("<[^>]+>"), "")                      // HTML tags
                    .replace(Regex("\\|"), " ")                         // table pipes
                    .replace(Regex("\\\\([*_`#])"), "$1")               // escaped chars
                    .replace(Regex("\\s{2,}"), " ")                     // collapse whitespace
                    .trim()
                voiceInputManager.speak(clean)
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
