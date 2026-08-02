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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import com.forge.os.R
import com.forge.os.domain.voice.HotwordEventBus
import com.forge.os.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that runs Picovoice Porcupine to listen for the
 * "Hello Forge" wake word. When detected, emits to [HotwordEventBus]
 * which triggers the activation overlay in the UI.
 *
 * Battery-conscious: Porcupine is designed for always-listening IoT
 * devices and uses minimal CPU. The service only runs when the app
 * is in the foreground (started/stopped by MainActivity).
 */
@AndroidEntryPoint
class HotwordDetectionService : Service() {

    @Inject lateinit var hotwordEventBus: HotwordEventBus

    private var porcupineManager: PorcupineManager? = null

    companion object {
        private const val CHANNEL_ID = "hotword_detection"
        private const val NOTIFICATION_ID = 42
        private const val KEYWORD_PATH = "forge.ppn" // in assets/
        private const val SENSITIVITY = 0.5f

        // TODO: User must provide their Picovoice AccessKey
        // Get one free at https://console.picovoice.ai/
        private const val ACCESS_KEY = "YOUR_PICOVOICE_ACCESS_KEY"
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

        // Initialize Porcupine
        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(ACCESS_KEY)
                .setKeywordPath(KEYWORD_PATH)
                .setSensitivity(SENSITIVITY)
                .build(this, PorcupineManagerCallback { keywordIndex ->
                    Timber.d("HotwordDetectionService: wake word detected (index=$keywordIndex)")
                    hotwordEventBus.emit()
                })
            porcupineManager?.start()
            Timber.d("HotwordDetectionService: Porcupine started, listening for 'Hello Forge'")
        } catch (e: Exception) {
            Timber.e(e, "HotwordDetectionService: failed to initialize Porcupine")
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Timber.d("HotwordDetectionService: onDestroy")
        porcupineManager?.stop()
        porcupineManager?.delete()
        porcupineManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
