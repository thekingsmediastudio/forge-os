package com.forge.os.domain.memory

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_FACTS = 500
private const val PRUNE_TO = 400

/**
 * Tier 2: Long-term structured fact store.
 * Persisted to workspace/memory/longterm/facts.json as a key→FactEntry map.
 * Supports tag-based grouping, access-count tracking, and LRU pruning.
 */
@Singleton
class LongtermMemory @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
    private val factsFile: File get() = context.filesDir.resolve("workspace/memory/longterm/facts.json")
    private var cache: MutableMap<String, FactEntry> = mutableMapOf()
    private var dirty = false

    init {
        load()
    }

    private fun load() {
        cache = try {
            if (factsFile.exists()) {
                json.decodeFromString<Map<String, FactEntry>>(factsFile.readText()).toMutableMap()
            } else {
                mutableMapOf()
            }
        } catch (e: Exception) {
            Timber.e(e, "LongtermMemory: load failed")
            mutableMapOf()
        }
    }

    @Synchronized
    fun store(key: String, content: String, tags: List<String> = emptyList(), source: String = "agent") {
        val existing = cache[key]
        cache[key] = FactEntry(
            key = key,
            content = content,
            timestamp = System.currentTimeMillis(),
            accessCount = (existing?.accessCount ?: 0),
            tags = tags,
            source = source
        )
        dirty = true
        if (cache.size > MAX_FACTS) prune()
        persist()
    }

    @Synchronized
    fun recall(key: String): FactEntry? {
        val entry = cache[key] ?: return null
        cache[key] = entry.copy(accessCount = entry.accessCount + 1)
        dirty = true
        return cache[key]
    }

    fun getAll(): Map<String, FactEntry> = cache.toMap()

    fun delete(key: String): Boolean {
        return if (cache.remove(key) != null) {
            persist()
            true
        } else false
    }

    fun filterByTags(tags: List<String>): List<FactEntry> =
        cache.values.filter { entry -> tags.any { it in entry.tags } }

    private fun prune() {
        // Remove lowest-access entries to stay under quota
        val sorted = cache.values.sortedBy { it.accessCount }
        val toRemove = sorted.take(cache.size - PRUNE_TO)
        toRemove.forEach { cache.remove(it.key) }
        Timber.i("LongtermMemory: pruned ${toRemove.size} entries")
    }

    @Synchronized
    fun persist() {
        if (!dirty) return
        try {
            factsFile.parentFile?.mkdirs()
            factsFile.writeText(json.encodeToString(cache as Map<String, FactEntry>))
            dirty = false
        } catch (e: Exception) {
            Timber.e(e, "LongtermMemory: persist failed")
        }
    }

    fun summary(): String {
        val count = cache.size
        val topTags = cache.values.flatMap { it.tags }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(5).map { it.key }
        return "Long-term facts: $count entries. Top tags: ${topTags.joinToString()}"
    }
}
