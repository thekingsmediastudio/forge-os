package com.forge.os.domain.agent

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A question routed to a specific "mailbox". The route key is derived from
 * the [InputRoute] coroutine context element on the agent run; the default
 * is [InputRoute.UI] (the in-app chat). Telegram-driven runs use a per-chat
 * key so the question is delivered to that chat instead of the chat UI.
 */
data class PendingQuestion(val routeKey: String, val question: String)

/**
 * Coordinates mid-run user input requests between the agent loop (ToolRegistry)
 * and whichever surface is currently driving the agent (the in-app chat,
 * a Telegram chat, etc.).
 *
 * Flow:
 *  1. Agent calls `request_user_input` — ToolRegistry suspends in [awaitResponse].
 *  2. The current coroutine context's [InputRoute] is read to pick a mailbox.
 *  3. The question is published on [questions] tagged with that route key.
 *  4. The matching surface (ChatViewModel for "ui", ChannelManager for
 *     "channel:<id>:<chatId>") shows the question to the user and later
 *     calls [submitResponse] with the same route key.
 *  5. The agent unblocks with the user's text and continues.
 */
@Singleton
class UserInputBroker @Inject constructor() {

    private val _questions = MutableSharedFlow<PendingQuestion>(extraBufferCapacity = 16)
    /** Hot flow of pending questions for any route. Subscribers should
     *  filter on the route key they own. */
    val questions: SharedFlow<PendingQuestion> = _questions.asSharedFlow()

    /** One rendezvous channel per active route. Created lazily. */
    private val responseChannels = ConcurrentHashMap<String, Channel<String>>()

    /** Routes currently blocked inside [awaitResponse]. ChannelManager reads
     *  this to decide whether the next inbound message should unblock the
     *  agent rather than start a new turn. */
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** Called by ToolRegistry: publishes the question on the active route
     *  (read from the coroutine context) and suspends until the user
     *  answers via [submitResponse]. */
    suspend fun awaitResponse(question: String): String {
        val route = currentCoroutineContext()[InputRoute]?.routeKey ?: InputRoute.UI
        val ch = responseChannels.computeIfAbsent(route) { Channel(Channel.RENDEZVOUS) }
        pending.add(route)
        try {
            _questions.emit(PendingQuestion(route, question))
            return ch.receive()
        } finally {
            pending.remove(route)
        }
    }

    /** True when an agent run on [routeKey] is currently suspended waiting
     *  for the user's answer. */
    fun isAwaiting(routeKey: String): Boolean = routeKey in pending

    /** Called by the surface that owns the route (chat UI, ChannelManager, …)
     *  to deliver the user's answer back to the suspended agent. Safe to
     *  call when nothing is waiting — the value is dropped. */
    suspend fun submitResponse(routeKey: String, response: String) {
        val ch = responseChannels[routeKey] ?: return
        ch.send(response)
    }
}
