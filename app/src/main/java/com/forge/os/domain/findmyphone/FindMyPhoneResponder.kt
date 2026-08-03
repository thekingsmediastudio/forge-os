package com.forge.os.domain.findmyphone

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
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
 * Handles automatic responses to incoming calls when Find My Phone is enabled.
 *
 * Supports:
 * - RING_LOUD: Answer call + play max-volume ringtone
 * - SPEAK_LOCATION: Answer call + speak TTS message
 * - FLASH_LED: Flash camera LED repeatedly
 * - ALL: All of the above
 */
@Singleton
class FindMyPhoneResponder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val findMyPhoneManager: FindMyPhoneManager,
) {
    companion object {
        private const val TAG = "FindMyPhoneResponder"
        private const val TTS_DELAY_MS = 1500L
        private const val FLASH_INTERVAL_MS = 500L
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false
    private var isFlashing = false

    /**
     * Handle an incoming call when Find My Phone is enabled.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun handleIncomingCall(phoneNumber: String) {
        val state = findMyPhoneManager.state.value
        val durationMs = state.durationSeconds * 1000L

        scope.launch {
            try {
                // Answer the call (requires API 26+)
                val telecomManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                    answerCallIfPossible(tm, phoneNumber)
                    tm
                } else {
                    Log.w(TAG, "answerRingingCall requires API 26+, current: ${Build.VERSION.SDK_INT}")
                    null
                }

                // Wait for call to connect
                delay(TTS_DELAY_MS)

                // Trigger responses based on type
                when (state.responseType) {
                    FindMyPhoneManager.RESPONSE_RING_LOUD -> {
                        playLoudRingtone(durationMs)
                    }
                    FindMyPhoneManager.RESPONSE_SPEAK_LOCATION -> {
                        speakTtsMessage(state.ttsMessage, durationMs)
                    }
                    FindMyPhoneManager.RESPONSE_FLASH_LED -> {
                        flashCameraLed(durationMs)
                    }
                    FindMyPhoneManager.RESPONSE_ALL -> {
                        // Do all three in parallel
                        launch { playLoudRingtone(durationMs) }
                        launch { speakTtsMessage(state.ttsMessage, durationMs) }
                        launch { flashCameraLed(durationMs) }
                    }
                }

                // End call after duration
                delay(durationMs)
                if (telecomManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        telecomManager.endCall()
                        Log.i(TAG, "Call ended after Find My Phone response")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not end call automatically", e)
                    }
                }

                // Stop flashing if still active
                isFlashing = false
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied for call answering", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle Find My Phone call", e)
            }
        }

        findMyPhoneManager.onCallHandled(phoneNumber)
    }

    /**
     * Play the default ringtone at maximum volume.
     */
    private suspend fun playLoudRingtone(durationMs: Long) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // Set volume to max
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVolume, 0)
            
            // Set speakerphone on
            audioManager.isSpeakerphoneOn = true

            // Play default ringtone
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = RingtoneManager.getRingtone(context, ringtoneUri)
            ringtone.play()
            
            Log.i(TAG, "Playing loud ringtone for ${durationMs}ms")
            
            // Stop after duration
            delay(durationMs)
            ringtone.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play loud ringtone", e)
        }
    }

    /**
     * Speak a TTS message repeatedly for the duration.
     */
    private suspend fun speakTtsMessage(message: String, durationMs: Long) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.isSpeakerphoneOn = true

            // Initialize TTS if needed
            if (tts == null) {
                val latch = kotlinx.coroutines.CompletableDeferred<Boolean>()
                tts = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ttsInitialized = true
                        tts?.setSpeechRate(0.9f)
                        latch.complete(true)
                    } else {
                        Log.e(TAG, "TTS initialization failed")
                        latch.complete(false)
                    }
                }
                if (!latch.await()) return
            }

            if (!ttsInitialized) return

            // Speak message repeatedly
            val endTime = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < endTime) {
                tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "find_my_phone_tts")
                Log.i(TAG, "TTS playing: $message")
                delay(3000) // Wait 3s between repeats
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to speak TTS message", e)
        }
    }

    /**
     * Flash the camera LED repeatedly.
     */
    private suspend fun flashCameraLed(durationMs: Long) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return

            isFlashing = true
            val endTime = System.currentTimeMillis() + durationMs

            Log.i(TAG, "Flashing camera LED for ${durationMs}ms")

            while (System.currentTimeMillis() < endTime && isFlashing) {
                cameraManager.setTorchMode(cameraId, true)
                delay(FLASH_INTERVAL_MS)
                cameraManager.setTorchMode(cameraId, false)
                delay(FLASH_INTERVAL_MS)
            }

            // Ensure torch is off
            cameraManager.setTorchMode(cameraId, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flash camera LED", e)
        }
    }

    /**
     * Manually trigger a response (for testing).
     */
    fun triggerManualResponse() {
        handleIncomingCall("TEST")
    }

    /**
     * Clean up TTS resources.
     */
    fun shutdown() {
        isFlashing = false
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsInitialized = false
    }

    @SuppressLint("MissingPermission")
    private fun answerCallIfPossible(tm: TelecomManager, phoneNumber: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            tm.answerRingingCall()
            Log.i(TAG, "Call answered for Find My Phone from $phoneNumber")
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing ANSWER_PHONE_CALLS permission", e)
        }
    }
}
