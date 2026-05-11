package com.forge.os.domain.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    @ApplicationContext private val context: Context,
    // Enhanced Integration: Connect with learning systems (Lazy to break circular dependency)
    private val reflectionManager: dagger.Lazy<com.forge.os.domain.agent.ReflectionManager>,
    private val userPreferencesManager: com.forge.os.domain.user.UserPreferencesManager,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
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
        
        // Enhanced Integration: Learn notification patterns
        scope.launch {
            try {
                userPreferencesManager.recordInteractionPattern("receives_agent_notifications", 1)
                
                reflectionManager.get().recordPattern(
                    pattern = "Agent notification sent: started",
                    description = "Notified user about sub-agent start: ${goal.take(50)}",
                    applicableTo = listOf("notifications", "agent_activity", "user_awareness"),
                    tags = listOf("notification_sent", "agent_start", "user_engagement")
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to record notification patterns")
            }
        }
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
        
        // Enhanced Integration: Learn notification completion patterns
        scope.launch {
            try {
                userPreferencesManager.recordInteractionPattern("receives_completion_notifications", 1)
                
                if (success) {
                    reflectionManager.get().recordPattern(
                        pattern = "Agent notification sent: success",
                        description = "Notified user about successful sub-agent completion in ${durationMs}ms: ${summary.take(50)}",
                        applicableTo = listOf("notifications", "agent_success", "user_feedback"),
                        tags = listOf("notification_sent", "agent_success", "completion_feedback")
                    )
                } else {
                    reflectionManager.get().recordPattern(
                        pattern = "Agent notification sent: failure",
                        description = "Notified user about sub-agent failure: ${summary.take(50)}",
                        applicableTo = listOf("notifications", "agent_failure", "error_reporting"),
                        tags = listOf("notification_sent", "agent_failure", "error_feedback")
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to record notification completion patterns")
            }
        }
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
