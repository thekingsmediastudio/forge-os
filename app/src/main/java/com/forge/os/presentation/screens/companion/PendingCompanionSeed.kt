package com.forge.os.presentation.screens.companion

import java.util.concurrent.atomic.AtomicReference

/**
 * Phase L — process-singleton handoff for the "tap notification to open
 * Companion with this prompt" flow. [MainActivity] reads the intent extras and
 * calls [set]; [CompanionScreen] consumes it once on first composition.
 *
 * Kept intentionally tiny so we don't have to thread a nav arg through every
 * route — the seed is already half-baked content the user is about to edit.
 */
object PendingCompanionSeed {
    private val ref = AtomicReference<String?>(null)

    fun set(seed: String?) {
        if (seed.isNullOrBlank()) return
        ref.set(seed)
    }

    /** Returns the pending seed, or null. Single-shot — clears after read. */
    fun consume(): String? = ref.getAndSet(null)
}
