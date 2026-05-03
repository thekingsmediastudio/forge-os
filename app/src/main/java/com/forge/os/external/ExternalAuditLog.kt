package com.forge.os.external

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ExternalAuditEntry(
    val ts: Long = System.currentTimeMillis(),
    val packageName: String,
    val operation: String,            // "invokeTool" | "askAgent" | "getMemory" | "putMemory" | "runSkill" | "bind" | "deny"
    val target: String = "",          // tool name, skill id, memory key
    val outcome: String = "ok",       // "ok" | "deny" | "error" | "rate_limited"
    val durationMs: Long = 0,
    val outputBytes: Int = 0,
    val message: String = "",
)

/**
 * Append-only JSONL log at workspace/system/external_audit.jsonl.
 * Each line is a self-contained JSON object so cron jobs / shell tools can `tail -f` it.
 */
@Singleton
class ExternalAuditLog @Inject constructor(
    private val context: Context,
) {
    private val file: File by lazy {
        File(context.filesDir, "workspace/system").apply { mkdirs() }
            .resolve("external_audit.jsonl")
    }
    private val json = Json { encodeDefaults = true }

    @Synchronized
    fun record(entry: ExternalAuditEntry) {
        runCatching {
            file.appendText(json.encodeToString(ExternalAuditEntry.serializer(), entry) + "\n")
        }.onFailure { Timber.w(it, "ExternalAuditLog: append failed") }
    }

    /** Last [n] entries, newest first. */
    fun tail(n: Int = 200): List<ExternalAuditEntry> = runCatching {
        if (!file.exists()) return emptyList()
        file.readLines()
            .takeLast(n)
            .reversed()
            .mapNotNull { line ->
                runCatching { json.decodeFromString(ExternalAuditEntry.serializer(), line) }.getOrNull()
            }
    }.getOrDefault(emptyList())

    fun clear() { runCatching { file.writeText("") } }
}
