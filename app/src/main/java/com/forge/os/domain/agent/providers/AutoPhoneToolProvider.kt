package com.forge.os.domain.agent.providers

import com.forge.os.data.android.AutoPhoneConnection
import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exposes all Forge AutoPhone capabilities as first-class Forge OS tools.
 *
 * Tool naming convention: `autophone_<action>` for phone-control and
 * `phone_notification_<action>` for notification management.
 *
 * All tools degrade gracefully: if AutoPhone is not installed or the
 * Accessibility service is not enabled the tool returns a descriptive
 * error JSON rather than crashing the agent loop.
 *
 * Tool groups:
 *   Screen reading   — autophone_read_screen
 *   Gestures         — autophone_tap_text, autophone_tap_xy, autophone_swipe,
 *                      autophone_scroll, autophone_find_and_tap
 *   Text input       — autophone_type
 *   App control      — autophone_launch_app, autophone_go_back, autophone_go_home
 *   System           — autophone_open_notifications, autophone_screenshot,
 *                      autophone_status
 *   Notifications    — phone_notification_list, phone_notification_dismiss,
 *                      phone_notification_reply
 */
@Singleton
class AutoPhoneToolProvider @Inject constructor(
    private val autoPhone: AutoPhoneConnection,
) : ToolProvider {

    override fun getTools(): List<ToolDefinition> = listOf(
        // ── Screen ────────────────────────────────────────────────────────────
        tool(
            name = "autophone_read_screen",
            description = "Read the current foreground app's UI tree via Android Accessibility. Returns a JSON array of visible elements with text, bounds, clickability, and content-description. Use before tap/type to locate elements.",
            params = emptyMap(), required = emptyList(),
        ),
        // ── Tap ───────────────────────────────────────────────────────────────
        tool(
            name = "autophone_tap_text",
            description = "Tap the first element whose text contains the given string (case-insensitive partial match). Returns ok/error JSON.",
            params = mapOf("text" to ("string" to "The visible label or text of the element to tap")),
            required = listOf("text"),
        ),
        tool(
            name = "autophone_tap_xy",
            description = "Tap at absolute screen pixel coordinates. Get coordinates from autophone_read_screen bounds.",
            params = mapOf(
                "x" to ("integer" to "Horizontal pixel position from left"),
                "y" to ("integer" to "Vertical pixel position from top"),
            ),
            required = listOf("x", "y"),
        ),
        tool(
            name = "autophone_find_and_tap",
            description = "Reads the screen and taps the first matching element in one step. More reliable than read+tap separately.",
            params = mapOf("text" to ("string" to "Text to find and tap")),
            required = listOf("text"),
        ),
        // ── Input ─────────────────────────────────────────────────────────────
        tool(
            name = "autophone_type",
            description = "Type text into the currently focused input field. Tap the field first with autophone_tap_text before typing.",
            params = mapOf("text" to ("string" to "Text to insert")),
            required = listOf("text"),
        ),
        // ── Gestures ─────────────────────────────────────────────────────────
        tool(
            name = "autophone_swipe",
            description = "Swipe in a direction by a pixel amount. Useful for swiping cards, unlocking, or navigating between pages.",
            params = mapOf(
                "direction" to ("string" to "up, down, left, or right"),
                "amount"    to ("integer" to "Pixels to swipe (e.g. 500)"),
            ),
            required = listOf("direction", "amount"),
        ),
        tool(
            name = "autophone_scroll",
            description = "Scroll the focused scrollable container up or down.",
            params = mapOf("direction" to ("string" to "up or down")),
            required = listOf("direction"),
        ),
        // ── App control ───────────────────────────────────────────────────────
        tool(
            name = "autophone_launch_app",
            description = "Launch an app by its package name (e.g. com.whatsapp) or display label (e.g. WhatsApp). Returns ok/error.",
            params = mapOf("app" to ("string" to "Package name or display label of the app to launch")),
            required = listOf("app"),
        ),
        tool(
            name = "autophone_go_back",
            description = "Press the system Back button.",
            params = emptyMap(), required = emptyList(),
        ),
        tool(
            name = "autophone_go_home",
            description = "Press the system Home button to return to the launcher.",
            params = emptyMap(), required = emptyList(),
        ),
        // ── System ────────────────────────────────────────────────────────────
        tool(
            name = "autophone_open_notifications",
            description = "Pull down the notification shade to reveal the notification panel.",
            params = emptyMap(), required = emptyList(),
        ),
        tool(
            name = "autophone_screenshot",
            description = "Capture the current screen as a base64-encoded PNG. Requires MediaProjection permission granted in AutoPhone.",
            params = emptyMap(), required = emptyList(),
        ),
        tool(
            name = "autophone_status",
            description = "Check whether AutoPhone is installed, connected, and the Accessibility + Notification listener services are active.",
            params = emptyMap(), required = emptyList(),
        ),
        // ── Notifications ─────────────────────────────────────────────────────
        tool(
            name = "phone_notification_list",
            description = "List all current status-bar notifications on the device. Returns JSON array with app, title, body, time, and canReply fields. Requires Notification Access granted to AutoPhone.",
            params = emptyMap(), required = emptyList(),
        ),
        tool(
            name = "phone_notification_dismiss",
            description = "Dismiss (cancel) a status-bar notification by its key (from phone_notification_list).",
            params = mapOf("key" to ("string" to "Notification key from phone_notification_list")),
            required = listOf("key"),
        ),
        tool(
            name = "phone_notification_reply",
            description = "Send a direct reply to a notification. Only works when canReply is true. Works for WhatsApp, Messages, Gmail, Slack, etc.",
            params = mapOf(
                "key"  to ("string" to "Notification key from phone_notification_list"),
                "text" to ("string" to "The reply text to send"),
            ),
            required = listOf("key", "text"),
        ),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (toolName) {
                "autophone_read_screen"          -> autoPhone.readScreen()
                "autophone_tap_text"             -> autoPhone.tapByText(args.str("text"))
                "autophone_tap_xy"               -> autoPhone.tapAt(args.int("x"), args.int("y"))
                "autophone_find_and_tap"         -> autoPhone.findAndTap(args.str("text"))
                "autophone_type"                 -> autoPhone.typeText(args.str("text"))
                "autophone_swipe"                -> autoPhone.swipe(args.str("direction"), args.int("amount"))
                "autophone_scroll"               -> autoPhone.scroll(args.str("direction"))
                "autophone_launch_app"           -> autoPhone.launchApp(args.str("app"))
                "autophone_go_back"              -> autoPhone.goBack()
                "autophone_go_home"              -> autoPhone.goHome()
                "autophone_open_notifications"   -> autoPhone.openNotifications()
                "autophone_screenshot"           -> autoPhone.screenshot()
                "autophone_status"               -> buildStatus()
                "phone_notification_list"        -> autoPhone.readNotifications()
                "phone_notification_dismiss"     -> autoPhone.dismissNotification(args.str("key"))
                "phone_notification_reply"       -> autoPhone.replyToNotification(args.str("key"), args.str("text"))
                else -> null
            }
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildStatus(): String = buildString {
        val connected = autoPhone.isConnected
        val accActive  = runCatching { autoPhone.isServiceActive() }.getOrElse { false }
        val ntfActive  = runCatching { autoPhone.isNotificationListenerActive() }.getOrElse { false }
        appendLine("📱 Forge AutoPhone Status")
        appendLine("• AIDL binding:         ${if (connected) "✓ Connected" else "✗ Not bound"}")
        appendLine("• Accessibility:        ${if (accActive) "✓ Active" else "✗ Not active"}")
        appendLine("• Notification access:  ${if (ntfActive) "✓ Granted" else "✗ Not granted"}")
        if (!connected) appendLine("\n⚠ Install Forge AutoPhone and enable Accessibility in Settings.")
        if (connected && !accActive) appendLine("\n⚠ Enable AutoPhone in Settings → Accessibility.")
        if (connected && !ntfActive) appendLine("\n⚠ Grant Notification access in Settings → Notifications → Notification access.")
    }

    private fun tool(
        name: String,
        description: String,
        params: Map<String, Pair<String, String>>,
        required: List<String>,
    ) = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = FunctionParameters(
                properties = params.mapValues { (_, v) ->
                    ParameterProperty(type = v.first, description = v.second)
                },
                required = required,
            ),
        )
    )

    private fun Map<String, Any>.str(key: String) = get(key)?.toString() ?: ""
    private fun Map<String, Any>.int(key: String) = get(key)?.toString()?.toIntOrNull() ?: 0
}
