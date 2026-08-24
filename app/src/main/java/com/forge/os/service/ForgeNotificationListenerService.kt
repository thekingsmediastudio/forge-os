package com.forge.os.service

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.TypedValue
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Bridges Android notifications to Forge Desktop (Task 11.1 / 11.2 / 11.4).
 *
 * - [onNotificationPosted] extracts title, body, small icon (Base64 PNG) and
 *   action labels, then forwards them through [EventBroadcaster].
 * - [onNotificationRemoved] forwards dismissal so the desktop can drop its
 *   mirror notification.
 * - Package allowlist filters come from [ConfigService] (notificationFilters).
 *
 * The user must grant notification access to Forge OS in system settings:
 *   Settings -> Apps -> Forge OS -> Notification access
 */
@AndroidEntryPoint
class ForgeNotificationListenerService : NotificationListenerService() {

    @Inject lateinit var broadcaster: EventBroadcaster
    @Inject lateinit var configService: ConfigService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        Timber.d("ForgeNotificationListener: connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Timber.d("ForgeNotificationListener: disconnected")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Never mirror our own notifications (avoids feedback loops).
        if (sbn.packageName == packageName) return
        scope.launch {
            try {
                if (!isAllowed(sbn.packageName)) return@launch
                val n = sbn.notification ?: return@launch
                val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                    ?: sbn.packageName
                val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                val body = n.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                    ?: text ?: ""
                if (title.isBlank() && body.isBlank()) return@launch

                broadcaster.emitNotification(
                    id = sbn.key,
                    packageName = sbn.packageName,
                    title = title,
                    body = body,
                    icon = encodeSmallIcon(n.smallIcon),
                    actions = n.actions?.take(3)?.mapIndexed { index, action ->
                        NotificationAction(
                            id = index.toString(),
                            label = action.title?.toString() ?: "Action ${index + 1}"
                        )
                    } ?: emptyList()
                )
                NotificationActionStore.put(sbn.key, n.actions)
            } catch (e: Exception) {
                Timber.e(e, "ForgeNotificationListener: forward failed")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        scope.launch {
            try {
                NotificationActionStore.remove(sbn.key)
                broadcaster.emitNotificationRemoved(sbn.key)
            } catch (e: Exception) {
                Timber.e(e, "ForgeNotificationListener: removal forward failed")
            }
        }
    }

    /**
     * Package allowlist from config. Empty list = forward all; otherwise a
     * notification is forwarded when it matches any filter exactly or by
     * prefix (either direction).
     */
    private suspend fun isAllowed(pkg: String): Boolean = try {
        val filters = configService.getConfig().notificationFilters
        if (filters.isEmpty()) {
            true
        } else {
            filters.any { filter -> pkg == filter || pkg.startsWith(filter) || filter.startsWith(pkg) }
        }
    } catch (e: Exception) {
        Timber.e(e, "ForgeNotificationListener: filter check failed")
        true
    }

    /** Renders the small icon into a 28dp PNG and returns it as Base64. */
    private fun encodeSmallIcon(icon: Icon?): String? {
        if (icon == null) return null
        return try {
            val drawable = icon.loadDrawable(this) ?: return null
            val size = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics
            ).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Timber.w(e, "ForgeNotificationListener: icon encode failed")
            null
        }
    }
}