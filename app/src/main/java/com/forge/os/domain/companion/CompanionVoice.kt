package com.forge.os.domain.companion

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase P-4 — minimal, opt-in TTS wrapper around Android's built-in
 * [TextToSpeech]. The companion screen calls [speak] for each assistant reply
 * iff `friendMode.voiceEnabled` is on.
 *
 * SpeechRecognizer (mic input) is intentionally **not** wired here — it
 * needs runtime `RECORD_AUDIO` permission and a dedicated UI flow; deferred
 * to a follow-up. The settings toggle still gates that future plumbing.
 */
@Singleton
class CompanionVoice @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready: Boolean = false

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                runCatching { tts?.language = Locale.getDefault() }
            } else {
                Timber.w("CompanionVoice: TTS init failed status=$status")
            }
        }
    }

    /** Speak [text] if TTS is available; silently no-op otherwise. */
    fun speak(text: String) {
        if (text.isBlank()) return
        try {
            ensure()
            // best-effort even if init hasn't completed yet — TTS will queue
            tts?.speak(text.take(800), TextToSpeech.QUEUE_FLUSH, null, "companion-${text.hashCode()}")
        } catch (e: Exception) {
            Timber.w(e, "CompanionVoice.speak failed")
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}
