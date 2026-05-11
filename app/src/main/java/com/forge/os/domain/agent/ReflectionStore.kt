package com.forge.os.domain.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Isolated, deduplicated storage for ReflectionManager patterns.
 *
 * Kept completely separate from LongtermMemory so reflection noise never
 * pollutes the user's fact space. Patterns are deduplicated by name —
 * recording the same pattern twice just increments [StoredPattern.useCount]
 * and updates [StoredPattern.lastSeen] rather than creating a new entry.
 *
 * Persisted to: workspace/memory/reflection/patterns.json
 * Max entries: [MAX_PATTERNS] (oldest/least-used pruned beyond that)
 */
@Singleton
class ReflectionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
    private val file: File
        get() = context.filesDir.resolve("workspace/memory/reflection/patterns.json")

    private val mutex = Mutex()
    private var cache: MutableMap<String, StoredPattern> = mutableMapOf()
    private var loaded = false

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Upsert a pattern. If [name] already exists, increments useCount and
     * updates lastSeen + description. Never creates duplicates.
     */
    suspend fun upsert(
        name: String,
        description: String,
        applicableTo: List<String> = emptyList(),
        tags: List<String> = emptyList(),
    ) = mutex.withLock {
        ensureLoaded()
        val existing = cache[name]
        cache[name] = StoredPattern(
            name        = name,
            description = description,
            applicableTo = applicableTo,
            tags        = tags,
            firstSeen   = existing?.firstSeen ?: System.currentTimeMillis(),
            lastSeen    = System.currentTimeMillis(),
            useCount    = (existing?.useCount ?: 0) + 1,
        )
        if (cache.size > MAX_PATTERNS) prune()
        persist()
    }

    /** Store a failure/recovery entry (also deduplicated by taskId). */
    suspend fun upsertRecovery(
        taskId: String,
        failureReason: String,
        recoveryStrategy: String,
    ) = upsert(
        name        = "recovery:$taskId",
        description = "Failure: $failureReason | Recovery: $recoveryStrategy",
        tags        = listOf("failure", "recovery"),
    )

    /** Store an execution trace summary (deduplicated by taskId). */
    suspend fun upsertExecution(
        taskId: String,
        goal: String,
        success: Boolean,
        outcome: String,
        tags: List<String> = emptyList(),
    ) = upsert(
        name        = "execution:$taskId",
        description = "Goal: $goal | Success: $success | Outcome: ${outcome.take(120)}",
        tags        = listOf("execution", "reflection") + tags,
    )

    /** Return all stored patterns, newest first. */
    suspend fun getAll(): List<StoredPattern> = mutex.withLock {
        ensureLoaded()
        cache.values.sortedByDescending { it.lastSeen }
    }

    /** Return patterns whose tags or applicableTo overlap with [query]. */
    suspend fun getRelevant(query: String, limit: Int = 10): List<StoredPattern> = mutex.withLock {
        ensureLoaded()
        val q = query.lowercase()
        cache.values
            .filter { p ->
                p.name.lowercase().contains(q) ||
                p.applicableTo.any { it.lowercase().contains(q) } ||
                p.tags.any { it.lowercase().contains(q) }
            }
            .sortedByDescending { it.useCount }
            .take(limit)
    }

    /** Return patterns tagged with "execution". */
    suspend fun getExecutions(limit: Int = 5): List<StoredPattern> = mutex.withLock {
        ensureLoaded()
        cache.values
            .filter { "execution" in it.tags }
            .sortedByDescending { it.lastSeen }
            .take(limit)
    }

    /** Return patterns tagged with "failure". */
    suspend fun getRecoveries(failureType: String, limit: Int = 5): List<StoredPattern> = mutex.withLock {
        ensureLoaded()
        val q = failureType.lowercase()
        cache.values
            .filter { "failure" in it.tags && it.description.lowercase().contains(q) }
            .sortedByDescending { it.useCount }
            .take(limit)
    }

    fun summary(): String {
        val n = cache.size
        val topUsed = cache.values.sortedByDescending { it.useCount }.take(3).map { it.name }
        return "Reflection patterns: $n stored. Most used: ${topUsed.joinToString()}"
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun ensureLoaded() {
        if (loaded) return
        cache = try {
            if (file.exists()) {
                json.decodeFromString<Map<String, StoredPattern>>(file.readText()).toMutableMap()
            } else mutableMapOf()
        } catch (e: Exception) {
            Timber.w(e, "ReflectionStore: load failed, starting empty")
            mutableMapOf()
        }
        loaded = true
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(cache as Map<String, StoredPattern>))
        } catch (e: Exception) {
            Timber.e(e, "ReflectionStore: persist failed")
        }
    }

    /** Keep only the [MAX_PATTERNS] most-used entries. */
    private fun prune() {
        val toRemove = cache.values
            .sortedBy { it.useCount }
            .take(cache.size - PRUNE_TO)
        toRemove.forEach { cache.remove(it.name) }
        Timber.i("ReflectionStore: pruned ${toRemove.size} entries")
    }

    private companion object {
        const val MAX_PATTERNS = 200
        const val PRUNE_TO     = 150
    }
}

@Serializable
data class StoredPattern(
    val name: String,
    val description: String,
    val applicableTo: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val useCount: Int = 1,
)
