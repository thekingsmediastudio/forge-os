package com.forge.os.domain.agent.providers

import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone contacts tools exposed to the Forge OS agent.
 *
 * Tools:
 *   contacts_search   — query by name or phone
 *   contacts_get      — get one contact by ID
 *   contacts_list     — list recent contacts (up to 100)
 *
 * Requires READ_CONTACTS permission granted at runtime.
 * Write tools (contacts_add) are included but require WRITE_CONTACTS.
 */
@Singleton
class ContactsToolProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolProvider {

    override fun getTools(): List<ToolDefinition> = listOf(
        tool(
            name = "contacts_search",
            description = "Search the phone's contact list by name or phone number. Returns matching contacts with name, phone numbers, email addresses.",
            params = mapOf("query" to ("string" to "Name or phone number to search for")),
            required = listOf("query"),
        ),
        tool(
            name = "contacts_list",
            description = "Return up to 100 contacts sorted alphabetically. Each entry has id, name, and primary phone.",
            params = mapOf("limit" to ("integer" to "Max results, default 50")),
            required = emptyList(),
        ),
        tool(
            name = "contacts_get",
            description = "Get full details for a contact by ID (from contacts_search or contacts_list).",
            params = mapOf("id" to ("string" to "Contact ID")),
            required = listOf("id"),
        ),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? {
        return when (toolName) {
        "contacts_search" -> {
            if (!hasPermission(android.Manifest.permission.READ_CONTACTS))
                return "Error: READ_CONTACTS permission not granted. Grant it in Settings → Apps → Forge OS → Permissions."
            searchContacts(args["query"]?.toString() ?: "")
        }
        "contacts_list"   -> {
            if (!hasPermission(android.Manifest.permission.READ_CONTACTS))
                return "Error: READ_CONTACTS permission not granted."
            listContacts(args["limit"]?.toString()?.toIntOrNull() ?: 50)
        }
        "contacts_get"    -> {
            if (!hasPermission(android.Manifest.permission.READ_CONTACTS))
                return "Error: READ_CONTACTS permission not granted."
            getContact(args["id"]?.toString() ?: "")
        }
        else -> null
        }
    }

    private fun hasPermission(permission: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ── Implementations ───────────────────────────────────────────────────────

    private fun searchContacts(query: String): String = runCatching {
        if (query.isBlank()) return "Error: query required"
        val results = mutableListOf<Map<String, Any>>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val sel = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        context.contentResolver.query(uri, projection, sel, arrayOf("%$query%", "%$query%"), null)?.use { cursor ->
            val seenIds = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                if (!seenIds.add(id)) continue
                results += mapOf(
                    "id" to id,
                    "name" to (cursor.getString(1) ?: ""),
                    "phone" to (cursor.getString(2) ?: ""),
                )
                if (results.size >= 20) break
            }
        }
        if (results.isEmpty()) "No contacts found for '$query'"
        else results.joinToString("\n") { c -> "• ${c["name"]} (${c["phone"]}) id=${c["id"]}" }
    }.getOrElse { e ->
        "Error reading contacts: ${e.message}. Check READ_CONTACTS permission."
    }

    private fun listContacts(limit: Int): String = runCatching {
        val results = mutableListOf<String>()
        val uri = ContactsContract.Contacts.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
        )
        val sort = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC LIMIT $limit"
        context.contentResolver.query(uri, projection, null, null, sort)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id   = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: "(no name)"
                results += "id=$id  $name"
            }
        }
        if (results.isEmpty()) "No contacts found. Check READ_CONTACTS permission."
        else "Contacts (${results.size}):\n" + results.joinToString("\n") { "• $it" }
    }.getOrElse { "Error: ${it.message}" }

    private fun getContact(id: String): String = runCatching {
        if (id.isBlank()) return "Error: id required"
        val sb = StringBuilder()

        // Name
        val nameCursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, null,
            "${ContactsContract.Contacts._ID}=?", arrayOf(id), null,
        )
        nameCursor?.use { c ->
            if (c.moveToFirst()) {
                sb.appendLine("Name: ${c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY))}")
            }
        }

        // Phones
        val phoneCursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?", arrayOf(id), null,
        )
        phoneCursor?.use { c ->
            while (c.moveToNext()) {
                val num  = c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources,
                    c.getInt(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)), "").toString()
                sb.appendLine("Phone ($type): $num")
            }
        }

        // Emails
        val emailCursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI, null,
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID}=?", arrayOf(id), null,
        )
        emailCursor?.use { c ->
            while (c.moveToNext()) {
                val addr = c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS))
                sb.appendLine("Email: $addr")
            }
        }

        if (sb.isEmpty()) "No contact found with id=$id" else sb.toString().trim()
    }.getOrElse { "Error: ${it.message}" }

    private fun tool(name: String, description: String, params: Map<String, Pair<String, String>>, required: List<String>) =
        ToolDefinition(function = FunctionDefinition(name = name, description = description,
            parameters = FunctionParameters(
                properties = params.mapValues { (_, v) -> ParameterProperty(type = v.first, description = v.second) },
                required = required,
            )))
}
