package com.forge.os.domain.notifications

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase Q — registry of pending action handlers for agent-posted
 * notifications. When the agent posts a notification with one or more
 * action buttons, each button's PendingIntent points to
 * [NotificationActionReceiver] with an action token. On tap, the receiver
 * looks up the action here and dispatches it (typically by routing back into
 * the agent runtime as a tool call or chat message).
 *
 * The registry persists nothing — it lives in-memory for the process. If the
 * process dies the action becomes a no-op (the notification is just dismissed).
 */
@Serializable
data class NotificationAction(
    val token: String,
    val label: String,
    val kind: String, // "tool_call" | "chat_message" | "open_screen"
    val payloadJson: String = "{}",
)

@Singleton
class NotificationActionRegistry @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun interface Dispatcher {
        fun dispatch(action: NotificationAction)
    }

    private val actions = mutableMapOf<String, NotificationAction>()
    @Volatile private var dispatcher: Dispatcher? = null

    @Synchronized
    fun register(action: NotificationAction) {
        actions[action.token] = action
    }

    @Synchronized
    fun take(token: String): NotificationAction? = actions.remove(token)

    fun setDispatcher(d: Dispatcher) { dispatcher = d }

    fun dispatch(token: String) {
        val action = take(token) ?: run {
            Timber.w("NotificationAction: no action for token $token")
            return
        }
        val d = dispatcher ?: run {
            Timber.w("NotificationAction: no dispatcher; dropping ${action.label}")
            return
        }
        runCatching { d.dispatch(action) }
            .onFailure { Timber.e(it, "NotificationAction dispatch failed for ${action.label}") }
    }

    fun encode(action: NotificationAction): String = json.encodeToString(action)
}
