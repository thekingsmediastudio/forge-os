package com.forge.os.domain.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.forge.os.domain.control.AgentControlPlane
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase Q — agent-side notification builder that supports up to 3 action
 * buttons. Each action is registered with [NotificationActionRegistry] under
 * a unique token; tapping the button fires [NotificationActionReceiver] which
 * dispatches the registered handler back into the agent runtime.
 *
 * Gated by [AgentControlPlane.NOTIFY_ACTIONS] for the actions; the base
 * notification is always allowed.
 */
@Singleton
class AgentNotificationBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controlPlane: AgentControlPlane,
    private val actionRegistry: NotificationActionRegistry,
) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    data class ActionSpec(
        val label: String,
        val kind: String,            // "tool_call" | "chat_message" | "open_screen"
        val payloadJson: String = "{}",
    )

    fun postWithActions(
        title: String,
        body: String,
        channelId: String = "forge_agent",
        actions: List<ActionSpec> = emptyList(),
        navRoute: String = "chat",
    ): Int {
        val notifId = (System.currentTimeMillis() and 0x7fffffff).toInt() + 1
        return runCatching {
            // Tapping the body opens MainActivity and navigates to [navRoute].
            // Without this the body tap was a no-op (the notification just
            // dismissed itself if AutoCancel was set, otherwise nothing).
            val contentIntent = Intent().apply {
                setComponent(ComponentName(context.packageName,
                    "com.forge.os.presentation.MainActivity"))
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("nav", navRoute)
                putExtra("notifId", notifId)
            }
            val contentPi = PendingIntent.getActivity(
                context, notifId, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            if (actions.isNotEmpty() && controlPlane.isEnabled(AgentControlPlane.NOTIFY_ACTIONS)) {
                actions.take(3).forEach { spec ->
                    val token = UUID.randomUUID().toString()
                    actionRegistry.register(NotificationAction(
                        token = token, label = spec.label,
                        kind = spec.kind, payloadJson = spec.payloadJson,
                    ))
                    val intent = Intent(NotificationActionReceiver.ACTION)
                        .setClass(context, NotificationActionReceiver::class.java)
                        .putExtra(NotificationActionReceiver.EXTRA_TOKEN, token)
                    val pi = PendingIntent.getBroadcast(
                        context, token.hashCode(), intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    builder.addAction(NotificationCompat.Action.Builder(
                        android.R.drawable.ic_menu_send, spec.label, pi).build())
                }
            }
            manager.notify(notifId, builder.build())
            notifId
        }.onFailure { Timber.w(it, "AgentNotificationBuilder.postWithActions failed") }
            .getOrDefault(-1)
    }
}
