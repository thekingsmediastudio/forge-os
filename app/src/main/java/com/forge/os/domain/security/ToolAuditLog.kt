package com.forge.os.domain.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Append-only audit trail of every tool dispatch. Backed by
 * `workspace/system/tool_audit.jsonl` with an in-memory ring buffer of the
 * most recent entries for fast UI reads. The Phase D plan called for a Room
 * table; we mirror the file-based pattern used by `ApiCallLog` / cron history
 * to avoid pulling Room into a UI-only feature.
 */
@Serializable
data class ToolAuditEntry(
    val id: String,
    val timestamp: Long,
    val toolName: String,
    val args: String,
    val success: Boolean,
    val durationMs: Long,
    val outputPreview: String,
    val source: String = "agent",   // agent | user | cron | plugin
)

@Singleton
class ToolAuditLog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val file: File get() = context.filesDir.resolve("workspace/system/tool_audit.jsonl").also {
        it.parentFile?.mkdirs()
    }
    private val maxInMemory = 300

    private val _entries = MutableStateFlow<List<ToolAuditEntry>>(loadFromDisk())
    val entries: StateFlow<List<ToolAuditEntry>> = _entries.asStateFlow()

    fun record(entry: ToolAuditEntry) {
        try {
            file.appendText(json.encodeToString(entry) + "\n")
        } catch (e: Exception) {
            Timber.e(e, "ToolAuditLog: append failed")
        }
        val next = (listOf(entry) + _entries.value).take(maxInMemory)
        _entries.value = next
    }

    fun clear() {
        try { if (file.exists()) file.writeText("") } catch (_: Exception) {}
        _entries.value = emptyList()
    }

    private fun loadFromDisk(): List<ToolAuditEntry> {
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().asReversed().take(maxInMemory).mapNotNull {
                runCatching { json.decodeFromString<ToolAuditEntry>(it) }.getOrNull()
            }
        } catch (e: Exception) {
            Timber.e(e, "ToolAuditLog: load failed")
            emptyList()
        }
    }
}
