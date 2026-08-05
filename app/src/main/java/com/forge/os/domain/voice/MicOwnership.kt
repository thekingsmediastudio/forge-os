package com.forge.os.domain.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for which component currently owns the microphone.
 *
 * Both the always-on hotword service ([com.forge.os.service.HotwordDetectionService])
 * and voice mode ([VoiceInputManager]) want the mic. Android's SpeechRecognizer
 * throws ERROR_CLIENT (5) / ERROR_RECOGNIZER_BUSY (8) when two recognizers race,
 * which previously killed voice sessions. Components publish ownership here and
 * listeners react via [owner], so the handoff is reactive instead of polled.
 */
object MicOwnership {
    enum class Owner { NONE, HOTWORD, VOICE_MODE }

    private val _owner = MutableStateFlow(Owner.NONE)
    val owner: StateFlow<Owner> = _owner.asStateFlow()

    fun claim(newOwner: Owner) {
        _owner.value = newOwner
    }

    /** Release only if we currently hold it — avoids clobbering another owner. */
    fun release(expected: Owner) {
        if (_owner.value == expected) _owner.value = Owner.NONE
    }
}
