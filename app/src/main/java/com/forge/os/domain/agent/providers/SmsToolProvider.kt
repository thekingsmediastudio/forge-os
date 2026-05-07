package com.forge.os.domain.agent.providers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SMS tools for reading the device's SMS inbox and sending messages.
 *
 * Tools:
 *   sms_list    — list recent SMS messages (inbox + sent)
 *   sms_search  — search SMS by contact name or content snippet
 *   sms_send    — send an SMS to a phone number
 *   sms_threads — list unique conversation threads
 *
 * Requires READ_SMS + SEND_SMS permissions.
 */
@Singleton
class SmsToolProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolProvider {

    override fun getTools(): List<ToolDefinition> = listOf(
        tool(
            name = "sms_list",
            description = "List recent SMS messages. Returns sender, body preview, timestamp, and inbox/sent type.",
            params = mapOf(
                "limit"  to ("integer" to "Max messages to return (default 20, max 100)"),
                "box"    to ("string"  to "inbox, sent, or all (default inbox)"),
            ),
            required = emptyList(),
        ),
        tool(
            name = "sms_search",
            description = "Search SMS messages by sender address or body content.",
            params = mapOf(
                "query" to ("string" to "Search term — matched against sender and body"),
                "limit" to ("integer" to "Max results (default 20)"),
            ),
            required = listOf("query"),
        ),
        tool(
            name = "sms_threads",
            description = "List distinct SMS conversation threads sorted by most recent. Each thread shows the contact address and last message snippet.",
            params = mapOf("limit" to ("integer" to "Max threads to return (default 30)")),
            required = emptyList(),
        ),
        tool(
            name = "sms_send",
            description = "Send an SMS to a phone number. Returns ok/error. Requires SEND_SMS permission.",
            params = mapOf(
                "to"   to ("string" to "Recipient phone number"),
                "body" to ("string" to "Message text"),
            ),
            required = listOf("to", "body"),
        ),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? = when (toolName) {
        "sms_list"    -> {
            if (!hasPermission(android.Manifest.permission.READ_SMS))
                return "Error: READ_SMS permission not granted. Grant it in Settings → Apps → Forge OS → Permissions."
            listSms(args["box"]?.toString() ?: "inbox", args["limit"]?.toString()?.toIntOrNull() ?: 20)
        }
        "sms_search"  -> {
            if (!hasPermission(android.Manifest.permission.READ_SMS))
                return "Error: READ_SMS permission not granted."
            searchSms(args["query"]?.toString() ?: "", args["limit"]?.toString()?.toIntOrNull() ?: 20)
        }
        "sms_threads" -> {
            if (!hasPermission(android.Manifest.permission.READ_SMS))
                return "Error: READ_SMS permission not granted."
            listThreads(args["limit"]?.toString()?.toIntOrNull() ?: 30)
        }
        "sms_send"    -> {
            if (!hasPermission(android.Manifest.permission.SEND_SMS))
                return """{"ok":false,"error":"SEND_SMS permission not granted. Grant it in Settings → Apps → Forge OS → Permissions."}"""
            sendSms(args["to"]?.toString() ?: "", args["body"]?.toString() ?: "")
        }
        else -> null
    }

    private fun hasPermission(permission: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ── Implementations ───────────────────────────────────────────────────────

    private fun listSms(box: String, limit: Int): String = runCatching {
        val uri = when (box.lowercase()) {
            "sent" -> Telephony.Sms.Sent.CONTENT_URI
            "all"  -> Telephony.Sms.CONTENT_URI
            else   -> Telephony.Sms.Inbox.CONTENT_URI
        }
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val results = mutableListOf<String>()
        context.contentResolver.query(uri, projection, null, null, "${Telephony.Sms.DATE} DESC LIMIT $limit")?.use { c ->
            while (c.moveToNext()) {
                val addr = c.getString(1) ?: "?"
                val body = c.getString(2)?.take(80) ?: ""
                val date = java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(c.getLong(3)))
                results += "[$date] $addr: $body"
            }
        }
        if (results.isEmpty()) "No messages in $box. Check READ_SMS permission."
        else results.joinToString("\n")
    }.getOrElse { "Error reading SMS: ${it.message}" }

    private fun searchSms(query: String, limit: Int): String = runCatching {
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val sel = "${Telephony.Sms.ADDRESS} LIKE ? OR ${Telephony.Sms.BODY} LIKE ?"
        val arg = "%$query%"
        val results = mutableListOf<String>()
        context.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, sel, arrayOf(arg, arg),
            "${Telephony.Sms.DATE} DESC LIMIT $limit")?.use { c ->
            while (c.moveToNext()) {
                val addr = c.getString(1) ?: "?"
                val body = c.getString(2)?.take(80) ?: ""
                val date = java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(c.getLong(3)))
                results += "[$date] $addr: $body"
            }
        }
        if (results.isEmpty()) "No SMS matching '$query'." else results.joinToString("\n")
    }.getOrElse { "Error: ${it.message}" }

    private fun listThreads(limit: Int): String = runCatching {
        val projection = arrayOf(Telephony.Sms.Conversations.THREAD_ID, Telephony.Sms.Conversations.ADDRESS, Telephony.Sms.Conversations.BODY, Telephony.Sms.Conversations.DATE)
        val results = mutableListOf<String>()
        context.contentResolver.query(Telephony.Sms.Conversations.CONTENT_URI, projection, null, null,
            "${Telephony.Sms.Conversations.DATE} DESC LIMIT $limit")?.use { c ->
            while (c.moveToNext()) {
                val addr = c.getString(1) ?: "?"
                val body = c.getString(2)?.take(60) ?: ""
                results += "• $addr: $body"
            }
        }
        if (results.isEmpty()) "No threads. Check READ_SMS permission." else results.joinToString("\n")
    }.getOrElse { "Error: ${it.message}" }

    private fun sendSms(to: String, body: String): String {
        if (to.isBlank()) return """{"ok":false,"error":"'to' is required"}"""
        if (body.isBlank()) return """{"ok":false,"error":"'body' is required"}"""
        return runCatching {
            val sm = context.getSystemService(SmsManager::class.java)
                ?: return """{"ok":false,"error":"SmsManager unavailable"}"""
            sm.sendTextMessage(to, null, body, null, null)
            """{"ok":true,"to":"$to","body_preview":"${body.take(40)}"}"""
        }.getOrElse { """{"ok":false,"error":"${it.message}"}""" }
    }

    private fun tool(name: String, description: String, params: Map<String, Pair<String, String>>, required: List<String>) =
        ToolDefinition(function = FunctionDefinition(name = name, description = description,
            parameters = FunctionParameters(
                properties = params.mapValues { (_, v) -> ParameterProperty(type = v.first, description = v.second) },
                required = required,
            )))
}
