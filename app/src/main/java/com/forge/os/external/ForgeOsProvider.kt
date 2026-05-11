package com.forge.os.external

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import com.forge.os.domain.agent.ToolRegistry
import com.forge.os.domain.memory.MemoryManager
import com.forge.os.domain.plugins.PluginManager
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * Read-mostly ContentProvider surface. Useful for Tasker / Automate-style automations
 * that already speak content://.
 *
 *   content://com.forge.os.provider/tools         — list of tool names + descriptions
 *   content://com.forge.os.provider/memory/<key>  — single memory value
 *   content://com.forge.os.provider/skills        — list of installed skill ids
 *
 * Same authorisation as the AIDL service via [ExternalApiBridge].
 */
class ForgeOsProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderEntryPoint {
        fun bridge(): ExternalApiBridge
        fun registry(): ExternalCallerRegistry
        fun toolRegistry(): ToolRegistry
        fun memoryManager(): MemoryManager
        fun pluginManager(): PluginManager
    }

    private val auth = "com.forge.os.provider"
    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(auth, "tools", 1)
        addURI(auth, "skills", 2)
        addURI(auth, "memory/*", 3)
    }

    private val ep get() = EntryPoints.get(context!!.applicationContext, ProviderEntryPoint::class.java)

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?,
    ): Cursor? {
        val uid = Binder.getCallingUid()
        return when (matcher.match(uri)) {
            1 -> {
                val d = ep.bridge().authorize(uid, "listTools")
                if (d !is ExternalApiBridge.Decision.Allow) return errCursor(d as ExternalApiBridge.Decision.Deny)
                val mc = MatrixCursor(arrayOf("name", "description"))
                ep.toolRegistry().getDefinitions().forEach { t ->
                    mc.addRow(arrayOf<Any?>(t.function.name, t.function.description))
                }
                mc
            }
            2 -> {
                val d = ep.bridge().authorize(uid, "runSkill")
                if (d !is ExternalApiBridge.Decision.Allow) return errCursor(d as ExternalApiBridge.Decision.Deny)
                val mc = MatrixCursor(arrayOf("id", "name"))
                ep.pluginManager().listAllTools().forEach { (_, tool) ->
                    mc.addRow(arrayOf<Any?>(tool.name, tool.name))
                }
                mc
            }
            3 -> {
                val key = uri.lastPathSegment ?: return null
                val d = ep.bridge().authorize(uid, "getMemory", key)
                if (d !is ExternalApiBridge.Decision.Allow) return errCursor(d as ExternalApiBridge.Decision.Deny)
                val mc = MatrixCursor(arrayOf("key", "value"))
                mc.addRow(arrayOf<Any?>(key, ep.bridge().getMemory(d.caller, key)))
                mc
            }
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (matcher.match(uri) != 3 || values == null) return null
        val key = uri.lastPathSegment ?: return null
        val d = ep.bridge().authorize(Binder.getCallingUid(), "putMemory", key)
        if (d !is ExternalApiBridge.Decision.Allow) return null
        val value = values.getAsString("value") ?: ""
        val tags = values.getAsString("tags") ?: ""
        ep.bridge().putMemory(d.caller, key, value, tags)
        return uri
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun getType(uri: Uri): String? = "vnd.android.cursor.item/forge"

    private fun errCursor(d: ExternalApiBridge.Decision.Deny): Cursor =
        MatrixCursor(arrayOf("error_code", "error_message")).apply {
            addRow(arrayOf<Any?>(d.code, d.reason))
        }
}
