package com.forge.os.domain.sandbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

data class DebuggerState(
    val isPaused: Boolean = false,
    val localVariables: Map<String, String> = emptyMap(),
)

@Singleton
class DebuggerRelay @Inject constructor() {
    
    companion object {
        @JvmStatic
        var instance: DebuggerRelay? = null
    }

    init {
        instance = this
    }

    private val _state = MutableStateFlow(DebuggerState())
    val state: StateFlow<DebuggerState> = _state

    private var resumeDeferred: CompletableDeferred<Map<String, String>>? = null

    /**
     * Called from the Python thread by Chaquopy.
     * Blocks the Python thread until the user clicks Resume in the UI.
     */
    fun waitForUser(localVarsJson: Map<String, String>): Map<String, String> {
        val deferred = CompletableDeferred<Map<String, String>>()
        this.resumeDeferred = deferred
        _state.value = DebuggerState(isPaused = true, localVariables = localVarsJson)

        // Block the calling thread (Python's background thread) until deferred is completed
        return runBlocking {
            deferred.await()
        }
    }

    /**
     * Called from the UI when the user clicks Resume.
     */
    fun resume(modifiedVars: Map<String, String>) {
        _state.value = DebuggerState(isPaused = false)
        resumeDeferred?.complete(modifiedVars)
        resumeDeferred = null
    }
}
