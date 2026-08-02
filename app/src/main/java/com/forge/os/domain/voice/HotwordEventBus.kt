package com.forge.os.domain.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton event bus that bridges the [HotwordDetectionService] (background)
 * and the UI (MainActivity). When the wake word is detected, the service
 * emits a [HotwordEvent] here; MainActivity collects it and shows the
 * [HotwordActivationOverlay].
 */
@Singleton
class HotwordEventBus @Inject constructor() {

    data class HotwordEvent(
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val _hotwordEvent = MutableStateFlow<HotwordEvent?>(null)
    val hotwordEvent: StateFlow<HotwordEvent?> = _hotwordEvent

    /** Called by the detection service when the wake word is heard. */
    fun emit() {
        Timber.d("HotwordEventBus: wake word detected")
        _hotwordEvent.value = HotwordEvent()
    }

    /** Called by the UI after the overlay is shown (prevents re-trigger). */
    fun consume() {
        _hotwordEvent.value = null
    }
}
