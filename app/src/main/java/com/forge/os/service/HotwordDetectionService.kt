package com.forge.os.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.forge.os.R
import com.forge.os.domain.voice.HotwordEventBus
import com.forge.os.domain.voice.VoiceActivityDetector
import com.forge.os.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that listens for the "Hello Forge" wake word using
 * Voice Activity Detection (VAD) + Android SpeechRecognizer.
 *
 * How it works:
 * 1. VAD continuously monitors the mic for speech (energy-based)
 * 2. When speech is detected, trigger SpeechRecognizer to transcribe
 * 3. Check if transcript contains "hello forge" (fuzzy match)
 * 4. If match, emit to HotwordEventBus → show activation popup
 *
 * Battery-efficient: VAD uses minimal CPU, STT only runs when speech detected.
 * No external dependencies or API keys required.
 */
@AndroidEntryPoint
class HotwordDetectionService : Service() {

    @Inject lateinit var hotwordEventBus: HotwordEventBus
    @Inject lateinit var voiceActivityDetector: VoiceActivityDetector

    private var speechRecognizer: SpeechRecognizer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var vadJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "hotword_detection"
        private const val NOTIFICATION_ID = 42
        private const val KEYWORD = "hello forge"
        private const val FUZZY_MATCH_THRESHOLD = 0.5f // 50% of words must match (allows "forge" alone)
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("HotwordDetectionService: onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("HotwordDetectionService: onStartCommand")

        // Check RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Timber.w("HotwordDetectionService: RECORD_AUDIO not granted, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        // Start foreground with notification
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Initialize SpeechRecognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    Timber.d("HotwordDetectionService: STT error $error")
                    // Restart VAD and listening after error
                    scope.launch {
                        delay(500) // Brief pause before restart
                        voiceActivityDetector.start()
                    }
                }
                override fun onResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.let { transcript ->
                        Timber.d("HotwordDetectionService: STT result: $transcript")
                        if (fuzzyMatch(transcript, KEYWORD)) {
                            Timber.d("HotwordDetectionService: wake word detected!")
                            hotwordEventBus.emit()
                        }
                    }
                    // Restart VAD and listening after results
                    scope.launch {
                        delay(500) // Brief pause before restart
                        voiceActivityDetector.start()
                    }
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.let { partial ->
                        Timber.d("HotwordDetectionService: STT partial: $partial")
                        if (fuzzyMatch(partial, KEYWORD)) {
                            Timber.d("HotwordDetectionService: wake word detected (partial)!")
                            hotwordEventBus.emit()
                        }
                    }
                }
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
        }

        // Start VAD
        voiceActivityDetector.start()

        // Collect VAD events and trigger STT when speech detected
        vadJob = scope.launch {
            voiceActivityDetector.speechDetected.collect { detected ->
                if (detected) {
                    Timber.d("HotwordDetectionService: speech detected, starting STT")
                    // Stop VAD to release mic for SpeechRecognizer
                    voiceActivityDetector.stop()
                    startListening()
                }
            }
        }

        Timber.d("HotwordDetectionService: VAD started, listening for 'Hello Forge'")
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.d("HotwordDetectionService: onDestroy")
        vadJob?.cancel()
        voiceActivityDetector.stop()
        speechRecognizer?.destroy()
        speechRecognizer = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    /**
     * Fuzzy match: check if transcript contains the keyword.
     * Returns true if at least [FUZZY_MATCH_THRESHOLD] of keyword words are present.
     */
    private fun fuzzyMatch(transcript: String, keyword: String): Boolean {
        val transcriptWords = transcript.lowercase().split(Regex("\\s+"))
        val keywordWords = keyword.lowercase().split(Regex("\\s+"))
        val matchCount = keywordWords.count { it in transcriptWords }
        return matchCount.toFloat() / keywordWords.size >= FUZZY_MATCH_THRESHOLD
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hotword Detection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Listening for 'Hello Forge' wake word"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Forge is listening")
            .setContentText("Say \"Hello Forge\" to activate")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
