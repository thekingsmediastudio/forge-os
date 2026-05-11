package com.forge.os.domain.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Phase Q — receives taps on action buttons attached to agent-posted
 * notifications. The intent carries the action token; the receiver looks the
 * token up in [NotificationActionRegistry] and dispatches the registered
 * handler.
 *
 * Declared in AndroidManifest with action `com.forge.os.action.AGENT_NOTIF`.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var registry: NotificationActionRegistry

    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        registry.dispatch(token)
    }

    companion object {
        const val ACTION = "com.forge.os.action.AGENT_NOTIF"
        const val EXTRA_TOKEN = "forge.notif.token"
    }
}
