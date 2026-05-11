package com.forge.os.domain.agent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element identifying which "mailbox" should receive
 * mid-run `request_user_input` questions for the current agent run.
 *
 * The default route is [UI] (the in-app chat). [ChannelManager] wraps each
 * Telegram-driven agent run with a per-chat route key so the broker can
 * deliver questions back to the correct chat instead of the on-screen UI
 * (the original Phase T bug).
 */
class InputRoute(val routeKey: String) : AbstractCoroutineContextElement(Key) {
    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<InputRoute> {
        /** Default mailbox = the in-app chat screen. */
        const val UI = "ui"

        /** Build a stable route key for a Telegram (channel,chat) pair. */
        fun forChannel(channelId: String, chatId: String): String =
            "channel:$channelId:$chatId"
    }
}
