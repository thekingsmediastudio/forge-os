package com.forge.os.presentation.screens.chat

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-singleton handoff for the "Use in Chat" flow from the Recipes screen.
 * [RecipesScreen] calls [set] before navigating to chat; the chat screen
 * consumes it once on first composition to prefill the input field.
 */
object PendingRecipeSeed {
    private val ref = AtomicReference<String?>(null)

    fun set(seed: String?) {
        if (seed.isNullOrBlank()) return
        ref.set(seed)
    }

    /** Returns the pending seed, or null. Single-shot — clears after read. */
    fun consume(): String? = ref.getAndSet(null)
}
