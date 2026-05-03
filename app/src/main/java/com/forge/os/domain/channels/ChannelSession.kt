package com.forge.os.domain.channels

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One step inside a [ChannelSession]'s live timeline. The UI renders these
 * in order, distinguishing each kind by colour / typography.
 */
data class SessionEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val kind: Kind,
    val content: String,
    val toolName: String? = null,
    val isError: Boolean = false,
) {
    enum class Kind {
        IncomingText,        // user → bot
        IncomingAttachment,  // user → bot (voice / photo / document / ...)
        ChatAction,          // bot is "typing…" / "recording…"
        Thinking,            // partial assistant text mid-stream
        ToolCall,            // assistant requested tool X with args Y
        ToolResult,          // tool X returned Z (or failed)
        OutgoingText,        // final assistant reply that was sent to chat
        OutgoingVoice,       // voice note that was sent to chat
        OutgoingAttachment,  // file / photo / video that was sent to chat
        AgentError,          // agent loop crashed / API error
        Info,                // misc UI breadcrumb
    }
}

/**
 * A session is the live conversation between the bot and ONE chat.
 * Keyed by `${channelId}:${chatId}`.
 */
data class ChannelSession(
    val channelId: String,
    val channelType: String,
    val chatId: String,
    val displayName: String,
    val events: List<SessionEvent>,
    val lastActivity: Long,
) {
    val key: String get() = "$channelId:$chatId"
}

/**
 * Process-wide store for live channel sessions. Pure in-memory so the
 * timeline survives recompositions but resets on app death — that's fine
 * for "what's happening right now" UX. Persistent history still goes to
 * `MemoryManager.logEvent`.
 */
@Singleton
class ChannelSessionStore @Inject constructor() {

    private val _sessions = MutableStateFlow<Map<String, ChannelSession>>(emptyMap())
    val sessions: StateFlow<Map<String, ChannelSession>> = _sessions.asStateFlow()

    /** Append [event] to the session for ([channelId], [chatId]); creates
     *  the session if it doesn't exist yet. Trimmed to [MAX_EVENTS]. */
    @Synchronized
    fun record(
        channelId: String,
        channelType: String,
        chatId: String,
        displayName: String,
        event: SessionEvent,
    ) {
        if (chatId.isBlank()) return
        val key = "$channelId:$chatId"
        val current = _sessions.value
        val existing = current[key]
        val nextEvents = ((existing?.events ?: emptyList()) + event).takeLast(MAX_EVENTS)
        val next = ChannelSession(
            channelId = channelId,
            channelType = channelType,
            chatId = chatId,
            displayName = existing?.displayName?.takeIf { it.isNotBlank() } ?: displayName,
            events = nextEvents,
            lastActivity = event.timestamp,
        )
        _sessions.value = current + (key to next)
    }

    @Synchronized
    fun clear(key: String) {
        _sessions.value = _sessions.value - key
    }

    @Synchronized
    fun clearAll() { _sessions.value = emptyMap() }

    fun get(key: String): ChannelSession? = _sessions.value[key]

    fun recent(): List<ChannelSession> =
        _sessions.value.values.sortedByDescending { it.lastActivity }

    companion object {
        const val MAX_EVENTS = 200
    }
}
