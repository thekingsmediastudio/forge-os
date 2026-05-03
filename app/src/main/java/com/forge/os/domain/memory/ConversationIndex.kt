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

@Serializable
data class ConversationEntry(
    val channel: String,    // "main" | "telegram" | "subagent"
    val sender: String,     // "user" | "agent"
    val text: String,
    val timestamp: Long,
    val sessionId: String = ""
)

@Singleton
class ConversationIndex @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiManager: AiApiManager,
) {
    @Serializable
    private data class IndexSlot(
        val entry: ConversationEntry,
        val vector: FloatArray,
        val model: String
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val file: File get() =
        context.filesDir.resolve("workspace/system/conversation_index.json")

    private val mutex = Mutex()
    private var index: MutableList<IndexSlot> = mutableListOf()
    private var loaded = false

    private fun loadIfNeeded() {
        if (loaded) return
        index = try {
            if (file.exists()) {
                json.decodeFromString<List<IndexSlot>>(file.readText()).toMutableList()
            } else mutableListOf()
        } catch (e: Exception) {
            Timber.w(e, "ConversationIndex: load failed")
            mutableListOf()
        }
        loaded = true
    }

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(index.toList()))
        } catch (e: Exception) {
            Timber.e(e, "ConversationIndex: persist failed")
        }
    }

    suspend fun indexMessage(entry: ConversationEntry) = mutex.withLock {
        loadIfNeeded()
        
        // Don't index very short messages
        if (entry.text.length < 10) return@withLock

        val vecs = try {
            apiManager.embed(listOf(entry.text))
        } catch (e: Exception) {
            Timber.w(e, "ConversationIndex: embed failed for message")
            null
        } ?: return@withLock

        index.add(IndexSlot(
            entry = entry,
            vector = vecs.first(),
            model = apiManager.embeddingModelLabel()
        ))
        
        // Keep index size manageable (last 5000 messages)
        if (index.size > 5000) {
            index.removeAt(0)
        }
        
        persist()
    }

    suspend fun search(query: String, channels: Set<String>? = null, k: Int = 5): List<ConversationEntry> = mutex.withLock {
        loadIfNeeded()
        if (index.isEmpty() || query.isBlank()) return@withLock emptyList()

        val qVec = try {
            apiManager.embed(listOf(query))?.firstOrNull()
        } catch (e: Exception) { null } ?: return@withLock emptyList()

        return@withLock index.asSequence()
            .filter { channels == null || it.entry.channel in channels }
            .map { it to dot(qVec, it.vector) }
            .filter { it.second > 0.5f } // Similarity threshold
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first.entry }
            .toList()
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
