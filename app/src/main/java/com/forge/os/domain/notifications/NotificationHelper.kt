package com.forge.os.domain.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.forge.os.domain.cron.CronExecution
import com.forge.os.domain.cron.CronJob
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private const val CHANNEL_CRON_ID        = "forge_cron"
private const val CHANNEL_CRON_NAME      = "Cron Jobs"
private const val CHANNEL_AGENT_ID       = "forge_agent"
private const val CHANNEL_AGENT_NAME     = "Agent Activity"
private const val CHANNEL_EXT_ID         = "forge_external_api"
private const val CHANNEL_EXT_NAME       = "External API Requests"
private const val CHANNEL_COMPANION_ID   = "forge_companion"
private const val CHANNEL_COMPANION_NAME = "Companion Check-ins"
private const val CHANNEL_WELLBEING_ID   = "forge_wellbeing"
private const val CHANNEL_WELLBEING_NAME = "Wellbeing"
private const val CHANNEL_ALARM_ID       = "forge_alarms"
private const val CHANNEL_ALARM_NAME     = "Alarms"

/**
 * Centralised notification helper.
 *
 * All channels are created lazily via [ensureChannels] which is safe to call
 * many times.  POST_NOTIFICATIONS permission is declared in AndroidManifest;
 * on API 33+ the user is prompted on first launch by the system.
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init { ensureChannels() }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        createChannelIfAbsent(CHANNEL_CRON_ID, CHANNEL_CRON_NAME,
            NotificationManager.IMPORTANCE_LOW, "Cron job completion notifications")
        createChannelIfAbsent(CHANNEL_AGENT_ID, CHANNEL_AGENT_NAME,
            NotificationManager.IMPORTANCE_DEFAULT, "Long-running agent task updates")
        createChannelIfAbsent(CHANNEL_EXT_ID, CHANNEL_EXT_NAME,
            NotificationManager.IMPORTANCE_HIGH, "Other apps requesting access to Forge OS")
        createChannelIfAbsent(CHANNEL_COMPANION_ID, CHANNEL_COMPANION_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
            "Gentle proactive check-ins from your companion. Disable per-channel here at any time.")
        // Phase O-2 — separate channel for wellbeing / dependency nudges so the
        // user can silence them independently without muting companion check-ins.
        createChannelIfAbsent(CHANNEL_WELLBEING_ID, CHANNEL_WELLBEING_NAME,
            NotificationManager.IMPORTANCE_LOW,
            "Occasional gentle reminders to connect with people in your life.")
        // Alarms — high importance so they heads-up on the lock screen
        createChannelIfAbsent(CHANNEL_ALARM_ID, CHANNEL_ALARM_NAME,
            NotificationManager.IMPORTANCE_HIGH, "Forge alarm notifications")
    }

    private fun createChannelIfAbsent(id: String, name: String, importance: Int, desc: String) {
        if (manager.getNotificationChannel(id) != null) return
        manager.createNotificationChannel(
            NotificationChannel(id, name, importance).apply { description = desc }
        )
    }

    /**
     * Phase L — proactive companion check-in. Tap opens the Companion screen
     * with [seedPrompt] pre-filled in the input field.
     */
    fun notifyCompanionCheckIn(
        title: String,
        body: String,
        seedPrompt: String,
        tag: String,
    ) {
        try {
            ensureChannels()
            val open = Intent()
                .setClassName(context.packageName, "com.forge.os.presentation.MainActivity")
                .putExtra("nav", "companion")
                .putExtra("companionSeed", seedPrompt)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(context, tag.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notif = NotificationCompat.Builder(context, CHANNEL_COMPANION_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi)
                .build()
            manager.notify("companion_$tag".hashCode(), notif)
        } catch (e: SecurityException) {
            Timber.w(e, "NotificationHelper: missing POST_NOTIFICATIONS permission")
        } catch (e: Exception) {
            Timber.e(e, "NotificationHelper: notifyCompanionCheckIn failed")
        }
    }

    /**
     * Phase O-2 — single gentle nudge when usage exceeds the dependency
     * threshold. Fires through its own low-importance channel so the user
     * can mute it without silencing check-ins. At most once every 30 days
     * (enforced by [com.forge.os.domain.companion.DependencyMonitor]).
     *
     * Copy intentionally avoids urgency language and never says "I'll miss you."
     */
    fun notifyDependencyNudge(personaName: String, title: String, body: String) {
        try {
            ensureChannels()
            val open = Intent()
                .setClassName(context.packageName, "com.forge.os.presentation.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(context, "dep_nudge".hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notif = NotificationCompat.Builder(context, CHANNEL_WELLBEING_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .build()
            manager.notify("dep_nudge".hashCode(), notif)
        } catch (e: SecurityException) {
            Timber.w(e, "NotificationHelper: missing POST_NOTIFICATIONS permission")
        } catch (e: Exception) {
            Timber.e(e, "NotificationHelper: notifyDependencyNudge failed")
        }
    }

    /** First-time bind from an external app — surface a high-priority alert so the user can grant or deny. */
    fun notifyExternalApiRequest(packageName: String, displayName: String) {
        try {
            ensureChannels()
            val open = Intent().setClassName(context.packageName, "com.forge.os.presentation.MainActivity")
                .putExtra("nav", "external")
                .putExtra("pendingPkg", packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(context, packageName.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notif = NotificationCompat.Builder(context, CHANNEL_EXT_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("$displayName wants to use Forge OS")
                .setContentText("Tap to review and grant access")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "Package: $packageName\nTap to open the External API screen and decide what this app may do."
                ))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .build()
            manager.notify("ext_${packageName}".hashCode(), notif)
        } catch (e: SecurityException) {
            Timber.w(e, "NotificationHelper: missing POST_NOTIFICATIONS permission")
        } catch (e: Exception) {
            Timber.e(e, "NotificationHelper: notifyExternalApiRequest failed")
        }
    }

    fun notifyJobComplete(job: CronJob, execution: CronExecution) {
        try {
            ensureChannels()
            val title = if (execution.success) "✓ ${job.name}" else "✗ ${job.name} failed"
            val text = if (execution.success) {
                execution.output.lineSequence().firstOrNull()?.take(120) ?: "Completed in ${execution.durationMs}ms"
            } else {
                execution.error ?: "Unknown error"
            }
            val notif = NotificationCompat.Builder(context, CHANNEL_CRON_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(execution.output.take(800)))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            manager.notify(job.id.hashCode(), notif)
        } catch (e: SecurityException) {
            Timber.w(e, "NotificationHelper: missing POST_NOTIFICATIONS permission")
        } catch (e: Exception) {
            Timber.e(e, "NotificationHelper: notifyJobComplete failed")
        }
    }

    fun notifyAgentMessage(title: String, body: String) {
        try {
            ensureChannels()
            val notif = NotificationCompat.Builder(context, CHANNEL_AGENT_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            manager.notify(System.currentTimeMillis().toInt(), notif)
        } catch (e: SecurityException) {
            Timber.w(e, "NotificationHelper: missing POST_NOTIFICATIONS permission")
        } catch (e: Exception) {
            Timber.e(e, "NotificationHelper: notifyAgentMessage failed")
        }
    }
}
