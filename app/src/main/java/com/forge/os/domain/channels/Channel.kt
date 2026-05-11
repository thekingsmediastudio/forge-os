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
    val autoReply: Boolean = true,
    val parseMode: String = "HTML",
    val allowedChatIds: String = "",

    // ── Phase U (per-channel model picker) ───────────────────────────────
    val provider: String = "",
    val model: String = "",

    // ── Channel purpose / workspace scoping ──────────────────────────────
    /** Optional system prompt suffix injected for this channel only.
     *  Use this to give a channel a specific purpose, persona, or set of
     *  instructions (e.g. "You are a customer support agent for X. Only
     *  answer questions about X. Do not discuss other topics."). */
    val systemPromptSuffix: String = "",

    /** Optional workspace sub-path this channel's agent is restricted to.
     *  Empty = full workspace access (default).
     *  Example: "projects/customer-support" restricts the agent to only
     *  read/write files under workspace/projects/customer-support/.
     *  The agent cannot access files outside this path. */
    val workspacePath: String = "",

    // ── Channel purpose ───────────────────────────────────────────────────
    /** Describes what this channel is for. One of:
     *   "personal"  — the user's own private channel (default)
     *   "teaching"  — a channel for teaching, tutoring, or Q&A
     *   "work"      — a work or team channel
     *   "support"   — customer support / help desk
     *   "custom"    — fully custom (governed by systemPromptSuffix)
     *  The purpose is used to pre-fill a sensible systemPromptSuffix when
     *  the channel is created, and shown as a badge in the UI. */
    val purpose: String = "personal",
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
