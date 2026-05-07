package com.forge.os.domain.agent.providers

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calendar tools — read and create events on the device calendar.
 *
 * Tools:
 *   calendar_list_events    — upcoming events (today + N days)
 *   calendar_search_events  — search events by title keyword
 *   calendar_create_event   — create a new calendar event
 *   calendar_delete_event   — delete an event by ID
 *   calendar_list_calendars — list all calendars on device
 *
 * Requires READ_CALENDAR (+ WRITE_CALENDAR for create/delete).
 */
@Singleton
class CalendarToolProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolProvider {

    override fun getTools(): List<ToolDefinition> = listOf(
        tool(
            name = "calendar_list_events",
            description = "List upcoming calendar events. Returns title, start/end time, location, and calendar name.",
            params = mapOf(
                "days" to ("integer" to "How many days ahead to look (default 7)"),
            ),
            required = emptyList(),
        ),
        tool(
            name = "calendar_search_events",
            description = "Search calendar events by title keyword.",
            params = mapOf(
                "query" to ("string" to "Text to search in event titles"),
                "days"  to ("integer" to "Days ahead to search (default 30)"),
            ),
            required = listOf("query"),
        ),
        tool(
            name = "calendar_list_calendars",
            description = "List all calendars configured on the device (Google, Exchange, local, etc.).",
            params = emptyMap(), required = emptyList(),
        ),
        tool(
            name = "calendar_create_event",
            description = "Create a new calendar event. Times are ISO 8601 strings (e.g. '2026-05-04T09:00:00').",
            params = mapOf(
                "title"       to ("string"  to "Event title"),
                "start"       to ("string"  to "Start datetime (ISO 8601)"),
                "end"         to ("string"  to "End datetime (ISO 8601)"),
                "description" to ("string"  to "Event description (optional)"),
                "location"    to ("string"  to "Event location (optional)"),
                "calendar_id" to ("integer" to "Calendar ID from calendar_list_calendars (default: primary)"),
            ),
            required = listOf("title", "start", "end"),
        ),
        tool(
            name = "calendar_delete_event",
            description = "Delete a calendar event by its ID (from calendar_list_events).",
            params = mapOf("id" to ("string" to "Event ID")),
            required = listOf("id"),
        ),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? {
        return when (toolName) {
        "calendar_list_events"    -> {
            if (!hasPermission(android.Manifest.permission.READ_CALENDAR))
                return "Error: READ_CALENDAR permission not granted. Forge OS needs calendar access — please grant it in Settings → Apps → Forge OS → Permissions."
            listEvents(args["days"]?.toString()?.toIntOrNull() ?: 7)
        }
        "calendar_search_events"  -> {
            if (!hasPermission(android.Manifest.permission.READ_CALENDAR))
                return "Error: READ_CALENDAR permission not granted."
            searchEvents(args["query"]?.toString() ?: "", args["days"]?.toString()?.toIntOrNull() ?: 30)
        }
        "calendar_list_calendars" -> {
            if (!hasPermission(android.Manifest.permission.READ_CALENDAR))
                return "Error: READ_CALENDAR permission not granted."
            listCalendars()
        }
        "calendar_create_event"   -> {
            if (!hasPermission(android.Manifest.permission.WRITE_CALENDAR))
                return "Error: WRITE_CALENDAR permission not granted."
            createEvent(args)
        }
        "calendar_delete_event"   -> {
            if (!hasPermission(android.Manifest.permission.WRITE_CALENDAR))
                return "Error: WRITE_CALENDAR permission not granted."
            deleteEvent(args["id"]?.toString() ?: "")
        }
        else -> null
        }
    }

    private fun hasPermission(permission: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ── Implementations ───────────────────────────────────────────────────────

    private val fmt = SimpleDateFormat("EEE MMM d, HH:mm", Locale.getDefault())

    private fun listEvents(days: Int): String = runCatching {
        val now  = System.currentTimeMillis()
        val end  = now + days * 86_400_000L
        val proj = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
        )
        val sel  = "${CalendarContract.Events.DTSTART} BETWEEN ? AND ? AND ${CalendarContract.Events.DELETED}=0"
        val rows = mutableListOf<String>()
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI, proj, sel,
            arrayOf(now.toString(), end.toString()), "${CalendarContract.Events.DTSTART} ASC")?.use { c ->
            while (c.moveToNext()) {
                val id       = c.getLong(0)
                val title    = c.getString(1) ?: "(untitled)"
                val start    = fmt.format(java.util.Date(c.getLong(2)))
                val endTime  = fmt.format(java.util.Date(c.getLong(3)))
                val location = c.getString(4)?.let { " @ $it" } ?: ""
                val cal      = c.getString(5) ?: ""
                rows += "[$start → $endTime] $title$location (id=$id, cal=$cal)"
            }
        }
        if (rows.isEmpty()) "No events in the next $days day(s). Check READ_CALENDAR permission."
        else "Events for next $days day(s):\n" + rows.joinToString("\n")
    }.getOrElse { "Error: ${it.message}" }

    private fun searchEvents(query: String, days: Int): String = runCatching {
        val now = System.currentTimeMillis()
        val end = now + days * 86_400_000L
        val proj = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND)
        val sel  = "${CalendarContract.Events.TITLE} LIKE ? AND ${CalendarContract.Events.DTSTART} BETWEEN ? AND ? AND ${CalendarContract.Events.DELETED}=0"
        val rows = mutableListOf<String>()
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI, proj, sel,
            arrayOf("%$query%", now.toString(), end.toString()), "${CalendarContract.Events.DTSTART} ASC")?.use { c ->
            while (c.moveToNext()) {
                val id    = c.getLong(0)
                val title = c.getString(1) ?: "(untitled)"
                val start = fmt.format(java.util.Date(c.getLong(2)))
                rows += "$start — $title (id=$id)"
            }
        }
        if (rows.isEmpty()) "No events matching '$query'." else rows.joinToString("\n")
    }.getOrElse { "Error: ${it.message}" }

    private fun listCalendars(): String = runCatching {
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.IS_PRIMARY)
        val rows = mutableListOf<String>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id   = c.getLong(0)
                val name = c.getString(1)
                val acct = c.getString(2)
                val pri  = c.getInt(3) == 1
                rows += "id=$id  $name ($acct)${if (pri) " [primary]" else ""}"
            }
        }
        if (rows.isEmpty()) "No calendars found." else rows.joinToString("\n")
    }.getOrElse { "Error: ${it.message}" }

    private fun createEvent(args: Map<String, Any>): String = runCatching {
        val title  = args["title"]?.toString() ?: return "Error: title required"
        val start  = parseIso(args["start"]?.toString() ?: return "Error: start required")
        val end    = parseIso(args["end"]?.toString() ?: return "Error: end required")
        val calId  = args["calendar_id"]?.toString()?.toLongOrNull() ?: primaryCalendarId() ?: 1L

        val cv = ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            args["description"]?.toString()?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            args["location"]?.toString()?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, cv)
        val id = uri?.lastPathSegment
        """{"ok":true,"event_id":"$id","title":"${title.replace("\"","'")}","start":${start},"end":${end}}"""
    }.getOrElse { """{"ok":false,"error":"${it.message}"}""" }

    private fun deleteEvent(id: String): String = runCatching {
        if (id.isBlank()) return """{"ok":false,"error":"id required"}"""
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id.toLong())
        val rows = context.contentResolver.delete(uri, null, null)
        if (rows > 0) """{"ok":true,"deleted_id":"$id"}""" else """{"ok":false,"error":"Event $id not found"}"""
    }.getOrElse { """{"ok":false,"error":"${it.message}"}""" }

    private fun parseIso(s: String): Long {
        return runCatching {
            val formats = listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")
            for (fmt in formats) {
                runCatching { SimpleDateFormat(fmt, Locale.US).parse(s)?.time }
                    .getOrNull()?.let { return it }
            }
            throw IllegalArgumentException("Cannot parse date: $s")
        }.getOrElse { throw it }
    }

    private fun primaryCalendarId(): Long? {
        val proj = arrayOf(CalendarContract.Calendars._ID)
        val sel  = "${CalendarContract.Calendars.IS_PRIMARY}=1"
        return context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    private fun tool(name: String, description: String, params: Map<String, Pair<String, String>>, required: List<String>) =
        ToolDefinition(function = FunctionDefinition(name = name, description = description,
            parameters = FunctionParameters(
                properties = params.mapValues { (_, v) -> ParameterProperty(type = v.first, description = v.second) },
                required = required,
            )))
}
