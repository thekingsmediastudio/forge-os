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
    val redacted: Boolean = false,  // true when secret values were stripped from args
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

    /** Parameter names that likely hold secrets — values are replaced with "[REDACTED]". */
    private val secretParamNames = setOf(
        "token", "key", "secret", "password", "pat", "api_key", "apikey",
        "auth", "bearer", "credential", "private_key", "access_token",
        "refresh_token", "session_token", "secret_names",
    )

    /** Tools whose args may contain longer payloads that are security-relevant. */
    private val verboseAuditTools = setOf(
        "shell_exec", "python_run", "config_write", "git_push", "git_clone",
        "plugin_install", "plugin_create", "http_fetch", "curl_exec",
    )

    private val _entries = MutableStateFlow<List<ToolAuditEntry>>(loadFromDisk())
    val entries: StateFlow<List<ToolAuditEntry>> = _entries.asStateFlow()

    /**
     * Record an audit entry, redacting secret values from args before persisting.
     */
    fun record(entry: ToolAuditEntry) {
        val sanitized = sanitizeEntry(entry)
        try {
            file.appendText(json.encodeToString(sanitized) + "\n")
        } catch (e: Exception) {
            Timber.e(e, "ToolAuditLog: append failed")
        }
        val next = (listOf(sanitized) + _entries.value).take(maxInMemory)
        _entries.value = next
    }

    /**
     * Redact known secret parameter values from the args JSON string.
     * Returns a copy of the entry with secrets replaced and `redacted=true` if any were found.
     */
    private fun sanitizeEntry(entry: ToolAuditEntry): ToolAuditEntry {
        var args = entry.args
        var wasRedacted = false

        for (param in secretParamNames) {
            // Match "param":"value" or "param": "value" in JSON-like strings
            val pattern = Regex("""("$param"\s*:\s*")([^"]*)(")""", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(args)) {
                args = pattern.replace(args, """$1[REDACTED]$3""")
                wasRedacted = true
            }
        }

        // Use longer truncation for security-relevant tools
        val maxArgsLen = if (entry.toolName in verboseAuditTools) 1000 else 400
        val truncatedArgs = if (args.length > maxArgsLen) args.take(maxArgsLen) + "…" else args

        return entry.copy(args = truncatedArgs, redacted = wasRedacted)
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
