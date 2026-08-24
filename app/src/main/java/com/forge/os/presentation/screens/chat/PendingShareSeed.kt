package com.forge.os.presentation.screens.chat

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-singleton handoff for the "Share → Forge OS" flow.
 * [com.forge.os.presentation.ShareReceiverActivity] copies the shared
 * text/files into the workspace and calls [set] before opening the chat;
 * [ModernChatScreen] consumes it once on first composition to auto-send it.
 */
object PendingShareSeed {
    private val ref = AtomicReference<String?>(null)

    fun set(seed: String?) {
        if (seed.isNullOrBlank()) return
        ref.set(seed)
    }

    /** Returns the pending seed, or null. Single-shot — clears after read. */
    fun consume(): String? = ref.getAndSet(null)
}
