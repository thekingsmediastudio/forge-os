package com.forge.os.presentation.screens.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.data.api.ApiMessage
import com.forge.os.data.conversations.ConversationRepository
import com.forge.os.data.conversations.StoredConversation
import com.forge.os.data.conversations.StoredChatMessage
import com.forge.os.data.conversations.StoredApiMessage
import com.forge.os.domain.agent.AgentEvent
import com.forge.os.domain.agent.ReActAgent
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.voice.TTSState
import com.forge.os.domain.voice.VoiceInputManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
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
    val conversationId: String? = null, // the stored conversation for this session
)

@HiltViewModel
class VoiceModeViewModel @Inject constructor(
    private val voiceInputManager: VoiceInputManager,
    private val reActAgent: ReActAgent,
    private val conversationRepo: ConversationRepository,
    private val configRepository: ConfigRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(VoiceModeState())
    val state: StateFlow<VoiceModeState> = _state.asStateFlow()

    // API history for the current voice session
    private val voiceHistory = mutableListOf<ApiMessage>()
    // UI messages for the current voice session (persisted to conversation)
    private val voiceMessages = mutableListOf<StoredChatMessage>()
    // The conversation being written to
    private var currentConversation: StoredConversation? = null

    private var agentJob: Job? = null

    init {
        // Mirror RMS level into state while listening
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
                    delay(600) // brief pause so mic doesn't catch speaker echo
                    if (_state.value.phase == VoicePhase.SPEAKING) {
                        startListening()
                    }
                }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Enter voice mode — creates a new conversation and starts listening. */
    fun enterVoiceMode() {
        voiceHistory.clear()
        voiceMessages.clear()

        // Create a dedicated conversation for this voice session
        val timestamp = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
        val id = "voice-${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()
        val conv = StoredConversation(
            id = id,
            title = "🎤 Voice — $timestamp",
            createdAt = now,
            updatedAt = now,
        )
        conversationRepo.save(conv)
        conversationRepo.setCurrent(id)
        currentConversation = conv

        // Add a system greeting message
        val greeting = StoredChatMessage(
            id = UUID.randomUUID().toString(),
            role = "system",
            content = "🎤 Voice session started — ${configRepository.get().agentIdentity.name} is listening.",
        )
        voiceMessages.add(greeting)
        persistConversation()

        _state.value = VoiceModeState(
            phase = VoicePhase.IDLE,
            conversationId = id,
        )
        startListening()
    }

    /** Exit voice mode — stops everything, saves the conversation. */
    fun exitVoiceMode() {
        agentJob?.cancel()
        voiceInputManager.stopListening()
        voiceInputManager.stopSpeaking()
        persistConversation()
        _state.value = VoiceModeState(phase = VoicePhase.IDLE)
    }

    /** Tap the orb to toggle listening / interrupt speaking. */
    fun tapOrb() {
        when (_state.value.phase) {
            VoicePhase.LISTENING -> {
                voiceInputManager.stopListening()
                val partial = _state.value.transcript
                if (partial.isNotBlank()) onSpeechRecognized(partial)
            }
            VoicePhase.SPEAKING -> {
                voiceInputManager.stopSpeaking()
                startListening()
            }
            VoicePhase.THINKING -> { /* agent is running, can't interrupt */ }
            VoicePhase.IDLE -> startListening()
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun startListening() {
        _state.value = _state.value.copy(
            phase = VoicePhase.LISTENING,
            transcript = "",
            error = null,
            rmsLevel = 0f,
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

        // Persist the user turn immediately
        voiceMessages.add(StoredChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = text,
        ))
        persistConversation()

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

                            // Update API history
                            voiceHistory.add(ApiMessage(role = "user", content = text))
                            voiceHistory.add(ApiMessage(role = "assistant", content = fullResponse))
                            while (voiceHistory.size > 20) voiceHistory.removeAt(0)

                            // Persist agent response
                            voiceMessages.add(StoredChatMessage(
                                id = UUID.randomUUID().toString(),
                                role = "assistant",
                                content = fullResponse,
                            ))
                            persistConversation()
                        }
                        is AgentEvent.ToolCall -> {
                            // Record tool calls in the conversation so they're visible in history
                            voiceMessages.add(StoredChatMessage(
                                id = UUID.randomUUID().toString(),
                                role = "tool_call",
                                content = event.args,
                                toolName = event.name,
                            ))
                        }
                        is AgentEvent.ToolResult -> {
                            voiceMessages.add(StoredChatMessage(
                                id = UUID.randomUUID().toString(),
                                role = "tool_result",
                                content = event.result,
                                toolName = event.name,
                                isError = event.isError,
                            ))
                        }
                        is AgentEvent.Error -> {
                            _state.value = _state.value.copy(
                                phase = VoicePhase.IDLE,
                                error = event.message,
                            )
                            return@collect
                        }
                        else -> {}
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
        val clean = text
            .replace(Regex("```[\\s\\S]*?```"), "code block")
            .replace(Regex("`[^`]+`"), "")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\*([^*]+)\\*"), "$1")
            .replace(Regex("#+\\s"), "")
            .replace(Regex("- "), "")
            .trim()
            .take(500)

        _state.value = _state.value.copy(phase = VoicePhase.SPEAKING)
        voiceInputManager.speak(clean)
    }

    /** Write the current message list to the conversation file. */
    private fun persistConversation() {
        val conv = currentConversation ?: return
        val apiStored = voiceHistory.map { StoredApiMessage(role = it.role, content = it.content) }
        // Derive title from first user message
        val firstUserMsg = voiceMessages.firstOrNull { it.role == "user" }?.content
        val title = if (firstUserMsg != null)
            "🎤 ${firstUserMsg.take(50)}"
        else
            conv.title

        val updated = conv.copy(
            title = title,
            updatedAt = System.currentTimeMillis(),
            messages = voiceMessages.toList(),
            apiHistory = apiStored,
        )
        currentConversation = updated
        viewModelScope.launch(Dispatchers.IO) {
            conversationRepo.save(updated)
        }
    }

    override fun onCleared() {
        super.onCleared()
        exitVoiceMode()
    }
}
