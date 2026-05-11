package com.forge.os.domain.proactive

import com.forge.os.domain.agent.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Short-lived, in-memory cache for proactively executed tool results.
 *
 * The [PatternAnalyzer] predicts likely tool calls and the [ProactiveWorker]
 * executes them ahead of time. Results are stored here keyed by a hash of
 * `(toolName, argsJson)`. When [ToolRegistry.dispatch] is called for the
 * same tool+args combination it can transparently return the cached result
 * instead of running the tool again.
 *
 * Entries automatically expire after [TTL_MS] (default 1 hour).
 */
@Singleton
class PrefetchCache @Inject constructor(
    // Enhanced Integration: Connect with learning systems
    private val reflectionManager: com.forge.os.domain.agent.ReflectionManager
) {

    companion object {
        /** How long a prefetched result stays valid. */
        const val TTL_MS: Long = 60 * 60 * 1000L // 1 hour
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private data class Entry(
        val result: ToolResult,
        val storedAt: Long,
    )

    private val cache = mutableMapOf<String, Entry>()

    /**
     * Store a tool result for later retrieval.
     * Silently replaces any existing entry for the same key.
     */
    fun put(toolName: String, argsJson: String, result: ToolResult) {
        val key = hash(toolName, argsJson)
        synchronized(cache) {
            cache[key] = Entry(result, System.currentTimeMillis())
        }
        Timber.d("PrefetchCache: stored result for $toolName (key=${key.take(12)}…)")
    }

    /**
     * Retrieve a previously prefetched result if it exists and has not expired.
     * Returns `null` when there is no valid cached entry. A successful hit
     * removes the entry (single-use).
     */
    fun getAndRemove(toolName: String, argsJson: String): ToolResult? {
        val key = hash(toolName, argsJson)
        synchronized(cache) {
            val entry = cache[key] ?: return null
            val age = System.currentTimeMillis() - entry.storedAt
            if (age > TTL_MS) {
                cache.remove(key)
                Timber.d("PrefetchCache: expired entry for $toolName (age=${age}ms)")
                return null
            }
            cache.remove(key)
            Timber.d("PrefetchCache: HIT for $toolName (age=${age}ms)")
            
            // Enhanced Integration: Record successful prefetch hit for learning
            scope.launch {
                try {
                    reflectionManager.recordPattern(
                        pattern = "Successful prefetch hit: $toolName",
                        description = "Prefetch cache successfully predicted and cached result for $toolName (age=${age}ms)",
                        applicableTo = listOf("prefetch", "prediction_accuracy", toolName),
                        tags = listOf("prefetch_hit", "prediction_success", "cache_efficiency", "proactive_success")
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to record prefetch hit pattern")
                }
            }
            
            return entry.result
        }
    }

    /** Evict all expired entries. Called periodically by ProactiveWorker. */
    fun evictExpired() {
        val now = System.currentTimeMillis()
        synchronized(cache) {
            val expired = cache.entries.filter { now - it.value.storedAt > TTL_MS }
            expired.forEach { cache.remove(it.key) }
            if (expired.isNotEmpty()) {
                Timber.d("PrefetchCache: evicted ${expired.size} expired entries")
            }
        }
    }

    /** Number of entries currently cached (for diagnostics). */
    fun size(): Int = synchronized(cache) { cache.size }

    // ── Internal ────────────────────────────────────────────────────────────

    /**
     * Deterministic hash of `(toolName, argsJson)`.
     * Normalises the args JSON by sorting keys so that `{"a":1,"b":2}` and
     * `{"b":2,"a":1}` produce the same key.
     */
    private fun hash(toolName: String, argsJson: String): String {
        // Simple normalisation: lowercase tool name, trim whitespace from args
        val raw = "$toolName|${argsJson.trim()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
