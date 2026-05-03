package com.forge.os.data.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.forge.os.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that keeps [ForgeHttpServer] alive while the user is
 * running the local HTTP API. Android 14+ requires a typed foreground
 * service; we register it as DATA_SYNC.
 */
@AndroidEntryPoint
class ForgeHttpService : Service() {

    @Inject lateinit var server: ForgeHttpServer

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification("Starting local HTTP API..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> {
                val port = intent?.getIntExtra(EXTRA_PORT, ForgeHttpServer.DEFAULT_PORT)
                    ?: ForgeHttpServer.DEFAULT_PORT
                val ok = server.start(port)
                val msg = if (ok) "Listening on http://127.0.0.1:$port"
                          else "Failed to bind on $port"
                startForeground(NOTIF_ID, buildNotification(msg))
                Timber.i("ForgeHttpService: $msg")
            }
            ACTION_STOP -> {
                server.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Forge HTTP Server",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Local HTTP API for third-party tools"
                }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = Intent(this, MainActivity::class.java)
            .putExtra("nav", "server")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Forge OS — HTTP API")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "forge_http_server"
        const val NOTIF_ID = 98765
        const val ACTION_START = "com.forge.os.action.SERVER_START"
        const val ACTION_STOP = "com.forge.os.action.SERVER_STOP"
        const val EXTRA_PORT = "port"

        fun start(context: Context, port: Int = ForgeHttpServer.DEFAULT_PORT) {
            val i = Intent(context, ForgeHttpService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, ForgeHttpService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }
    }
}
