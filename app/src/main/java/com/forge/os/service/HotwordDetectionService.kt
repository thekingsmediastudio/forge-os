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
    private var voiceModeJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "hotword_detection"
        private const val NOTIFICATION_ID = 42
        private const val KEYWORD = "hello forge"
        private const val FUZZY_MATCH_THRESHOLD = 0.5f // 50% of words must match (allows "forge" alone)

        /**
         * Set to true while voice mode owns the mic. When true the service
         * releases its VAD/SpeechRecognizer so voice mode doesn't hit
         * ERROR_RECOGNIZER_BUSY; set back to false to resume listening.
         */
        @Volatile var voiceModeActive = false

        fun start(context: android.content.Context) {
            val intent = Intent(context, HotwordDetectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, HotwordDetectionService::class.java))
        }
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
                            onWakeWordDetected()
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
                            onWakeWordDetected()
                        }
                    }
                }
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
        }

        // Start VAD (unless voice mode currently owns the mic)
        if (!voiceModeActive) {
            voiceActivityDetector.start()
        }

        // Collect VAD events and trigger STT when speech detected
        vadJob = scope.launch {
            voiceActivityDetector.speechDetected.collect { detected ->
                if (detected && !voiceModeActive) {
                    Timber.d("HotwordDetectionService: speech detected, starting STT")
                    // Stop VAD to release mic for SpeechRecognizer
                    voiceActivityDetector.stop()
                    startListening()
                }
            }
        }

        // Watch for voice mode taking over the mic; release/reacquire accordingly.
        voiceModeJob = scope.launch {
            var wasActive = voiceModeActive
            while (isActive) {
                val active = voiceModeActive
                if (active != wasActive) {
                    wasActive = active
                    if (active) {
                        Timber.d("HotwordDetectionService: voice mode active, releasing mic")
                        speechRecognizer?.stopListening()
                        voiceActivityDetector.stop()
                    } else {
                        Timber.d("HotwordDetectionService: voice mode ended, resuming VAD")
                        delay(300) // let voice mode fully release the mic first
                        voiceActivityDetector.start()
                    }
                }
                delay(200)
            }
        }

        Timber.d("HotwordDetectionService: VAD started, listening for 'Hello Forge'")
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.d("HotwordDetectionService: onDestroy")
        vadJob?.cancel()
        voiceModeJob?.cancel()
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
     * Emit the wake-word event to the in-app bus, and — when the app isn't in the
     * foreground — also raise the floating system-overlay bubble so the activation
     * is visible over other apps (the in-app Dialog only renders while foreground).
     */
    private fun onWakeWordDetected() {
        hotwordEventBus.emit()
        if (!isAppInForeground()) {
            try {
                startService(Intent(this, HotwordOverlayService::class.java))
            } catch (e: Exception) {
                Timber.w("HotwordDetectionService: couldn't start overlay: ${e.message}")
            }
        }
    }

    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val processes = activityManager.runningAppProcesses ?: return false
        val myPackage = packageName
        return processes.any {
            it.processName == myPackage &&
                it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
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
