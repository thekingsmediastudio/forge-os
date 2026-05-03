package com.forge.os.domain.channels

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class ChannelConfig(
    val id: String,
    val type: String,           // "telegram" | ...
    val displayName: String,
    val enabled: Boolean = true,
    val configJson: String = "{}",   // channel-specific settings
    val createdAt: Long = System.currentTimeMillis(),

    // ── Phase T (auto-reply pipeline) ────────────────────────────────────
    /** When true, every incoming message is fed to the ReAct agent and the
     *  final response is sent back to the chat. Default: true. */
    val autoReply: Boolean = true,

    /** Telegram parse_mode for outbound text. One of:
     *   "HTML"        — recommended (LLM markdown is converted to HTML).
     *   "MarkdownV2"  — strict, requires escaping; passed through verbatim.
     *   "Markdown"    — legacy.
     *   ""            — plain text, no parsing. */
    val parseMode: String = "HTML",

    /** Optional comma-separated allow-list of `chat_id`s. Empty means
     *  every chat that messages the bot is allowed. Useful as a poor
     *  man's auth — only your own chat will get replies. */
    val allowedChatIds: String = "",

    // ── Phase U (per-channel model picker) ───────────────────────────────
    /** Per-channel provider override (e.g. "ANTHROPIC", "OPENAI", "GROQ").
     *  Empty/blank means "use the global default + fallback chain". */
    val provider: String = "",
    /** Per-channel model id override (e.g. "claude-sonnet-4-20250514").
     *  Honoured only when [provider] is also set. */
    val model: String = "",
)

@Serializable
data class IncomingMessage(
    val channelId: String,
    val channelType: String,
    val fromName: String,
    val fromId: String,           // chat id (where to reply)
    val text: String,
    val receivedAt: Long = System.currentTimeMillis(),

    // ── Phase T (rich attachments + reply tracking) ──────────────────────
    /** Provider-side message id (Telegram update.message.message_id). */
    val messageId: Long? = null,
    /** "voice" / "audio" / "photo" / "document" / "video" / null. */
    val attachmentKind: String? = null,
    /** Workspace-relative path the attachment was downloaded to, or null. */
    val attachmentPath: String? = null,
    /** Telegram caption (for photo/voice/etc.) or null. */
    val caption: String? = null,
)

@Serializable
data class OutgoingResult(
    val success: Boolean,
    val detail: String = "",
)

/**
 * Multi-channel messaging abstraction. Each adapter (Telegram, Discord, SMS,
 * ...) implements this interface. The [ChannelManager] owns lifecycle
 * (start/stop) and multiplexes [incoming] across all enabled channels.
 */
interface Channel {
    val config: ChannelConfig

    /** Best-effort: start polling / connect. */
    suspend fun start()

    /** Stop polling / disconnect. */
    suspend fun stop()

    /** Hot flow of incoming messages while this channel is active. */
    val incoming: Flow<IncomingMessage>

    /** Send an outbound message. `to` is channel-specific (chat id, user id, ...). */
    suspend fun send(to: String, text: String): OutgoingResult

    /** True if the adapter is actively polling / connected. */
    val isRunning: Boolean

    /** Send a formatted message using a provider-specific parse mode (e.g.
     *  "HTML", "MarkdownV2"). Adapters that don't support formatting fall
     *  back to plain [send]. */
    suspend fun sendFormatted(to: String, text: String, parseMode: String): OutgoingResult =
        send(to, text)

    /** Show a transient "the agent is working" indicator on the chat (e.g.
     *  Telegram's `sendChatAction`). `action` is provider-specific:
     *  "typing", "record_voice", "upload_voice", "upload_document". No-op
     *  on adapters that don't support it. */
    suspend fun sendChatAction(to: String, action: String): Unit = Unit

    /** Send a voice note (uploaded as an OGG/Opus file, with optional
     *  caption). `audioPath` is an absolute path on the device.
     *  Adapters that don't support voice return `success = false`. */
    suspend fun sendVoice(to: String, audioPath: String, caption: String? = null): OutgoingResult =
        OutgoingResult(false, "voice not supported by this channel")

    /** Send a file, photo, or video. [path] is relative to the workspace or absolute.
     *  Adapters that don't support file transfers return `success = false`. */
    suspend fun sendFile(to: String, path: String, caption: String? = null): OutgoingResult =
        OutgoingResult(false, "file transfer not supported by this channel")

    /** Add a reaction (emoji) to a specific message. `reaction` is provider-specific.
     *  For Telegram, use standard emojis or premium custom emoji ids. */
    suspend fun reactToMessage(to: String, messageId: Long, reaction: String): OutgoingResult =
        OutgoingResult(false, "reactions not supported by this channel")

    /** Reply to a specific message ID with text. */
    suspend fun replyToMessage(to: String, replyToId: Long, text: String, parseMode: String = "HTML"): OutgoingResult =
        OutgoingResult(false, "replies not supported by this channel")
}
