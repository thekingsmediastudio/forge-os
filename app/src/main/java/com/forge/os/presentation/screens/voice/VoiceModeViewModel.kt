package com.forge.os.presentation.screens.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.agent.AgentEvent
import com.forge.os.domain.agent.ReActAgent
import com.forge.os.domain.voice.TTSState
import com.forge.os.domain.voice.VoiceInputManager
import com.forge.os.data.api.ApiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

enum class VoicePhase {
    IDLE,       // voice mode not active
    LISTENING,  // STT active, waiting for user speech
    THINKING,   // agent is processing
    SPEAKING,   // TTS reading response
}

data class VoiceModeState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val transcript: String = "",        // what the user said
    val agentResponse: String = "",     // what the agent said
    val rmsLevel: Float = 0f,           // mic level 0..1
    val error: String? = null,
)

@HiltViewModel
class VoiceModeViewModel @Inject constructor(
    private val voiceInputManager: VoiceInputManager,
    private val reActAgent: ReActAgent,
) : ViewModel() {

    private val _state = MutableStateFlow(VoiceModeState())
    val state: StateFlow<VoiceModeState> = _state.asStateFlow()

    // Shared API history for the voice session (separate from chat history)
    private val voiceHistory = mutableListOf<ApiMessage>()
    private var agentJob: Job? = null
    private var ttsJob: Job? = null

    init {
        // Mirror RMS level into state
        viewModelScope.launch {
            voiceInputManager.rmsLevel.collect { rms ->
                if (_state.value.phase == VoicePhase.LISTENING) {
                    _state.value = _state.value.copy(rmsLevel = rms)
                }
            }
        }

        // When STT produces a result, send it to the agent
        viewModelScope.launch {
            voiceInputManager.lastRecognizedText.collect { text ->
                if (text.isNotBlank() && _state.value.phase == VoicePhase.LISTENING) {
                    onSpeechRecognized(text)
                }
            }
        }

        // When TTS finishes speaking, auto-restart listening
        viewModelScope.launch {
            voiceInputManager.ttsState.collect { ttsState ->
                if (ttsState == TTSState.IDLE && _state.value.phase == VoicePhase.SPEAKING) {
                    // Small pause before listening again so it doesn't pick up its own echo
                    delay(600)
                    if (_state.value.phase == VoicePhase.SPEAKING) {
                        startListening()
                    }
                }
            }
        }
    }

    /** Enter voice mode — starts listening immediately. */
    fun enterVoiceMode() {
        voiceHistory.clear()
        _state.value = VoiceModeState(phase = VoicePhase.IDLE)
        startListening()
    }

    /** Exit voice mode — stops everything. */
    fun exitVoiceMode() {
        agentJob?.cancel()
        ttsJob?.cancel()
        voiceInputManager.stopListening()
        voiceInputManager.stopSpeaking()
        _state.value = VoiceModeState(phase = VoicePhase.IDLE)
    }

    /** Manually re-trigger listening (e.g. user taps the orb). */
    fun tapOrb() {
        when (_state.value.phase) {
            VoicePhase.LISTENING -> {
                // Tap while listening = stop and send what we have
                voiceInputManager.stopListening()
                val partial = _state.value.transcript
                if (partial.isNotBlank()) onSpeechRecognized(partial)
            }
            VoicePhase.SPEAKING -> {
                // Tap while speaking = interrupt TTS and listen again
                voiceInputManager.stopSpeaking()
                startListening()
            }
            VoicePhase.THINKING -> { /* can't interrupt agent mid-run */ }
            VoicePhase.IDLE -> startListening()
        }
    }

    private fun startListening() {
        _state.value = _state.value.copy(
            phase = VoicePhase.LISTENING,
            transcript = "",
            error = null,
        )
        voiceInputManager.startListening()
    }

    private fun onSpeechRecognized(text: String) {
        Timber.i("VoiceMode: recognized '$text'")
        _state.value = _state.value.copy(
            phase = VoicePhase.THINKING,
            transcript = text,
            agentResponse = "",
        )

        agentJob?.cancel()
        agentJob = viewModelScope.launch {
            var fullResponse = ""
            try {
                reActAgent.run(
                    userMessage = text,
                    history = voiceHistory.toList(),
                    spec = null,
                    currentChannel = "voice",
                ).collect { event ->
                    when (event) {
                        is AgentEvent.Thinking -> {
                            fullResponse = event.text
                            _state.value = _state.value.copy(agentResponse = fullResponse)
                        }
                        is AgentEvent.Response -> {
                            fullResponse = event.text
                            _state.value = _state.value.copy(agentResponse = fullResponse)
                            voiceHistory.add(ApiMessage(role = "user", content = text))
                            voiceHistory.add(ApiMessage(role = "assistant", content = fullResponse))
                            while (voiceHistory.size > 20) voiceHistory.removeAt(0)
                        }
                        is AgentEvent.Error -> {
                            _state.value = _state.value.copy(
                                phase = VoicePhase.IDLE,
                                error = event.message,
                            )
                            return@collect
                        }
                        else -> { /* tool calls etc — ignore in voice mode */ }
                    }
                }

                if (fullResponse.isNotBlank()) {
                    speakResponse(fullResponse)
                } else {
                    startListening()
                }
            } catch (e: Exception) {
                Timber.e(e, "VoiceMode: agent error")
                _state.value = _state.value.copy(
                    phase = VoicePhase.IDLE,
                    error = "Agent error: ${e.message}",
                )
            }
        }
    }

    private fun speakResponse(text: String) {
        // Strip markdown for cleaner TTS output
        val clean = text
            .replace(Regex("```[\\s\\S]*?```"), "code block")
            .replace(Regex("`[^`]+`"), "")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\*([^*]+)\\*"), "$1")
            .replace(Regex("#+\\s"), "")
            .replace(Regex("- "), "")
            .trim()
            .take(500) // don't read a novel

        _state.value = _state.value.copy(phase = VoicePhase.SPEAKING)
        voiceInputManager.speak(clean)
    }

    override fun onCleared() {
        super.onCleared()
        exitVoiceMode()
    }
}
