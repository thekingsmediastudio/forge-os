package com.forge.os.data.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the "browser reveal" flow between the agent (background tool
 * call) and the UI (Compose overlay). When the agent calls `browser_reveal`,
 * this manager emits a [BrowserRevealRequest] that MainActivity collects to
 * show the [AgentBrowserRevealOverlay] with the headless WebView attached.
 *
 * The overlay is dismissed when the user taps "Done", at which point the
 * WebView is detached and returned to headless mode.
 */
@Singleton
class BrowserRevealManager @Inject constructor() {

    data class BrowserRevealRequest(
        val message: String,
        val url: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val _revealRequest = MutableStateFlow<BrowserRevealRequest?>(null)
    val revealRequest: StateFlow<BrowserRevealRequest?> = _revealRequest

    /** Signal that completes when the user dismisses the overlay. */
    private var dismissSignal: CompletableDeferred<Unit>? = null

    /** Called by the agent tool to request a browser reveal. */
    fun requestReveal(message: String, url: String) {
        Timber.d("BrowserRevealManager: reveal requested for $url — $message")
        _revealRequest.value = BrowserRevealRequest(message, url)
    }

    /**
     * Suspend until the user dismisses the overlay. Called by the agent tool
     * to block until the user taps "Done".
     */
    suspend fun awaitDismiss() {
        dismissSignal = CompletableDeferred()
        Timber.d("BrowserRevealManager: waiting for user to dismiss overlay")
        dismissSignal?.await()
        Timber.d("BrowserRevealManager: user dismissed overlay, resuming")
    }

    /** Called by the UI when the user dismisses the overlay. */
    fun dismiss() {
        Timber.d("BrowserRevealManager: reveal dismissed")
        _revealRequest.value = null
        dismissSignal?.complete(Unit)
        dismissSignal = null
    }

    /** Whether the overlay is currently showing. */
    val isRevealed: Boolean get() = _revealRequest.value != null
}
