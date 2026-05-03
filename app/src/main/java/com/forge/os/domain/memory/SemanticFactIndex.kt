package com.forge.os.domain.memory

import android.content.Context
import com.forge.os.data.api.AiApiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Phase J2 — Semantic fact index.
 *
 * Sits next to [LongtermMemory] and gives every fact a vector embedding so we can
 * answer queries by *meaning* instead of substring overlap. The actual fact
 * content remains owned by [LongtermMemory] — this class only stores
 * `key → (contentHash, FloatArray)` pairs in `workspace/memory/longterm/embeddings.json`.
 *
 * Embedding strategy (in order of preference):
 *   1. Remote — uses [AiApiManager.embed]; routes via the user's configured
 *      `memory_embedding` provider/model in [com.forge.os.domain.config.ModelRoutingConfig].
 *   2. Local fallback — deterministic 256-dim hashed character-trigram vector.
 *      Always available, zero network, zero key.
 *
 * Both paths produce an L2-normalised vector so cosine similarity reduces to a
 * dot product. They are NOT compatible with each other — if a remote model is
 * unreachable mid-session we mark the fact as locally-embedded and re-embed
 * lazily next time the remote model is available.
 */
@Singleton
class SemanticFactIndex @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiManager: AiApiManager,
) {
    @Serializable
    private data class Slot(
        val contentHash: String,
        val vector: FloatArray,
        val source: String,    // "remote" | "local"
        val model: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val file: File get() =
        context.filesDir.resolve("workspace/memory/longterm/embeddings.json")

    private val mutex = Mutex()
    private var cache: MutableMap<String, Slot> = mutableMapOf()
    private var loaded = false

    private fun loadIfNeeded() {
        if (loaded) return
        cache = try {
            if (file.exists()) {
                json.decodeFromString<Map<String, Slot>>(file.readText()).toMutableMap()
            } else mutableMapOf()
        } catch (e: Exception) {
            Timber.w(e, "SemanticFactIndex: load failed; starting empty")
            mutableMapOf()
        }
        loaded = true
    }

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(cache as Map<String, Slot>))
        } catch (e: Exception) {
            Timber.e(e, "SemanticFactIndex: persist failed")
        }
    }

    fun delete(key: String) {
        loadIfNeeded()
        if (cache.remove(key) != null) persist()
    }

    fun deleteAll() {
        cache.clear(); loaded = true; persist()
    }

    /** Reports the slot count and how many were embedded remotely vs locally. */
    fun summary(): String {
        loadIfNeeded()
        val n = cache.size
        val remote = cache.values.count { it.source == "remote" }
        return "Semantic facts: $n indexed (remote=$remote, local=${n - remote})"
    }

    /**
     * Idempotently bring the index up-to-date with the supplied facts. New keys
     * get embedded, changed contents get re-embedded, and removed keys get
     * dropped. Falls back to local embeddings on any remote failure.
     *
     * Proactively attempts to upgrade "local" facts to "remote" if a remote
     * provider has become available.
     *
     * Safe to call on any thread; the heavy lifting is done off the caller's
     * thread when the API is involved.
     */
    suspend fun reindex(facts: Map<String, FactEntry>) = mutex.withLock {
        loadIfNeeded()
        val obsolete = cache.keys - facts.keys
        obsolete.forEach { cache.remove(it) }

        val remoteAvailable = apiManager.embeddingModelLabel() != "local"
        val needRemote = mutableListOf<Pair<String, String>>()
        
        for ((key, entry) in facts) {
            val slot = cache[key]
            val h = computeHash(entry.content)
            
            // Re-embed if:
            // 1. No slot exists
            // 2. Content hash changed
            // 3. Current slot is local but remote has become available (upgrade path)
            val needsUpdate = slot == null || slot.contentHash != h || (slot.source == "local" && remoteAvailable)
            
            if (needsUpdate) {
                needRemote += key to entry.content
            }
        }
        
        if (needRemote.isEmpty()) { 
            if (obsolete.isNotEmpty()) persist()
            return@withLock 
        }

        val texts = needRemote.map { it.second }
        val remoteVecs = if (remoteAvailable) {
            try {
                apiManager.embed(texts)
            } catch (e: Exception) {
                Timber.w(e, "SemanticFactIndex: remote embed failed; using local fallback for this batch")
                null
            }
        } else null

        if (remoteVecs != null && remoteVecs.size == needRemote.size) {
            val model = apiManager.embeddingModelLabel()
            needRemote.forEachIndexed { i, (key, text) ->
                cache[key] = Slot(
                    contentHash = computeHash(text),
                    vector = normalise(remoteVecs[i]),
                    source = "remote",
                    model = model,
                )
            }
        } else {
            needRemote.forEach { (key, text) ->
                // If it was already in cache and we were just trying to upgrade, 
                // don't overwrite with local if remote failed unless content changed.
                val existing = cache[key]
                val h = computeHash(text)
                if (existing == null || existing.contentHash != h) {
                    cache[key] = Slot(
                        contentHash = h,
                        vector = localEmbed(text),
                        source = "local",
                    )
                }
            }
        }
        persist()
    }

    /**
     * Rank the supplied facts by semantic similarity to [query]. The caller is
     * responsible for having called [reindex] first — we won't auto-embed here
     * to avoid surprise network calls from the UI thread.
     *
     * @return up to [k] (key, score) pairs, score in [0, 1], descending.
     */
    suspend fun recall(query: String, k: Int = 5): List<Pair<String, Float>> = mutex.withLock {
        loadIfNeeded()
        if (cache.isEmpty() || query.isBlank()) return@withLock emptyList()

        val remoteAvailable = cache.values.any { it.source == "remote" }
        val qVec = if (remoteAvailable) {
            val r = try { apiManager.embed(listOf(query))?.firstOrNull() } catch (_: Exception) { null }
            r?.let { normalise(it) } ?: localEmbed(query)
        } else localEmbed(query)

        // Only score against slots produced the same way the query was — mixing
        // remote and local vectors is meaningless because the spaces differ.
        val querySource = if (remoteAvailable && qVec.size != LOCAL_DIM) "remote" else "local"

        return@withLock cache.entries
            .asSequence()
            .filter { it.value.source == querySource }
            .map { (key, slot) -> key to dot(qVec, slot.vector) }
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
            .take(k)
            .toList()
    }

    // ─── Math ────────────────────────────────────────────────────────────────

    private fun computeHash(text: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            text.hashCode().toString()
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s.toFloat().coerceIn(-1f, 1f)
    }

    private fun normalise(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        n = kotlin.math.sqrt(n)
        if (n == 0.0) return v
        val out = FloatArray(v.size)
        val inv = (1.0 / n).toFloat()
        for (i in v.indices) out[i] = v[i] * inv
        return out
    }

    /**
     * Hashed character-trigram embedding. Deterministic, no network, no key.
     * 256 dims is plenty for the modest scale we're dealing with (≤500 facts)
     * and keeps the on-disk JSON small.
     */
    private fun localEmbed(text: String): FloatArray {
        val v = FloatArray(LOCAL_DIM)
        val s = " " + text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() + " "
        if (s.length < 3) return v
        for (i in 0..s.length - 3) {
            val tri = s.substring(i, i + 3)
            val h = (tri.hashCode() and Int.MAX_VALUE) % LOCAL_DIM
            // Sign hash so we don't only ever add — gives the vector real spread.
            val sign = if ((tri.hashCode() ushr 31) == 0) 1f else -1f
            v[h] += sign
        }
        return normalise(v)
    }

    private companion object {
        const val LOCAL_DIM = 256
    }
}
