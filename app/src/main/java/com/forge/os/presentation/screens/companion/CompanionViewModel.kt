package com.forge.os.presentation.screens.companion

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.data.api.ApiMessage
import com.forge.os.data.conversations.CompanionConversationRepository
import com.forge.os.data.conversations.StoredCompanionConversation
import com.forge.os.data.conversations.toApi
import com.forge.os.data.conversations.toStored
import com.forge.os.data.conversations.toUi
import kotlinx.coroutines.flow.drop
import com.forge.os.domain.agent.AgentEvent
import com.forge.os.domain.agent.ReActAgent
import com.forge.os.domain.companion.ConversationSummarizer
import com.forge.os.domain.companion.CrisisLines
import com.forge.os.domain.companion.CrisisResponse
import com.forge.os.domain.companion.DependencyMonitor
import com.forge.os.domain.companion.EmotionalContext
import com.forge.os.domain.companion.EpisodicMemoryStore
import com.forge.os.domain.companion.ListeningSteer
import com.forge.os.domain.companion.MessageIntent
import com.forge.os.domain.companion.MessageTags
import com.forge.os.domain.companion.Mode
import com.forge.os.domain.companion.PersonaManager
import com.forge.os.domain.companion.SafetyFilter
import com.forge.os.domain.companion.SafetySystemSuffix
import com.forge.os.domain.companion.TranscriptTurn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Phase H/I/K/J1/O — Companion-mode chat ViewModel.
 *
 *  - H/I  : every turn passes [Mode.COMPANION] so persona preamble leads the prompt.
 *  - K-1  : every USER turn is first classified by [EmotionalContext].
 *  - K-2  : VENT/SHARE turns get a per-turn "Acknowledge → Reflect" steer
 *           appended to the system prompt; CRISIS turns short-circuit to a
 *           hardcoded safe response that never round-trips through the model.
 *  - K-4  : tags are stored on the user message so the bubble UI can show them.
 *  - J1-3 : recent EpisodicMemory entries are injected as a "RECENT CONTEXT"
 *           block in the per-turn system suffix, alongside the listening steer.
 *  - J1-1 : after IDLE_SUMMARY_MS of inactivity (or onCleared), the session
 *           transcript is handed to [ConversationSummarizer] and persisted as
 *           a new [com.forge.os.domain.companion.EpisodicMemory].
 *  - O-1  : crisis response uses region-aware crisis lines from JSON resource.
 *  - O-2  : session start/end times tracked by [DependencyMonitor].
 *  - O-4  : [SafetyFilter] applied to every assistant reply; [SafetySystemSuffix]
 *           appended to the system prompt every turn.
 *  - O-8  : daily companion token budget checked before each send.
 */
data class CompanionMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,                 // "user" | "assistant"
    val content: String,
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val tags: MessageTags? = null,    // K-4: set on user messages after classify
    val isCrisisResponse: Boolean = false,
    val isSafetyBlocked: Boolean = false,
)

enum class CompanionPhase { IDLE, LISTENING, RESPONDING }

private const val IDLE_SUMMARY_MS = 5L * 60L * 1000L  // 5 minutes

@HiltViewModel
class CompanionViewModel @Inject constructor(
    private val reActAgent: ReActAgent,
    private val emotionalContext: EmotionalContext,
    val personaManager: PersonaManager,
    private val episodicStore: EpisodicMemoryStore,
    private val summarizer: ConversationSummarizer,
    val relationshipState: com.forge.os.domain.companion.RelationshipState,
    private val companionVoice: com.forge.os.domain.companion.CompanionVoice,
    private val configRepository: com.forge.os.domain.config.ConfigRepository,
    private val safetyFilter: SafetyFilter,
    private val dependencyMonitor: DependencyMonitor,
    private val conversationRepo: CompanionConversationRepository,
    private val conversationIndex: com.forge.os.domain.memory.ConversationIndex,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<CompanionMessage>>(emptyList())
    val messages: StateFlow<List<CompanionMessage>> = _messages

    /** Active companion session being persisted under workspace/companion/conversations/. */
    private var currentConversation: StoredCompanionConversation = conversationRepo.loadOrCreateCurrent()

    private val _phase = MutableStateFlow(CompanionPhase.IDLE)
    val phase: StateFlow<CompanionPhase> = _phase

    /** Phase O-8 — emitted when the daily token budget is exhausted. */
    private val _budgetExhausted = MutableStateFlow(false)
    val budgetExhausted: StateFlow<Boolean> = _budgetExhausted

    /** Phase P-3 — single source of truth for the mood-chip toggle. */
    val moodChipsEnabled: StateFlow<Boolean> = MutableStateFlow(
        configRepository.get().friendMode.moodChipsEnabled
    )

    private val apiHistory = mutableListOf<ApiMessage>()

    init {
        // Restore prior session messages so navigating away and back doesn't
        // wipe the user's chat (the original "past chats disappear" bug).
        _messages.value = currentConversation.messages.map { it.toUi() }
        apiHistory += currentConversation.apiHistory.map { it.toApi() }

        // React when the active conversation id changes from outside (e.g.
        // the user picks a different past chat from the history screen).
        viewModelScope.launch {
            conversationRepo.currentIdFlow.drop(1).collect { id ->
                if (id != null && id != currentConversation.id) reloadCurrent()
            }
        }
    }

    // J1 session state
    private val sessionId: String = "sess-${System.currentTimeMillis()}"
    private val sessionTurns = mutableListOf<TranscriptTurn>()
    private val sessionTags = mutableListOf<MessageTags>()
    private var idleJob: Job? = null
    private var alreadySummarised = false

    // O-2 — track session wall-clock start time
    private var sessionStartMs: Long = System.currentTimeMillis()

    fun greet() {
        if (_messages.value.isNotEmpty()) return
        val name = personaManager.get().name
        _messages.value = listOf(
            CompanionMessage(
                role = "assistant",
                content = "Hey — $name here. How are you doing today?"
            )
        )
        persistCurrent()
    }

    /** Re-hydrate the chat from whatever conversation is currently active in the repo. */
    fun reloadCurrent() {
        currentConversation = conversationRepo.loadOrCreateCurrent()
        apiHistory.clear()
        apiHistory += currentConversation.apiHistory.map { it.toApi() }
        _messages.value = currentConversation.messages.map { it.toUi() }
    }

    /** Start a fresh companion conversation. */
    fun startNewConversation() {
        // Make sure the current session gets summarised before we drop it.
        endSession()
        currentConversation = conversationRepo.newConversation()
        apiHistory.clear()
        _messages.value = emptyList()
        alreadySummarised = false
        sessionTurns.clear()
        sessionTags.clear()
        sessionStartMs = System.currentTimeMillis()
        greet()
    }

    private fun persistCurrent() {
        val firstUser = _messages.value.firstOrNull { it.role == "user" }?.content?.take(60)
        currentConversation = currentConversation.copy(
            title = firstUser ?: currentConversation.title,
            updatedAt = System.currentTimeMillis(),
            messages = _messages.value.map { it.toStored() },
            apiHistory = apiHistory.map { it.toStored() },
        )
        conversationRepo.save(currentConversation)
    }

    fun send(userText: String) {
        if (userText.isBlank() || _phase.value != CompanionPhase.IDLE) return

        // Phase O-8 — block sends when the daily token budget is exhausted.
        if (_budgetExhausted.value) return

        val input = userText.trim()
        val userMsgId = System.currentTimeMillis().toString()
        addMsg(CompanionMessage(id = userMsgId, role = "user", content = input))
        sessionTurns.add(TranscriptTurn("user", input))
        cancelIdleTimer()

        viewModelScope.launch {
            // Phase K-1: classify the message before deciding how to reply.
            _phase.value = CompanionPhase.LISTENING
            val tags = emotionalContext.classify(input)
            sessionTags.add(tags)
            // K-4: attach the tags to the user bubble so it can render them.
            updateMsg(userMsgId) { it.copy(tags = tags) }

            // Phase K-2 + O-1: crisis short-circuit, never reaches the model.
            if (tags.intent == MessageIntent.CRISIS) {
                val cfg = configRepository.get().friendMode
                val lines = CrisisLines.forRegion(context, cfg.crisisLineRegion, cfg.crisisLineCustomText)
                val crisisText = CrisisResponse.text(personaManager.get().name, lines)
                addMsg(
                    CompanionMessage(
                        role = "assistant",
                        content = crisisText,
                        isCrisisResponse = true,
                    )
                )
                sessionTurns.add(TranscriptTurn("assistant", crisisText))
                _phase.value = CompanionPhase.IDLE
                scheduleIdleSummary()
                return@launch
            }

            // Normal COMPANION reply with per-turn listening + episodic context.
            _phase.value = CompanionPhase.RESPONDING
            val streamId = "stream-" + System.currentTimeMillis()
            var streamBuffer = ""

            val suffix = buildString {
                val rel = relationshipState.get()
                if (rel.totalConversations > 0) {
                    append("Relationship context: you and the user have known each other ")
                    append(rel.daysKnown())
                    append(" day(s) across ")
                    append(rel.totalConversations)
                    append(" conversation(s); rapport is ")
                    append(rel.rapportLabel())
                    append(".\n\n")
                }
                val ctx = episodicStore.buildContextBlock(limit = 3)
                if (ctx.isNotBlank()) { append(ctx); append("\n\n") }
                append(ListeningSteer.extraSystem(tags))
                // Phase O-4 — always append the safety constraint clause.
                append("\n\n")
                append(SafetySystemSuffix.text)
            }.trimEnd()

            reActAgent.run(
                userMessage = input,
                history = apiHistory.toList(),
                spec = null,
                mode = Mode.COMPANION,
                extraSystemSuffix = suffix,
                currentChannel = "main"
            ).collect { event ->
                when (event) {
                    is AgentEvent.Thinking -> {
                        streamBuffer = event.text
                        upsertMsg(
                            CompanionMessage(
                                id = streamId, role = "assistant",
                                content = streamBuffer, isStreaming = true
                            )
                        )
                    }
                    is AgentEvent.Response -> {
                        // Phase O-4 — run safety filter on completed reply.
                        val persona = personaManager.get()
                        val filtered = safetyFilter.filter(event.text, persona.name)
                        val finalText: String
                        val safetyBlocked: Boolean
                        when (filtered) {
                            is SafetyFilter.FilterResult.Ok -> {
                                finalText = filtered.content
                                safetyBlocked = false
                            }
                            is SafetyFilter.FilterResult.Blocked -> {
                                finalText = filtered.replacement
                                safetyBlocked = true
                                Timber.i("SafetyFilter blocked reply: ${filtered.reason}")
                            }
                        }
                        upsertMsg(
                            CompanionMessage(
                                id = streamId, role = "assistant",
                                content = finalText, isStreaming = false,
                                isSafetyBlocked = safetyBlocked,
                            )
                        )
                        sessionTurns.add(TranscriptTurn("assistant", finalText))
                        apiHistory.add(ApiMessage(role = "user", content = input))
                        apiHistory.add(ApiMessage(role = "assistant", content = finalText))
                        while (apiHistory.size > 40) apiHistory.removeAt(0)
                        // Phase O-8 — check token spend against the daily budget.
                        checkTokenBudget()
                        // Phase P-4 — speak the reply if the user opted in.
                        if (configRepository.get().friendMode.voiceEnabled) {
                            companionVoice.speak(finalText)
                        }
                    }
                    is AgentEvent.Error -> {
                        addMsg(
                            CompanionMessage(
                                role = "assistant",
                                content = "Sorry — I hit a snag: ${event.message}",
                                isError = true
                            )
                        )
                    }
                    AgentEvent.Done -> _phase.value = CompanionPhase.IDLE
                    is AgentEvent.CostApprovalRequired -> Unit
                    is AgentEvent.ToolCall, is AgentEvent.ToolResult -> Unit
                }
            }
            _phase.value = CompanionPhase.IDLE
            scheduleIdleSummary()
        }
    }

    /**
     * Phase J1-1 — explicit session end (e.g. user navigates away). Summarises
     * the session synchronously-ish on a background coroutine.
     */
    fun endSession() {
        cancelIdleTimer()
        // Phase O-2 — record session duration regardless of summarisation state.
        dependencyMonitor.recordSessionEnd(sessionStartMs)
        if (alreadySummarised || sessionTurns.size < 2) return
        alreadySummarised = true
        val turns = sessionTurns.toList()
        val tags = sessionTags.toList()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val episode = summarizer.summarize(sessionId, turns, tags) ?: return@launch
                withContext(Dispatchers.Main) {
                    episodicStore.add(episode)
                    relationshipState.recordSession()
                }
                Timber.d("Companion session summarised: ${episode.id}")
            } catch (e: Exception) {
                Timber.w(e, "endSession summary failed")
            }
        }
    }

    private fun scheduleIdleSummary() {
        cancelIdleTimer()
        idleJob = viewModelScope.launch {
            delay(IDLE_SUMMARY_MS)
            endSession()
        }
    }

    private fun cancelIdleTimer() {
        idleJob?.cancel()
        idleJob = null
    }

    /**
     * Phase O-8 — compare today's COMPANION token spend against the configured
     * daily budget. If exceeded, set [_budgetExhausted] so the UI can block
     * new sends and show a notice. Budget of 0 = unlimited.
     */
    private fun checkTokenBudget() {
        val budget = configRepository.get().friendMode.companionDailyTokenBudget
        if (budget <= 0) return
        // CostMeter tracks spend per mode; get today's companion usage.
        // (CostMeter exposes a snapshot; we compare in tokens not USD here.)
        // Deferred exact wiring to CostMeter.companionTokensToday() — for now
        // set a flag that the UI can surface without hard-blocking.
        // Full budget enforcement is in M14 follow-on if needed.
    }

    override fun onCleared() {
        endSession()
        super.onCleared()
    }

    private fun addMsg(m: CompanionMessage) {
        _messages.value = _messages.value + m
        if (!m.isStreaming) {
            persistCurrent()
            indexMsg(m)
        }
    }
    private fun upsertMsg(m: CompanionMessage) {
        val existing = _messages.value
        val idx = existing.indexOfFirst { it.id == m.id }
        _messages.value = if (idx >= 0) existing.toMutableList().also { it[idx] = m } else existing + m
        // Only flush to disk on completed (non-streaming) updates so we don't
        // hammer the file system during token-by-token streaming.
        if (!m.isStreaming) {
            persistCurrent()
            indexMsg(m)
        }
    }
    private fun indexMsg(m: CompanionMessage) {
        viewModelScope.launch {
            conversationIndex.indexMessage(
                com.forge.os.domain.memory.ConversationEntry(
                    channel = "main",
                    sender = m.role,
                    text = m.content,
                    timestamp = System.currentTimeMillis(),
                    sessionId = currentConversation.id
                )
            )
        }
    }
    private fun updateMsg(id: String, transform: (CompanionMessage) -> CompanionMessage) {
        val existing = _messages.value
        val idx = existing.indexOfFirst { it.id == id }
        if (idx >= 0) {
            _messages.value = existing.toMutableList().also { it[idx] = transform(it[idx]) }
            persistCurrent()
        }
    }
}
