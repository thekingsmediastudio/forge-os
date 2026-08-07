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
import com.forge.os.domain.voice.VoiceRecognitionError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.sample
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
    private val configRepository: ConfigRepository) : ViewModel() {

    private val _state = MutableStateFlow(VoiceModeState())
    val state: StateFlow<VoiceModeState> = _state.asStateFlow()

    // API history for the current voice session
    private val voiceHistory = mutableListOf<ApiMessage>()
    // UI messages for the current voice session (persisted to conversation)
    private val voiceMessages = mutableListOf<StoredChatMessage>()
    // The conversation being written to
    private var currentConversation: StoredConversation? = null

    private var agentJob: Job? = null
    private var consecutiveRetryCount = 0
    private var thinkingWatchdog: Job? = null
    private var speakingWatchdog: Job? = null

    init {
        // Mirror RMS level into state while listening, throttled to ~10fps
        viewModelScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            voiceInputManager.rmsLevel.sample(100).collect { rms ->
                if (_state.value.phase == VoicePhase.LISTENING) {
                    _state.value = _state.value.copy(rmsLevel = rms)
                }
            }
        }

        // Mirror partial transcript while listening for real-time feedback
        viewModelScope.launch {
            voiceInputManager.partialText.collect { partial ->
                if (_state.value.phase == VoicePhase.LISTENING) {
                    _state.value = _state.value.copy(transcript = partial)
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
                    speakingWatchdog?.cancel()
                    delay(600) // brief pause so mic doesn't catch speaker echo
                    if (_state.value.phase == VoicePhase.SPEAKING) {
                        startListening()
                    }
                }
            }
        }

        viewModelScope.launch {
            voiceInputManager.recognitionError.collect { error ->
                if (error != null && _state.value.phase == VoicePhase.LISTENING) {
                    onRecognitionError(error)
                }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Enter voice mode — resumes the provided conversation when possible. */
    fun enterVoiceMode(conversationId: String? = null) {
        // Claim the mic so the hotword service releases its recognizer before we
        // start ours — this prevents the ERROR_CLIENT/ERROR_RECOGNIZER_BUSY race.
        com.forge.os.domain.voice.MicOwnership.claim(com.forge.os.domain.voice.MicOwnership.Owner.VOICE_MODE)
        com.forge.os.service.HotwordDetectionService.voiceModeActive = true
        voiceHistory.clear()
        voiceMessages.clear()

        val existing = conversationId?.let { conversationRepo.load(it) }
        val conv = if (existing != null) {
            existing
        } else {
            // Fallback: create a dedicated conversation for standalone voice sessions.
            val timestamp = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
            val id = "voice-${System.currentTimeMillis()}"
            val now = System.currentTimeMillis()
            StoredConversation(
                id = id,
                title = "🎤 Voice — $timestamp",
                createdAt = now,
                updatedAt = now)
        }

        currentConversation = conv
        voiceHistory += conv.apiHistory.map { ApiMessage(role = it.role, content = it.content) }
        voiceMessages += conv.messages

        if (existing == null) {
            conversationRepo.save(conv)
            conversationRepo.setCurrent(conv.id)
        }

        val markerText = if (existing != null) {
            "🎤 Voice mode resumed — ${configRepository.get().agentIdentity.name} is listening."
        } else {
            "🎤 Voice session started — ${configRepository.get().agentIdentity.name} is listening."
        }
        voiceMessages.add(StoredChatMessage(
            id = UUID.randomUUID().toString(),
            role = "system",
            content = markerText))
        persistConversation()

        _state.value = VoiceModeState(
            phase = VoicePhase.IDLE,
            conversationId = conv.id)
        // Start from a clean recognizer — the hotword service may have left the
        // shared SpeechRecognizer in a stuck state when it released the mic.
        voiceInputManager.resetSpeechRecognizer()
        // Give the hotword service a beat to release its recognizer before we start
        // ours — starting immediately is what triggers the ERROR_CLIENT mic race.
        viewModelScope.launch {
            delay(300)
            if (_state.value.phase == VoicePhase.IDLE) startListening()
        }
    }

    /** Exit voice mode — stops everything, saves the conversation. */
    fun exitVoiceMode() {
        agentJob?.cancel()
        thinkingWatchdog?.cancel()
        speakingWatchdog?.cancel()
        voiceInputManager.stopListening()
        voiceInputManager.stopSpeaking()
        persistConversation()
        _state.value = VoiceModeState(phase = VoicePhase.IDLE)
        // Hand the mic back to the hotword service.
        com.forge.os.service.HotwordDetectionService.voiceModeActive = false
        com.forge.os.domain.voice.MicOwnership.release(com.forge.os.domain.voice.MicOwnership.Owner.VOICE_MODE)
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
        consecutiveRetryCount = 0
        _state.value = _state.value.copy(
            phase = VoicePhase.LISTENING,
            transcript = "",
            error = null,
            rmsLevel = 0f)
        // Wait for the hotword service to fully release the mic before we grab it.
        viewModelScope.launch {
            val deadline = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < deadline) {
                if (com.forge.os.domain.voice.MicOwnership.owner.value ==
                    com.forge.os.domain.voice.MicOwnership.Owner.NONE ||
                    com.forge.os.domain.voice.MicOwnership.owner.value ==
                    com.forge.os.domain.voice.MicOwnership.Owner.VOICE_MODE) break
                delay(50)
            }
            voiceInputManager.startListening()
        }
    }

    private fun onRecognitionError(error: VoiceRecognitionError) {
        when (error) {
            VoiceRecognitionError.NoMatch,
            VoiceRecognitionError.SpeechTimeout -> {
                consecutiveRetryCount++
                if (consecutiveRetryCount >= 5) {
                    // Too many retries — pause and let the user tap to resume
                    _state.value = _state.value.copy(
                        phase = VoicePhase.IDLE,
                        error = "Tap the orb to try again.",
                        rmsLevel = 0f)
                    consecutiveRetryCount = 0
                    return
                }
                _state.value = _state.value.copy(
                    error = "Still listening — speak when ready, or tap the orb to pause.",
                    rmsLevel = 0f)
                viewModelScope.launch {
                    delay(1200)
                    if (_state.value.phase == VoicePhase.LISTENING) {
                        voiceInputManager.startListening()
                    }
                }
            }
            VoiceRecognitionError.Busy,
            VoiceRecognitionError.ClientError,
            VoiceRecognitionError.AudioError,
            VoiceRecognitionError.ServerError -> {
                // Transient mic/service errors (e.g. the hotword↔voice-mode handoff).
                // Back off and retry instead of killing the session with a raw code.
                consecutiveRetryCount++
                if (consecutiveRetryCount >= 4) {
                    _state.value = _state.value.copy(
                        phase = VoicePhase.IDLE,
                        error = "Mic is busy — tap the orb to try again.",
                        rmsLevel = 0f)
                    consecutiveRetryCount = 0
                    return
                }
                viewModelScope.launch {
                    delay(800)
                    if (_state.value.phase == VoicePhase.LISTENING) {
                        voiceInputManager.startListening()
                    }
                }
            }
            else -> _state.value = _state.value.copy(
                phase = VoicePhase.IDLE,
                error = error.message,
                rmsLevel = 0f)
        }
    }

    private fun onSpeechRecognized(text: String) {
        Timber.i("VoiceMode: recognized '$text'")
        consecutiveRetryCount = 0
        _state.value = _state.value.copy(
            phase = VoicePhase.THINKING,
            transcript = text,
            agentResponse = "")

        // Watchdog: if the agent never responds (API hang, silent failure),
        // recover to IDLE with an actionable error instead of hanging forever.
        thinkingWatchdog?.cancel()
        thinkingWatchdog = viewModelScope.launch {
            delay(90_000)
            if (_state.value.phase == VoicePhase.THINKING) {
                Timber.w("VoiceMode: agent watchdog fired — no response in 90s")
                agentJob?.cancel()
                _state.value = _state.value.copy(
                    phase = VoicePhase.IDLE,
                    error = "No response from the agent — tap the orb to try again.")
            }
        }

        // Persist the user turn immediately
        voiceMessages.add(StoredChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = text))
        persistConversation()

        agentJob?.cancel()
        agentJob = viewModelScope.launch {
            var fullResponse = ""
            try {
                reActAgent.run(
                    userMessage = text,
                    history = voiceHistory.toList(),
                    spec = null,
                    currentChannel = "voice").collect { event ->
                    when (event) {
                        is AgentEvent.Thinking -> {
                            fullResponse = event.text
                            _state.value = _state.value.copy(agentResponse = fullResponse)
                        }
                        is AgentEvent.Response -> {
                            thinkingWatchdog?.cancel()
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
                                content = fullResponse))
                            persistConversation()
                        }
                        is AgentEvent.ToolCall -> {
                            // Record tool calls in the conversation so they're visible in history
                            voiceMessages.add(StoredChatMessage(
                                id = UUID.randomUUID().toString(),
                                role = "tool_call",
                                content = event.args,
                                toolName = event.name))
                        }
                        is AgentEvent.ToolResult -> {
                            voiceMessages.add(StoredChatMessage(
                                id = UUID.randomUUID().toString(),
                                role = "tool_result",
                                content = event.result,
                                toolName = event.name,
                                isError = event.isError))
                        }
                        is AgentEvent.Error -> {
                            thinkingWatchdog?.cancel()
                            _state.value = _state.value.copy(
                                phase = VoicePhase.IDLE,
                                error = event.message)
                            return@collect
                        }
                        else -> {}
                    }
                }

                thinkingWatchdog?.cancel()
                if (fullResponse.isNotBlank()) {
                    speakResponse(fullResponse)
                } else {
                    startListening()
                }
            } catch (e: Exception) {
                thinkingWatchdog?.cancel()
                Timber.e(e, "VoiceMode: agent error")
                _state.value = _state.value.copy(
                    phase = VoicePhase.IDLE,
                    error = "Agent error: ${e.message}")
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

        if (!voiceInputManager.isTtsReady() || clean.isBlank()) {
            // TTS engine failed to initialise — don't get stuck in SPEAKING
            // waiting for an onDone that will never arrive.
            Timber.w("VoiceMode: TTS not ready, skipping speech")
            startListening()
            return
        }

        // Watchdog: if onDone never fires (engine hiccup), recover to listening.
        // Duration scales with text length, capped at 60s.
        val maxSpeakMs = (clean.length * 120L).coerceIn(5_000, 60_000)
        speakingWatchdog?.cancel()
        speakingWatchdog = viewModelScope.launch {
            delay(maxSpeakMs)
            if (_state.value.phase == VoicePhase.SPEAKING) {
                Timber.w("VoiceMode: TTS watchdog fired after ${maxSpeakMs}ms")
                voiceInputManager.stopSpeaking()
                startListening()
            }
        }

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
            apiHistory = apiStored)
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
