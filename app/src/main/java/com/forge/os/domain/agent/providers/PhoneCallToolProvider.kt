package com.forge.os.domain.agent.providers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone call tools — read call log and initiate calls.
 *
 * Tools:
 *   call_log_list   — list recent calls (incoming/outgoing/missed)
 *   call_log_missed — list only missed calls
 *   call_dial       — open the dialler with a number pre-filled (no CALL_PHONE permission)
 *   call_phone      — initiate a call programmatically (requires CALL_PHONE permission)
 *
 * Requires READ_CALL_LOG. call_phone additionally requires CALL_PHONE.
 */
@Singleton
class PhoneCallToolProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolProvider {

    override fun getTools(): List<ToolDefinition> = listOf(
        tool(
            name = "call_log_list",
            description = "List recent calls with caller name/number, direction (IN/OUT/MISSED), duration, and timestamp.",
            params = mapOf(
                "limit" to ("integer" to "Max entries (default 20)"),
                "type"  to ("string"  to "all, incoming, outgoing, or missed (default all)"),
            ),
            required = emptyList(),
        ),
        tool(
            name = "call_log_missed",
            description = "List only missed calls. Shortcut for call_log_list with type=missed.",
            params = mapOf("limit" to ("integer" to "Max entries (default 20)")),
            required = emptyList(),
        ),
        tool(
            name = "call_dial",
            description = "Open the dialler app with the given number pre-filled. The user must press call. No special permission needed.",
            params = mapOf("number" to ("string" to "Phone number to dial")),
            required = listOf("number"),
        ),
        tool(
            name = "call_phone",
            description = "Initiate a phone call immediately without user interaction. Requires CALL_PHONE permission.",
            params = mapOf("number" to ("string" to "Phone number to call")),
            required = listOf("number"),
        ),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? {
        return when (toolName) {
        "call_log_list"   -> {
            if (!hasPermission(android.Manifest.permission.READ_CALL_LOG))
                return "Error: READ_CALL_LOG permission not granted. Grant it in Settings → Apps → Forge OS → Permissions."
            callLog(args["type"]?.toString() ?: "all", args["limit"]?.toString()?.toIntOrNull() ?: 20)
        }
        "call_log_missed" -> {
            if (!hasPermission(android.Manifest.permission.READ_CALL_LOG))
                return "Error: READ_CALL_LOG permission not granted."
            callLog("missed", args["limit"]?.toString()?.toIntOrNull() ?: 20)
        }
        "call_dial"       -> dial(args["number"]?.toString() ?: "")
        "call_phone"      -> {
            if (!hasPermission(android.Manifest.permission.CALL_PHONE))
                return """{"ok":false,"error":"CALL_PHONE permission not granted. Grant it in Settings → Apps → Forge OS → Permissions."}"""
            call(args["number"]?.toString() ?: "")
        }
        else -> null
        }
    }

    private fun hasPermission(permission: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ── Implementations ───────────────────────────────────────────────────────

    private val fmt = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())

    private fun callLog(type: String, limit: Int): String = runCatching {
        val typeFilter = when (type.lowercase()) {
            "incoming" -> "${CallLog.Calls.TYPE}=${CallLog.Calls.INCOMING_TYPE}"
            "outgoing" -> "${CallLog.Calls.TYPE}=${CallLog.Calls.OUTGOING_TYPE}"
            "missed"   -> "${CallLog.Calls.TYPE}=${CallLog.Calls.MISSED_TYPE}"
            else       -> null
        }
        val proj = arrayOf(
            CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION,
        )
        val rows = mutableListOf<String>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI, proj, typeFilter, null,
            "${CallLog.Calls.DATE} DESC LIMIT $limit"
        )?.use { c ->
            while (c.moveToNext()) {
                val name  = c.getString(0)?.takeIf { it.isNotBlank() } ?: c.getString(1) ?: "?"
                val dir   = when (c.getInt(2)) {
                    CallLog.Calls.INCOMING_TYPE -> "IN"
                    CallLog.Calls.OUTGOING_TYPE -> "OUT"
                    CallLog.Calls.MISSED_TYPE   -> "MISSED"
                    else                         -> "?"
                }
                val date  = fmt.format(Date(c.getLong(3)))
                val dur   = c.getInt(4).let { "${it / 60}m${it % 60}s" }
                rows += "[$date] $dir  $name  ${if (dir != "MISSED") dur else ""}"
            }
        }
        if (rows.isEmpty()) "No call log entries. Check READ_CALL_LOG permission."
        else rows.joinToString("\n")
    }.getOrElse { "Error: ${it.message}" }

    private fun dial(number: String): String = runCatching {
        if (number.isBlank()) return """{"ok":false,"error":"number required"}"""
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${number.trim()}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        """{"ok":true,"action":"dial","number":"$number"}"""
    }.getOrElse { """{"ok":false,"error":"${it.message}"}""" }

    private fun call(number: String): String = runCatching {
        if (number.isBlank()) return """{"ok":false,"error":"number required"}"""
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${number.trim()}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        """{"ok":true,"action":"call","number":"$number"}"""
    }.getOrElse { """{"ok":false,"error":"${it.message} — CALL_PHONE permission may be missing"}""" }

    private fun tool(name: String, description: String, params: Map<String, Pair<String, String>>, required: List<String>) =
        ToolDefinition(function = FunctionDefinition(name = name, description = description,
            parameters = FunctionParameters(
                properties = params.mapValues { (_, v) -> ParameterProperty(type = v.first, description = v.second) },
                required = required,
            )))
}
