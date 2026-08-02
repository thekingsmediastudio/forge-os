package com.forge.os.domain.missingmode

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles automatic responses to incoming calls when Missing Mode is enabled.
 *
 * Supports:
 * - SMS auto-reply (reject call + send SMS)
 * - TTS answer (answer call + play TTS message)
 * - Both (answer with TTS, then send SMS)
 */
@Singleton
class AutoResponder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val missingModeManager: MissingModeManager,
) {
    companion object {
        private const val TAG = "AutoResponder"
        private const val TTS_DELAY_MS = 1500L
        private const val CALL_END_DELAY_MS = 8000L
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false

    /**
     * Handle an incoming call from a trusted contact.
     */
    fun handleTrustedCall(phoneNumber: String) {
        val state = missingModeManager.state.value

        when (state.responseType) {
            MissingModeManager.RESPONSE_SMS -> {
                rejectCallAndSendSms(phoneNumber)
            }
            MissingModeManager.RESPONSE_TTS -> {
                answerCallWithTts(phoneNumber)
            }
            MissingModeManager.RESPONSE_BOTH -> {
                answerCallWithTts(phoneNumber)
                // SMS will be sent after call ends
                scope.launch {
                    delay(CALL_END_DELAY_MS)
                    missingModeManager.sendAutoReplySms(phoneNumber)
                }
            }
        }

        missingModeManager.onCallHandled(phoneNumber)
    }

    /**
     * Reject the call and send an SMS auto-reply.
     */
    private fun rejectCallAndSendSms(phoneNumber: String) {
        scope.launch {
            try {
                // Reject the call
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                telecomManager.endCall()
                Log.i(TAG, "Call rejected for $phoneNumber")

                // Small delay before sending SMS
                delay(500)

                // Send SMS
                val success = missingModeManager.sendAutoReplySms(phoneNumber)
                if (success) {
                    Log.i(TAG, "Auto-reply SMS sent to $phoneNumber")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied for call rejection", e)
                // Fallback: just send SMS without rejecting
                missingModeManager.sendAutoReplySms(phoneNumber)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reject call", e)
            }
        }
    }

    /**
     * Answer the call and play a TTS message.
     */
    private fun answerCallWithTts(phoneNumber: String) {
        scope.launch {
            try {
                // Answer the call
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                telecomManager.answerRingingCall()
                Log.i(TAG, "Call answered for $phoneNumber")

                // Wait for call to connect
                delay(TTS_DELAY_MS)

                // Play TTS message
                playTtsMessage(missingModeManager.state.value.ttsMessage)

                // End call after message
                delay(CALL_END_DELAY_MS)
                try {
                    telecomManager.endCall()
                    Log.i(TAG, "Call ended after TTS")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not end call automatically", e)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied for call answering", e)
                // Fallback: send SMS instead
                missingModeManager.sendAutoReplySms(phoneNumber)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to answer call", e)
            }
        }
    }

    /**
     * Play a TTS message over the phone call.
     */
    private fun playTtsMessage(message: String) {
        // Set audio to speakerphone for TTS to be heard
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val wasSpeakerphoneOn = audioManager.isSpeakerphoneOn
        audioManager.isSpeakerphoneOn = true

        // Initialize TTS if needed
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsInitialized = true
                    tts?.setSpeechRate(0.9f)
                    speakMessage(message)
                } else {
                    Log.e(TAG, "TTS initialization failed")
                }
            }
        } else if (ttsInitialized) {
            speakMessage(message)
        }
    }

    private fun speakMessage(message: String) {
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "missing_mode_tts")
        Log.i(TAG, "TTS playing: $message")
    }

    /**
     * Manually trigger a response (for testing).
     */
    fun triggerManualResponse(phoneNumber: String) {
        handleTrustedCall(phoneNumber)
    }

    /**
     * Clean up TTS resources.
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsInitialized = false
    }
}
