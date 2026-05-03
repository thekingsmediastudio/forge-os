package com.forge.os.data.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.forge.autophone.IAutoPhoneService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the AIDL binding to Forge AutoPhone (com.forge.autophone).
 *
 * AutoPhone exposes [IAutoPhoneService] — phone-control tools (tap, scroll,
 * type, read screen, etc.) plus notification interaction (read, dismiss, reply).
 *
 * Call [connect] once at startup (or lazily on first tool use). The binding is
 * persistent — Android will rebind automatically after AutoPhone restarts.
 * Use [isConnected] or the [state] flow to check availability before calling.
 *
 * All public methods are safe to call from any thread; they return an error JSON
 * string if the service is unavailable rather than throwing.
 */
@Singleton
class AutoPhoneConnection @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, UNAVAILABLE }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    private var service: IAutoPhoneService? = null

    val isConnected: Boolean get() = service != null && _state.value == State.CONNECTED

    // ── Binding lifecycle ─────────────────────────────────────────────────────

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IAutoPhoneService.Stub.asInterface(binder)
            _state.value = State.CONNECTED
            Timber.i("AutoPhone connected")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            _state.value = State.DISCONNECTED
            Timber.w("AutoPhone disconnected — will rebind when available")
        }
    }

    /** Initiate the binding. Safe to call multiple times. */
    fun connect() {
        if (_state.value == State.CONNECTED || _state.value == State.CONNECTING) return
        _state.value = State.CONNECTING
        val intent = Intent("com.forge.autophone.IAutoPhoneService").apply {
            setPackage("com.forge.autophone")
        }
        val bound = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse { false }

        if (!bound) {
            _state.value = State.UNAVAILABLE
            Timber.w("AutoPhone not installed or disabled")
        }
    }

    /** Lazy connect — called automatically on first tool use. */
    private fun ensureConnected(): IAutoPhoneService? {
        if (service == null) connect()
        return service
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun noService() = """{"ok":false,"error":"AutoPhone not connected. Install Forge AutoPhone and enable Accessibility."}"""

    private fun call(block: (IAutoPhoneService) -> String): String {
        val svc = ensureConnected() ?: return noService()
        return try { block(svc) } catch (e: RemoteException) {
            Timber.e(e, "AutoPhone RPC failed")
            """{"ok":false,"error":"AutoPhone RPC failed: ${e.message}"}"""
        }
    }

    // ── Screen-control tools ──────────────────────────────────────────────────

    fun readScreen()              = call { it.readScreen() }
    fun tapByText(text: String)   = call { it.tapByText(text) }
    fun tapAt(x: Int, y: Int)     = call { it.tapAt(x, y) }
    fun typeText(text: String)    = call { it.typeText(text) }
    fun swipe(dir: String, px: Int) = call { it.swipe(dir, px) }
    fun scroll(dir: String)       = call { it.scroll(dir) }
    fun launchApp(pkg: String)    = call { it.launchApp(pkg) }
    fun goBack()                  = call { it.goBack() }
    fun goHome()                  = call { it.goHome() }
    fun openNotifications()       = call { it.openNotifications() }
    fun screenshot()              = call { it.screenshot() }
    fun findAndTap(text: String)  = call { it.findAndTap(text) }
    fun isServiceActive(): Boolean = runCatching { ensureConnected()?.isServiceActive() ?: false }.getOrElse { false }

    // ── Notification tools ────────────────────────────────────────────────────

    fun readNotifications()                    = call { it.readNotifications() }
    fun dismissNotification(key: String)       = call { it.dismissNotification(key) }
    fun replyToNotification(key: String, text: String) = call { it.replyToNotification(key, text) }
    fun isNotificationListenerActive(): Boolean = runCatching { ensureConnected()?.isNotificationListenerActive() ?: false }.getOrElse { false }

    // ── Schedule lifecycle ────────────────────────────────────────────────────

    fun notifyScheduleStarted(scheduleId: String, planSummary: String) {
        runCatching { ensureConnected()?.notifyScheduleStarted(scheduleId, planSummary) }
            .onFailure { Timber.w(it, "notifyScheduleStarted failed") }
    }

    fun notifyScheduleCompleted(scheduleId: String, ok: Boolean, result: String) {
        runCatching { ensureConnected()?.notifyScheduleCompleted(scheduleId, ok, result) }
            .onFailure { Timber.w(it, "notifyScheduleCompleted failed") }
    }
}
