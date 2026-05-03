package com.forge.os.domain.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts user-visible notifications about long-running agent work:
 *   - sub-agent started / completed / failed
 *   - cron job results (delegated to the existing NotificationHelper for cron)
 *
 * Channel id: "forge_agent_activity" (importance: LOW so notifications are
 * silent but still visible in the shade).
 */
@Singleton
class AgentNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val nm: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init { ensureChannel() }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Agent Activity",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Sub-agent and long-running task updates"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun notifyAgentStarted(agentId: String, goal: String) {
        post(
            id = agentId.hashCode(),
            title = "🤖 Sub-agent running",
            body = goal.take(120),
            ongoing = true
        )
    }

    fun notifyAgentFinished(agentId: String, success: Boolean, summary: String, durationMs: Long?) {
        val prefix = if (success) "✅ Sub-agent done" else "❌ Sub-agent failed"
        val tail = durationMs?.let { " (${it}ms)" } ?: ""
        post(
            id = agentId.hashCode(),
            title = prefix + tail,
            body = summary.take(180),
            ongoing = false
        )
    }

    fun cancel(agentId: String) {
        nm.cancel(agentId.hashCode())
    }

    private fun post(id: Int, title: String, body: String, ongoing: Boolean) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        val pi = PendingIntent.getActivity(
            context, id, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(pi)
            .build()
        nm.notify(id, notification)
    }

    companion object {
        const val CHANNEL_ID = "forge_agent_activity"
    }
}
