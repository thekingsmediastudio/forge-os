package com.forge.os.domain.voice

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import com.forge.os.R
import com.forge.os.presentation.screens.voice.VoiceModeActivity
import timber.log.Timber
import java.util.*
import javax.inject.Inject
import dagger.hilt.android.AndroidEntryPoint

/**
 * The Background Vanguard: A persistent guardian service that listens for 
 * "Hey Forge" across the entire OS. When detected, it triggers a high-fidelity 
 * haptic handshake and launches the full Voice Mode.
 */
@AndroidEntryPoint
class BackgroundVoiceService : Service() {

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognitionIntent: Intent? = null
    
    companion object {
        private const val NOTIFICATION_ID = 888
        private const val CHANNEL_ID = "forge_vanguard"
        
        fun start(context: Context) {
            val intent = Intent(context, BackgroundVoiceService::class.java)
            context.startForegroundService(intent)
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, BackgroundVoiceService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        initRecognizer()
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Timber.e("Speech recognition not available")
            stopSelf()
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(VanguardListener())
        }

        recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        startListening()
    }

    private fun startListening() {
        try {
            speechRecognizer?.startListening(recognitionIntent)
            Timber.d("Vanguard is listening for 'Hey Forge'...")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start listening")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Forge Vanguard", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active listening for 'Hey Forge' wake word"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Forge OS Vanguard")
            .setContentText("Listening for 'Hey Forge'...")
            .setSmallIcon(R.drawable.ic_voice_mic) // Ensure this exists or use a generic mic icon
            .setOngoing(true)
            .build()
    }

    inner class VanguardListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            // Restart listening loop
            startListening()
        }

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                startListening()
            } else {
                Timber.w("Recognizer error: $error. Re-initializing...")
                initRecognizer()
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (matches != null) {
                for (match in matches) {
                    if (match.lowercase().contains("forge")) {
                        onWakeWordDetected()
                        return
                    }
                }
            }
            startListening()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (matches != null) {
                for (match in matches) {
                    if (match.lowercase().contains("forge") || match.lowercase().contains("hey forge")) {
                        onWakeWordDetected()
                        return
                    }
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun onWakeWordDetected() {
        Timber.i("WAKE WORD DETECTED: Summoning Forge Agent")
        
        // 1. Haptic Handshake
        val vibrator = getSystemService(android.os.Vibrator::class.java)
        vibrator?.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_HEAVY_CLICK))

        // 2. Launch Voice Mode Activity with "auto-record" intent
        val intent = Intent(this, VoiceModeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("WAKE_WORD_TRIGGERED", true)
        }
        startActivity(intent)
        
        // Stop listening while VoiceModeActivity is active to avoid mic conflicts
        speechRecognizer?.stopListening()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}
