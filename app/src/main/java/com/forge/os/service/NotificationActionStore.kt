package com.forge.os.service

import android.app.Notification
import android.app.PendingIntent
import timber.log.Timber

/**
 * In-memory store of the most recent system notifications' action
 * PendingIntents, keyed by StatusBarNotification key (Task 11.4).
 *
 * Android does not expose a notification's actions after it has been posted,
 * so [ForgeNotificationListenerService] captures them here at post time and
 * clears them on removal. When the user taps an action on the desktop, the
 * HTTP endpoint /api/notification/action calls [trigger] to fire the stored
 * PendingIntent.
 */
object NotificationActionStore {

    private val actions = LinkedHashMap<String, Array<Notification.Action>>()
    private const val MAX_ENTRIES = 200

    @Synchronized
    fun put(key: String, acts: Array<Notification.Action>?) {
        if (acts.isNullOrEmpty()) return
        if (actions.size >= MAX_ENTRIES) {
            val oldest = actions.keys.iterator()
            if (oldest.hasNext()) actions.remove(oldest.next())
        }
        actions[key] = acts
    }

    @Synchronized
    fun remove(key: String) {
        actions.remove(key)
    }

    /**
     * Fires the stored action's PendingIntent.
     *
     * @return true when the action was found and dispatched
     */
    @Synchronized
    fun trigger(notificationId: String, actionId: String): Boolean {
        val acts = actions[notificationId] ?: return false
        val index = actionId.toIntOrNull() ?: return false
        val action = acts.getOrNull(index) ?: return false
        return try {
            val intent: PendingIntent? = action.actionIntent
            if (intent != null) {
                intent.send()
                true
            } else {
                Timber.w("NotificationActionStore: action $notificationId/$actionId has no intent")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "NotificationActionStore: send failed for $notificationId/$actionId")
            false
        }
    }
}