package com.forge.os.domain.agents

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 3 — In-process pub/sub message bus for multi-agent collaboration.
 *
 * Sub-agents spawned by [DelegationManager] can publish intermediate findings
 * to named topics, and other agents (or the orchestrator) can subscribe or
 * poll those topics. This enables collaborative workflows where agents share
 * context without waiting for the full delegation cycle to complete.
 *
 * Thread-safe: backed by [ConcurrentHashMap] + Kotlin [SharedFlow].
 */
@Singleton
class AgentMessageBus @Inject constructor() {

    data class BusMessage(
        val topic: String,
        val senderId: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    /** Replay = 64 so a late subscriber can catch recent history. */
    private val _flow = MutableSharedFlow<BusMessage>(replay = 64)
    val messages: SharedFlow<BusMessage> = _flow.asSharedFlow()

    /** Per-topic buffer for synchronous poll-style reads. */
    private val topicBuffers = ConcurrentHashMap<String, MutableList<BusMessage>>()

    // ─── Write ───────────────────────────────────────────────────────────────

    suspend fun publish(topic: String, senderId: String, content: String) {
        val msg = BusMessage(topic, senderId, content)
        topicBuffers.getOrPut(topic) { mutableListOf() }.add(msg)
        _flow.emit(msg)
        Timber.d("AgentMessageBus: [$senderId] → topic=$topic (${content.length} chars)")
    }

    // ─── Read ────────────────────────────────────────────────────────────────

    /**
     * Synchronous read — returns all messages on [topic] since the bus started
     * (or was last cleared). Useful for tool-call-based reads where suspension
     * semantics aren't ideal.
     */
    fun read(topic: String, limit: Int = 50): List<BusMessage> {
        return topicBuffers[topic]?.takeLast(limit) ?: emptyList()
    }

    /** Return all topics that have at least one message. */
    fun topics(): Set<String> = topicBuffers.keys.toSet()

    /** Clear a topic buffer (e.g. after the collaborative task is done). */
    fun clear(topic: String) {
        topicBuffers.remove(topic)
    }

    /** Clear everything. */
    fun clearAll() {
        topicBuffers.clear()
    }

    fun summary(): String = buildString {
        if (topicBuffers.isEmpty()) {
            append("📡 Message bus: no active topics")
            return@buildString
        }
        appendLine("📡 Message bus (${topicBuffers.size} topic(s)):")
        topicBuffers.forEach { (topic, msgs) ->
            appendLine("  • $topic — ${msgs.size} message(s)")
        }
    }
}
