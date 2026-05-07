package com.forge.os.domain.channels

import android.content.Context
import com.forge.os.data.api.AiApiManager
import com.forge.os.data.api.ApiCallException
import com.forge.os.data.api.ApiMessage
import com.forge.os.domain.agent.AgentEvent
import com.forge.os.domain.agent.InputRoute
import com.forge.os.domain.agent.ReActAgent
import com.forge.os.domain.agent.UserInputBroker
import com.forge.os.domain.memory.MemoryManager
import com.forge.os.domain.security.ApiKeyProvider
import com.forge.os.domain.security.CustomEndpointRepository
import com.forge.os.domain.security.ProviderSpec
import com.forge.os.domain.security.SecureKeyStore
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lifecycle + send façade over all [Channel] adapters. Loads persisted
 * configs from [ChannelRepository] on first use, starts all enabled ones,
 * and surfaces the last 100 incoming messages as a StateFlow for the UI.
 *
 * Phase T: also wires the auto-reply pipeline. Each incoming message is
 * fed through [ReActAgent.run] (per-chat conversation history is kept),
 * the agent's progress is mirrored into [ChannelSessionStore] so the UI
 * can render a live timeline, and the final response is sent back via
 * [Channel.sendFormatted] using the channel's configured `parseMode`.
 */
@Singleton
class ChannelManager @Inject constructor(
    private val repository: ChannelRepository,
    private val memoryManager: MemoryManager,
    private val sessionStore: ChannelSessionStore,
    /** [ReActAgent] depends on [ToolRegistry], which depends on us — break
     *  the Hilt cycle with [Lazy]. */
    private val reActAgentLazy: Lazy<ReActAgent>,
    private val userInputBroker: UserInputBroker,
    private val sessionStoreOverrides: ChannelSessionModelOverrides,
    private val secureKeyStore: SecureKeyStore,
    private val customEndpoints: CustomEndpointRepository,
    /** AiApiManager pulls in the OkHttp stack which transitively depends on
     *  ProviderRouter → ToolRegistry → us. Hilt would die without [Lazy]. */
    private val aiApiManagerLazy: Lazy<AiApiManager>,
    private val conversationIndex: com.forge.os.domain.memory.ConversationIndex,
    @ApplicationContext private val context: Context,
    // Enhanced Integration: Connect with learning systems
    private val reflectionManager: com.forge.os.domain.agent.ReflectionManager,
    private val userPreferencesManager: com.forge.os.domain.user.UserPreferencesManager,
) {
    private val channels = ConcurrentHashMap<String, Channel>()
    private val ingestJobs = ConcurrentHashMap<String, Job>()
    /** Phase U2 — route keys whose auto-reply we should skip because the
     *  agent already delivered the final reply itself via channel_send to
     *  the same chat (was the cause of the Telegram double-send bug). */
    private val suppressedAutoReplies = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>()
    )
    /** Per-chat drain workers, keyed by `${channelId}:${chatId}`. The agent
     *  processes one inbound message at a time; new messages are appended
     *  to [pendingInbound] and picked up after the current turn finishes
     *  rather than cancelling the in-flight reply (the original Phase T
     *  "latest-wins" behaviour was confusing on long-running tasks). */
    private val replyJobs = ConcurrentHashMap<String, Job>()
    /** FIFO of incoming messages per chat that have not been processed yet. */
    private val pendingInbound = ConcurrentHashMap<String, ArrayDeque<IncomingMessage>>()
    /** Per-chat conversation history for the agent, keyed the same way. */
    private val chatHistories = ConcurrentHashMap<String, ArrayDeque<ApiMessage>>()
    /** Listens for `request_user_input` questions destined for any of our
     *  per-channel routes and forwards them to the matching chat. */
    private var brokerListenerJob: Job? = null
    /** sessionKey → routeKey for the chat that currently has an inbound
     *  message routed straight to the broker (bypassing the queue). */
    private val brokerRoutesBySession = ConcurrentHashMap<String, String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _recent = MutableStateFlow<List<IncomingMessage>>(emptyList())
    val recent: StateFlow<List<IncomingMessage>> = _recent.asStateFlow()

    /** Re-exposed so the UI can subscribe to the live timeline. */
    val sessions: StateFlow<Map<String, ChannelSession>> = sessionStore.sessions

    @Synchronized
    fun startAll() {
        ensureBrokerListener()
        repository.all().filter { it.enabled }.forEach { cfg ->
            scope.launch { startChannel(cfg) }
        }
    }

    @Synchronized
    fun stopAll() {
        channels.values.toList().forEach { ch ->
            scope.launch { runCatching { ch.stop() } }
        }
        ingestJobs.values.forEach { it.cancel() }
        ingestJobs.clear()
        replyJobs.values.forEach { it.cancel() }
        replyJobs.clear()
        pendingInbound.clear()
        brokerRoutesBySession.clear()
        channels.clear()
        brokerListenerJob?.cancel()
        brokerListenerJob = null
    }

    /**
     * Subscribe once to [UserInputBroker.questions] and forward every
     * question whose route matches one of our active channels to the
     * corresponding Telegram chat. Without this, a `request_user_input`
     * call inside a Telegram-driven agent run was published on the broker
     * but only the in-app ChatViewModel was listening — so the question
     * appeared on the phone screen instead of in the chat that asked it
     * (the original Phase T routing bug).
     */
    private fun ensureBrokerListener() {
        if (brokerListenerJob?.isActive == true) return
        brokerListenerJob = scope.launch {
            userInputBroker.questions.collect { q ->
                // Route key shape: "channel:<channelId>:<chatId>"
                if (!q.routeKey.startsWith("channel:")) return@collect
                val rest = q.routeKey.removePrefix("channel:")
                val sep = rest.indexOf(':')
                if (sep <= 0) return@collect
                val channelId = rest.substring(0, sep)
                val chatId = rest.substring(sep + 1)
                val ch = channels[channelId] ?: return@collect
                val sessionKey = "$channelId:$chatId"
                // Mark this session so the next inbound message is treated
                // as the user's answer rather than a brand-new turn.
                brokerRoutesBySession[sessionKey] = q.routeKey
                runCatching {
                    val text = "❓ ${q.question}"
                    val mode = ch.config.parseMode
                    if (mode.isNotBlank()) ch.sendFormatted(chatId, text, mode)
                    else ch.send(chatId, text)
                }
                sessionStore.record(
                    channelId = ch.config.id, channelType = ch.config.type,
                    chatId = chatId, displayName = ch.config.displayName,
                    event = SessionEvent(
                        kind = SessionEvent.Kind.OutgoingText,
                        content = "❓ ${q.question}",
                    ),
                )
            }
        }
    }

    fun list(): List<ChannelConfig> = repository.all()
    fun find(id: String): ChannelConfig? = repository.find(id)

    fun createTelegram(
        displayName: String,
        botToken: String,
        defaultChatId: String = "",
        autoReply: Boolean = true,
        parseMode: String = "HTML",
        allowedChatIds: String = "",
        purpose: String = "personal",
        systemPromptSuffix: String = "",
    ): ChannelConfig {
        val id = "tg_${UUID.randomUUID().toString().take(8)}"
        val configJson = buildString {
            append('{')
            append("\"botToken\":\"").append(botToken.jsonEscape()).append("\",")
            append("\"defaultChatId\":\"").append(defaultChatId.jsonEscape()).append('"')
            append('}')
        }
        // Auto-generate a sensible system prompt suffix based on purpose if none provided
        val resolvedSuffix = systemPromptSuffix.ifBlank {
            when (purpose) {
                "teaching"  -> "You are a teaching assistant on this channel. Explain concepts clearly, use examples, and encourage questions. Adapt your explanations to the learner's level."
                "work"      -> "You are a professional work assistant on this channel. Be concise, focused, and action-oriented. Prioritise tasks and deliverables."
                "support"   -> "You are a support agent on this channel. Be patient, empathetic, and solution-focused. Always confirm the issue is resolved before closing."
                else        -> "" // personal — no suffix, full agent behaviour
            }
        }
        val cfg = ChannelConfig(
            id = id, type = "telegram",
            displayName = displayName.ifBlank { "Telegram" },
            configJson = configJson,
            enabled = true,
            autoReply = autoReply,
            parseMode = parseMode,
            allowedChatIds = allowedChatIds,
            purpose = purpose,
            systemPromptSuffix = resolvedSuffix,
        )
        repository.upsert(cfg)
        scope.launch { startChannel(cfg) }
        return cfg
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val cfg = repository.find(id) ?: return
        val updated = cfg.copy(enabled = enabled)
        repository.upsert(updated)
        scope.launch {
            if (enabled) startChannel(updated) else stopChannel(id)
        }
    }

    fun setAutoReply(id: String, autoReply: Boolean) {
        val cfg = repository.find(id) ?: return
        repository.upsert(cfg.copy(autoReply = autoReply))
    }

    fun setParseMode(id: String, parseMode: String) {
        val cfg = repository.find(id) ?: return
        repository.upsert(cfg.copy(parseMode = parseMode))
    }

    fun setAllowedChatIds(id: String, csv: String) {
        val cfg = repository.find(id) ?: return
        repository.upsert(cfg.copy(allowedChatIds = csv.trim()))
    }

    /** Phase U — set per-channel provider/model. Pass blank `provider`
     *  to clear the override (channel reverts to global default + fallback chain). */
    fun setChannelModel(id: String, provider: String, model: String) {
        val cfg = repository.find(id) ?: return
        repository.upsert(cfg.copy(provider = provider.trim(), model = model.trim()))
    }

    /** Phase U — set per-session provider/model that wins over the
     *  channel-level override for ONE chat. Empty values clear it. */
    fun setSessionModel(channelId: String, chatId: String, provider: String, model: String) {
        sessionStoreOverrides.set(channelId, chatId, provider.trim(), model.trim())
    }

    /** Phase U — read the current per-session override (or null if none). */
    fun getSessionModel(channelId: String, chatId: String): Pair<String, String>? {
        val so = sessionStoreOverrides.get(channelId, chatId) ?: return null
        if (so.provider.isBlank() || so.model.isBlank()) return null
        return so.provider to so.model
    }

    /** Phase U — providers that have a key on file and can be selected as a
     *  per-channel/per-session override. Returns triples of:
     *    - `key`        — the value to persist in the override storage
     *                     (`enumName` for built-ins, `"custom:<id>"` for custom),
     *    - `displayName`— human-readable provider label,
     *    - `kind`       — "builtin" or "custom" so the picker UI can group.
     *  Both built-in providers (with a saved key) AND user-defined custom
     *  endpoints (with a saved key) are returned. */
    fun availableProviders(): List<Triple<String, String, String>> {
        val builtins = ApiKeyProvider.values()
            .filter { secureKeyStore.hasKey(it) }
            .map { Triple(it.name, it.displayName, "builtin") }
        val customs = customEndpoints.list()
            .filter { secureKeyStore.hasCustomKey(it.id) }
            .map { Triple("custom:${it.id}", it.name, "custom") }
        return builtins + customs
    }

    /** Suggested model for a provider key, used by the picker as the
     *  pre-selected fallback if the live `/models` probe is empty. Accepts
     *  either an [ApiKeyProvider] enum name or `"custom:<id>"`. */
    fun defaultModelFor(providerKey: String): String {
        if (providerKey.startsWith("custom:")) {
            val id = providerKey.removePrefix("custom:")
            return customEndpoints.get(id)?.defaultModel.orEmpty()
        }
        return runCatching { ApiKeyProvider.valueOf(providerKey).defaultModel }.getOrDefault("")
    }

    /** Phase U2 — all selectable (provider, model) pairs across both built-in
     *  providers and custom endpoints. Each entry is:
     *    - `providerKey`   — persistable id (enumName or `"custom:<id>"`)
     *    - `providerLabel` — human-readable provider label
     *    - `model`         — concrete model id from the live catalog
     *    - `kind`          — "builtin" or "custom"
     *  This wraps [AiApiManager.availableSpecsExpanded] so the picker shows
     *  the live `/models` catalog (cached 24h) rather than just the hard-coded
     *  default model per provider. */
    suspend fun availableModels(): List<AiApiManager.Quad> {
        val specs = runCatching { aiApiManagerLazy.get().availableSpecsExpanded() }
            .getOrElse { emptyList() }
        return specs.mapNotNull { spec ->
            when (spec) {
                is ProviderSpec.Builtin -> AiApiManager.Quad(
                    providerKey = spec.provider.name,
                    providerLabel = spec.provider.displayName,
                    model = spec.effectiveModel,
                    kind = "builtin",
                )
                is ProviderSpec.Custom -> AiApiManager.Quad(
                    providerKey = "custom:${spec.endpoint.id}",
                    providerLabel = spec.endpoint.name,
                    model = spec.effectiveModel,
                    kind = "custom",
                )
            }
        }
    }

    /** Build a [ProviderSpec] from a saved override pair, or null if neither
     *  the built-in enum nor the custom endpoint id resolves to a usable
     *  spec (key missing, endpoint deleted, enum renamed, etc.). */
    private fun resolveSpec(providerKey: String, model: String): ProviderSpec? =
        aiApiManagerLazy.get().resolveSpec(providerKey, model)

    /** Called from the agent's `channel_send` tool: if the model just sent
     *  the final reply itself to the same chat that started this turn, mark
     *  the route so [runAgentReply] doesn't auto-send the same text again. */
    fun suppressAutoReplyFor(routeKey: String) {
        suppressedAutoReplies.add(routeKey)
    }
    private fun consumeAutoReplySuppression(routeKey: String): Boolean =
        suppressedAutoReplies.remove(routeKey)

    fun remove(id: String) {
        scope.launch {
            stopChannel(id)
            repository.remove(id)
        }
    }

    suspend fun send(channelId: String, to: String, text: String): OutgoingResult {
        val ch = channels[channelId]
            ?: return OutgoingResult(false, "Channel not running: $channelId")
        val mode = ch.config.parseMode
        val result = if (mode.isNotBlank()) ch.sendFormatted(to, text, mode) else ch.send(to, text)
        // Mirror successful sends into the chat-history session log so that
        // messages pushed via the `channel_send` tool / SendByName / the UI
        // appear alongside incoming messages and auto-replies. Without this
        // step, agent-initiated outbound text was invisible in the in-app
        // session view even though Telegram delivered it.
        if (result.success) {
            recordOutgoingText(channelId, to, text)
            // Also append to per-chat conversation history so that on the
            // *next* inbound turn the agent's own prior tool-sent messages
            // are part of its context. Without this the agent appeared to
            // "forget" everything it sent via channel_send between turns.
            appendToHistory("$channelId:$to", role = "assistant", content = text)
        }
        return result
    }

    /** Send via a channel specified by display-name (case-insensitive). */
    suspend fun sendByName(displayName: String, to: String, text: String): OutgoingResult {
        val cfg = repository.all().firstOrNull { it.displayName.equals(displayName, ignoreCase = true) }
            ?: return OutgoingResult(false, "No channel named '$displayName'")
        return send(cfg.id, to, text)
    }

    /**
     * Append a message to the per-chat conversation history kept in
     * [chatHistories]. This ensures that tool-initiated outbound messages
     * (e.g. via `channel_send`) are visible to the agent in subsequent turns
     * as assistant messages, preventing the "agent forgot what it sent" issue.
     *
     * History is capped at 80 entries (same as the auto-reply pipeline) to
     * avoid unbounded memory growth.
     */
    private fun appendToHistory(historyKey: String, role: String, content: String) {
        val hist = chatHistories.getOrPut(historyKey) { ArrayDeque() }
        hist.addLast(ApiMessage(role = role, content = content))
        while (hist.size > 80) hist.removeFirst()
    }

    /**
     * Return the last [n] messages (both user and assistant) for a given
     * channel + chat pair, newest-last order, as a list of role→content pairs.
     * Returns an empty list when the chat has no recorded history yet.
     */
    fun getHistory(channelId: String, chatId: String, n: Int = 20): List<ApiMessage> {
        val key = "$channelId:$chatId"
        val hist = chatHistories[key] ?: return emptyList()
        return synchronized(hist) {
            val from = maxOf(0, hist.size - n)
            hist.subList(from, hist.size).toList()
        }
    }

    /**
     * Mirror an outbound text message into the per-chat session log so that
     * `channel_send` / `sendByName` show up in the chat history exactly the
     * same way the auto-reply path does. Without this, replies pushed by the
     * agent disappeared from the in-app session view (the user only saw them
     * land in Telegram).
     *
     * Only call after the underlying send succeeded — failed sends already
     * surface as agent errors elsewhere.
     */
    private fun recordOutgoingText(channelId: String, to: String, text: String) {
        val ch = channels[channelId] ?: return
        sessionStore.record(
            channelId = ch.config.id,
            channelType = ch.config.type,
            chatId = to,
            displayName = ch.config.displayName,
            event = SessionEvent(
                kind = SessionEvent.Kind.OutgoingText,
                content = text,
            ),
        )
        scope.launch {
            conversationIndex.indexMessage(
                com.forge.os.domain.memory.ConversationEntry(
                    channel = ch.config.type,
                    sender = "agent",
                    text = text,
                    timestamp = System.currentTimeMillis(),
                    sessionId = to
                )
            )
        }
    }

    /** Voice-note send used by the `telegram_send_voice` tool and the UI. */
    suspend fun sendVoice(channelId: String, to: String, audioPath: String, caption: String?): OutgoingResult {
        val ch = channels[channelId]
            ?: return OutgoingResult(false, "Channel not running: $channelId")
        val result = ch.sendVoice(to, audioPath, caption)
        if (result.success) {
            sessionStore.record(
                channelId = ch.config.id,
                channelType = ch.config.type,
                chatId = to,
                displayName = ch.config.displayName,
                event = SessionEvent(
                    kind = SessionEvent.Kind.OutgoingVoice,
                    content = "🎙 voice note (${audioPath})${caption?.let { " — $it" } ?: ""}",
                ),
            )
        }
        return result
    }

    suspend fun sendVoiceByName(displayName: String, to: String, audioPath: String, caption: String?): OutgoingResult {
        val cfg = repository.all().firstOrNull { it.displayName.equals(displayName, ignoreCase = true) }
            ?: return OutgoingResult(false, "No channel named '$displayName'")
        return sendVoice(cfg.id, to, audioPath, caption)
    }

    /** File/Photo/Video send used by the `telegram_send_file` tool and the UI. */
    suspend fun sendFile(channelId: String, to: String, path: String, caption: String?): OutgoingResult {
        val ch = channels[channelId]
            ?: return OutgoingResult(false, "Channel not running: $channelId")
        val result = ch.sendFile(to, path, caption)
        if (result.success) {
            sessionStore.record(
                channelId = ch.config.id,
                channelType = ch.config.type,
                chatId = to,
                displayName = ch.config.displayName,
                event = SessionEvent(
                    kind = SessionEvent.Kind.OutgoingAttachment,
                    content = "📎 file (${path})${caption?.let { " — $it" } ?: ""}",
                ),
            )
        }
        return result
    }

    suspend fun sendFileByName(displayName: String, to: String, path: String, caption: String?): OutgoingResult {
        val cfg = repository.all().firstOrNull { it.displayName.equals(displayName, ignoreCase = true) }
            ?: return OutgoingResult(false, "No channel named '$displayName'")
        return sendFile(cfg.id, to, path, caption)
    }

    suspend fun reactToMessage(channelId: String, to: String, messageId: Long, reaction: String): OutgoingResult {
        val ch = channels[channelId]
            ?: return OutgoingResult(false, "Channel not running: $channelId")
        return ch.reactToMessage(to, messageId, reaction)
    }

    suspend fun reactToMessageByName(displayName: String, to: String, messageId: Long, reaction: String): OutgoingResult {
        val cfg = repository.all().firstOrNull { it.displayName.equals(displayName, ignoreCase = true) }
            ?: return OutgoingResult(false, "No channel named '$displayName'")
        return reactToMessage(cfg.id, to, messageId, reaction)
    }

    suspend fun replyToMessage(channelId: String, to: String, replyToId: Long, text: String): OutgoingResult {
        val ch = channels[channelId]
            ?: return OutgoingResult(false, "Channel not running: $channelId")
        val result = ch.replyToMessage(to, replyToId, text, ch.config.parseMode)
        if (result.success) {
            sessionStore.record(
                channelId = ch.config.id,
                channelType = ch.config.type,
                chatId = to,
                displayName = ch.config.displayName,
                event = SessionEvent(
                    kind = SessionEvent.Kind.OutgoingText,
                    content = "↪️ (reply to $replyToId) $text",
                ),
            )
            // Append reply to per-chat history so the agent recalls it next turn.
            appendToHistory("$channelId:$to", role = "assistant", content = text)
        }
        return result
    }

    suspend fun replyToMessageByName(displayName: String, to: String, replyToId: Long, text: String): OutgoingResult {
        val cfg = repository.all().firstOrNull { it.displayName.equals(displayName, ignoreCase = true) }
            ?: return OutgoingResult(false, "No channel named '$displayName'")
        return replyToMessage(cfg.id, to, replyToId, text)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internals
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun startChannel(cfg: ChannelConfig) {
        ensureBrokerListener()
        if (channels.containsKey(cfg.id)) return
        val ch: Channel = when (cfg.type) {
            "telegram" -> TelegramChannel(cfg, context)
            else -> {
                Timber.w("ChannelManager: unknown channel type '${cfg.type}'")
                return
            }
        }
        channels[cfg.id] = ch
        runCatching { ch.start() }.onFailure {
            Timber.w(it, "ChannelManager: start failed for ${cfg.id}")
        }
        ingestJobs[cfg.id] = scope.launch {
            ch.incoming.collect { msg ->
                pushRecent(msg)
                runCatching {
                    memoryManager.logEvent(
                        role = "channel",
                        content = "[${msg.channelType}:${msg.fromName}] ${msg.text}",
                        tags = listOf("channel", msg.channelType, msg.channelId),
                    )
                }
                handleIncoming(ch, msg)
            }
        }
    }

    private suspend fun stopChannel(id: String) {
        channels[id]?.let { runCatching { it.stop() } }
        channels.remove(id)
        ingestJobs.remove(id)?.cancel()
        // Also cancel any in-flight reply jobs for this channel.
        replyJobs.keys
            .filter { it.startsWith("$id:") }
            .forEach { replyJobs.remove(it)?.cancel() }
    }

    private fun pushRecent(msg: IncomingMessage) {
        val current = _recent.value
        _recent.value = (listOf(msg) + current).take(100)
    }

    /**
     * Dispatch an incoming message to the live session log AND, if the
     * channel has `autoReply` enabled, append it to the per-chat queue.
     * The agent processes messages one at a time so a follow-up does NOT
     * cancel an in-progress turn (Phase T "latest wins" was surprising on
     * long-running tasks). If the agent is currently suspended waiting for
     * `request_user_input`, the message is delivered to the broker as the
     * answer instead of starting a new turn.
     */
    private fun handleIncoming(ch: Channel, msg: IncomingMessage) {
        val cfg = ch.config
        val sessionKey = "${cfg.id}:${msg.fromId}"

        // Enhanced Integration: Learn user communication patterns
        scope.launch {
            try {
                // Learn channel preferences
                userPreferencesManager.recordInteractionPattern("uses_${cfg.type}_channel", 1)
                
                // Learn communication timing patterns
                val hour = java.time.LocalDateTime.now().hour
                userPreferencesManager.recordInteractionPattern("active_hour_$hour", 1)
                
                // Learn message length preferences
                val messageLength = when {
                    msg.text.length < 50 -> "short_messages"
                    msg.text.length < 200 -> "medium_messages"
                    else -> "long_messages"
                }
                userPreferencesManager.recordInteractionPattern("prefers_$messageLength", 1)
                
                // Learn attachment usage patterns
                if (msg.attachmentKind != null) {
                    userPreferencesManager.recordInteractionPattern("uses_${msg.attachmentKind}_attachments", 1)
                }
                
            } catch (e: Exception) {
                Timber.w(e, "Failed to learn communication patterns")
            }
        }

        // 1. Mirror the incoming message into the session log.
        sessionStore.record(
            channelId = cfg.id, channelType = cfg.type,
            chatId = msg.fromId, displayName = msg.fromName,
            event = SessionEvent(
                kind = if (msg.attachmentKind == null) SessionEvent.Kind.IncomingText
                       else SessionEvent.Kind.IncomingAttachment,
                content = msg.text,
            ),
        )

        scope.launch {
            conversationIndex.indexMessage(
                com.forge.os.domain.memory.ConversationEntry(
                    channel = cfg.type,
                    sender = "user",
                    text = msg.text,
                    timestamp = System.currentTimeMillis(),
                    sessionId = msg.fromId
                )
            )
        }

        if (!cfg.autoReply) return

        // 2. If the agent is mid-run and asked for user input via the
        //    broker, deliver this message as the answer and return — do
        //    NOT start a parallel turn.
        val routeKey = brokerRoutesBySession.remove(sessionKey)
        if (routeKey != null && userInputBroker.isAwaiting(routeKey)) {
            scope.launch { userInputBroker.submitResponse(routeKey, msg.text) }
            return
        }

        // 3. Append to the per-chat FIFO and (re)start the drain worker
        //    for this session if one isn't already running.
        val q = synchronized(pendingInbound) {
            pendingInbound.getOrPut(sessionKey) { ArrayDeque() }.also { it.addLast(msg) }
        }
        val existing = replyJobs[sessionKey]
        if (existing == null || existing.isCompleted) {
            replyJobs[sessionKey] = scope.launch { drainQueue(ch, sessionKey) }
        }
    }

    /** Process queued messages for one chat one-by-one. */
    private suspend fun drainQueue(ch: Channel, sessionKey: String) {
        while (true) {
            val next = synchronized(pendingInbound) {
                pendingInbound[sessionKey]?.removeFirstOrNull().also {
                    if (pendingInbound[sessionKey]?.isEmpty() == true) {
                        pendingInbound.remove(sessionKey)
                    }
                }
            } ?: return
            try {
                runAgentReply(ch, next, sessionKey)
            } catch (e: Exception) {
                Timber.w(e, "ChannelManager: drainQueue error")
            }
        }
    }

    private suspend fun runAgentReply(ch: Channel, msg: IncomingMessage, sessionKey: String) {
        val cfg = ch.config
        val chatId = msg.fromId

        // Per-chat history (last 20 turns).
        val history = chatHistories.getOrPut(sessionKey) { ArrayDeque() }

        // Build the prompt the agent sees. We prefix attachment context so
        // the model knows how to react to a voice / photo / document.
        val prompt = buildString {
            if (msg.attachmentKind != null) {
                append("[Telegram message contains a ")
                append(msg.attachmentKind)
                msg.attachmentPath?.let { append(" saved to workspace at: ").append(it) }
                appendLine("]")
            }
            append(msg.text)
            appendLine()
            appendLine()
            appendLine("Reply concisely. The text of your final response is sent")
            appendLine("to the user automatically. You MAY also call `channel_send`")
            appendLine("explicitly to this same chat (channel_id=\"${cfg.id}\", to=\"$chatId\")")
            appendLine("if you want to push intermediate updates — Forge de-duplicates")
            appendLine("the final auto-reply when you do, so the user never sees the")
            appendLine("same message twice.")
            appendLine("Telegram chat id: $chatId. Channel id: ${cfg.id}.")
            msg.messageId?.let {
                appendLine("Incoming message_id: $it  (use this as reply_to_id in telegram_reply to quote the user's message).")
            }
            if (cfg.workspacePath.isNotBlank()) {
                appendLine("WORKSPACE RESTRICTION: You may only read/write files under 'workspace/${cfg.workspacePath}/'. Do not access files outside this path.")
            }
            if (cfg.systemPromptSuffix.isNotBlank()) {
                appendLine()
                appendLine(cfg.systemPromptSuffix)
            }
            if (cfg.parseMode.equals("HTML", ignoreCase = true)) {
                appendLine("You may use Markdown — it is converted to Telegram HTML.")
            }
        }

        // Periodically renew the "typing…" indicator while the agent works.
        val typingPulse = scope.launch {
            while (isActive) {
                runCatching { ch.sendChatAction(chatId, "typing") }
                delay(4_500)
            }
        }
        sessionStore.record(
            channelId = cfg.id, channelType = cfg.type,
            chatId = chatId, displayName = msg.fromName,
            event = SessionEvent(kind = SessionEvent.Kind.ChatAction, content = "typing…"),
        )

        // Tag this agent run with a unique input route so any
        // `request_user_input` call inside it is delivered to THIS Telegram
        // chat instead of the on-screen chat UI (the broker reads the
        // [InputRoute] coroutine context element).
        val routeKey = InputRoute.forChannel(cfg.id, chatId)

        // Phase U / U2 — pick the spec for THIS turn. Per-session override
        // wins, then per-channel override, then null (global auto-route +
        // fallback). resolveSpec() handles BOTH built-in providers and
        // custom endpoints (encoded as "custom:<id>").
        val spec: ProviderSpec? = run {
            val so = sessionStoreOverrides.get(cfg.id, chatId)
            val pName = so?.provider?.takeIf { it.isNotBlank() } ?: cfg.provider
            val pModel = so?.model?.takeIf { it.isNotBlank() } ?: cfg.model
            resolveSpec(pName, pModel)
        }

        // Phase U2 — bot-control fallback chain. Try the chosen
        // session/channel spec FIRST; only fall back to the default
        // auto-route if that explicit spec actually fails (auth, network,
        // model gone, etc.).
        suspend fun runOnce(activeSpec: ProviderSpec?, onText: (String) -> Unit) {
            withContext(InputRoute(routeKey)) {
                reActAgentLazy.get().run(
                    userMessage = prompt,
                    history = history.toList(),
                    spec = activeSpec,
                    currentChannel = cfg.type
                ).collect { event ->
                    when (event) {
                        is AgentEvent.Thinking -> sessionStore.record(
                            channelId = cfg.id, channelType = cfg.type,
                            chatId = chatId, displayName = msg.fromName,
                            event = SessionEvent(kind = SessionEvent.Kind.Thinking, content = event.text),
                        )
                        is AgentEvent.ToolCall -> sessionStore.record(
                            channelId = cfg.id, channelType = cfg.type,
                            chatId = chatId, displayName = msg.fromName,
                            event = SessionEvent(
                                kind = SessionEvent.Kind.ToolCall,
                                content = event.args, toolName = event.name,
                            ),
                        )
                        is AgentEvent.ToolResult -> sessionStore.record(
                            channelId = cfg.id, channelType = cfg.type,
                            chatId = chatId, displayName = msg.fromName,
                            event = SessionEvent(
                                kind = SessionEvent.Kind.ToolResult,
                                content = event.result.take(2_000),
                                toolName = event.name, isError = event.isError,
                            ),
                        )
                        is AgentEvent.Response -> onText(event.text)
                        is AgentEvent.Error -> sessionStore.record(
                            channelId = cfg.id, channelType = cfg.type,
                            chatId = chatId, displayName = msg.fromName,
                            event = SessionEvent(
                                kind = SessionEvent.Kind.AgentError,
                                content = event.message, isError = true,
                            ),
                        )
                        is AgentEvent.CostApprovalRequired -> {
                            // In channels, we just log it for now. Future: send an interactive approval button.
                            sessionStore.record(
                                channelId = cfg.id, channelType = cfg.type,
                                chatId = chatId, displayName = msg.fromName,
                                event = SessionEvent(
                                    kind = SessionEvent.Kind.Info,
                                    content = "💰 Cost approval required for turn. Auto-approving for channel (Eco-mode active).",
                                ),
                            )
                        }
                        AgentEvent.Done -> { /* handled below */ }
                    }
                }
            }
        }

        var finalText = ""
        try {
            try {
                runOnce(spec) { finalText = it }
            } catch (primary: Exception) {
                if (spec == null) throw primary
                Timber.w(primary, "ChannelManager: chosen model failed, falling back to default route")
                sessionStore.record(
                    channelId = cfg.id, channelType = cfg.type,
                    chatId = chatId, displayName = msg.fromName,
                    event = SessionEvent(
                        kind = SessionEvent.Kind.Info,
                        content = "Chosen model unavailable (${primary.message ?: "error"}). " +
                            "Retrying with default route…",
                    ),
                )
                finalText = ""
                runOnce(null) { finalText = it }
            }
        } catch (e: ApiCallException) {
            sessionStore.record(
                channelId = cfg.id, channelType = cfg.type,
                chatId = chatId, displayName = msg.fromName,
                event = SessionEvent(
                    kind = SessionEvent.Kind.AgentError,
                    content = e.error.userFacing(), isError = true,
                ),
            )
            finalText = "⚠️ ${e.error.userFacing()}"
        } catch (e: Exception) {
            Timber.w(e, "ChannelManager: agent loop crashed")
            sessionStore.record(
                channelId = cfg.id, channelType = cfg.type,
                chatId = chatId, displayName = msg.fromName,
                event = SessionEvent(
                    kind = SessionEvent.Kind.AgentError,
                    content = e.message ?: "agent crashed", isError = true,
                ),
            )
            finalText = "⚠️ Agent error: ${e.message ?: "unknown"}"
        } finally {
            typingPulse.cancel()
            // If the broker was waiting on us when the run ended (e.g. the
            // user never answered before another event killed the run),
            // clear the marker so the next inbound message starts a new turn.
            brokerRoutesBySession.remove(sessionKey)
        }

        // Phase U2 — if the agent already delivered the reply by calling
        // `channel_send` to its own chat, suppress the auto-send below to
        // avoid the duplicate-message bug.
        val alreadyDeliveredByAgent = consumeAutoReplySuppression(routeKey)

        // Make sure the user always sees *something*. If the agent finished
        // without any visible text (model returned empty, or only emitted
        // tool calls), synthesise a tiny acknowledgement so the chat
        // doesn't appear silently broken — UNLESS it already sent the
        // reply itself via channel_send.
        if (finalText.isBlank() && !alreadyDeliveredByAgent) {
            finalText = "(Forge finished but produced no reply text — check the in-app session for tool/agent errors)"
        }

        if (alreadyDeliveredByAgent) {
            // Agent did its own delivery; just refresh history and return.
            history.addLast(ApiMessage(role = "user", content = msg.text))
            history.addLast(ApiMessage(role = "assistant", content = finalText.ifBlank { "(reply sent via channel_send)" }))
            while (history.size > MAX_HISTORY) history.removeFirst()
            if (replyJobs[sessionKey]?.isCompleted == true) replyJobs.remove(sessionKey)
            return
        }

        // Send the final reply.
        run {
            val mode = cfg.parseMode
            val sendResult = if (mode.isNotBlank())
                ch.sendFormatted(chatId, finalText, mode)
            else
                ch.send(chatId, finalText)
            sessionStore.record(
                channelId = cfg.id, channelType = cfg.type,
                chatId = chatId, displayName = msg.fromName,
                event = SessionEvent(
                    kind = SessionEvent.Kind.OutgoingText,
                    content = if (sendResult.success) finalText
                              else "$finalText\n\n[send failed: ${sendResult.detail}]",
                    isError = !sendResult.success,
                ),
            )

            // If the channel itself rejected the message (Telegram bot token
            // bad, parse-mode error after retry, network blip), make a
            // best-effort follow-up so the user gets *some* signal in the
            // chat instead of complete silence. Plain-text + chunked so it
            // doesn't trip the same parse error twice.
            if (!sendResult.success) {
                val notice = "⚠️ Forge tried to reply but the message couldn't be delivered:\n${sendResult.detail.take(300)}"
                runCatching { ch.send(chatId, notice) }
            }

            // Update per-chat history for the next turn.
            history.addLast(ApiMessage(role = "user", content = msg.text))
            history.addLast(ApiMessage(role = "assistant", content = finalText))
            while (history.size > MAX_HISTORY) history.removeFirst()
        }

        // Tidy: remove the job pointer if it's still us (the chat may have
        // received another message in the meantime that replaced it).
        if (replyJobs[sessionKey]?.isCompleted == true) replyJobs.remove(sessionKey)
    }

    companion object {
        private const val MAX_HISTORY = 20
    }
}

private fun String.jsonEscape(): String =
    this.replace("\\", "\\\\").replace("\"", "\\\"")
