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
    // Enhanced Integration: Connect with other systems
    private val memoryManager: MemoryManager,
    private val reflectionManager: com.forge.os.domain.agent.ReflectionManager,
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
        
        // Enhanced Integration: Cross-system learning from conversation indexing
        try {
            // Store important conversations in long-term memory
            if (entry.text.length > 100 && (entry.text.contains("remember") || entry.text.contains("important"))) {
                memoryManager.store(
                    key = "conversation_${entry.channel}_${System.currentTimeMillis()}",
                    content = "${entry.sender}: ${entry.text}",
                    tags = listOf("conversation", entry.channel, entry.sender, "indexed")
                )
            }
            
            // Learn conversation patterns
            reflectionManager.recordPattern(
                pattern = "Conversation indexed: ${entry.channel}",
                description = "Indexed ${entry.sender} message in ${entry.channel}: ${entry.text.take(50)}",
                applicableTo = listOf("conversation_indexing", entry.channel, "communication"),
                tags = listOf("conversation_index", "communication_pattern", entry.channel, entry.sender)
            )
            
            // Advanced conversation analysis and learning
            val messageWords = entry.text.lowercase().split(" ")
            
            // Learn topic patterns
            val topics = listOf("project", "code", "bug", "feature", "deploy", "test", "api", "database", "ui", "performance")
            topics.forEach { topic ->
                if (messageWords.contains(topic)) {
                    reflectionManager.recordPattern(
                        pattern = "Topic discussion: $topic in ${entry.channel}",
                        description = "${entry.sender} discussed $topic: ${entry.text.take(100)}",
                        applicableTo = listOf("topic_$topic", entry.channel, "discussion"),
                        tags = listOf("topic_analysis", topic, entry.channel, entry.sender)
                    )
                }
            }
            
            // Learn communication style patterns
            if (entry.sender == "user") {
                val messageLength = when {
                    entry.text.length < 50 -> "short"
                    entry.text.length < 200 -> "medium"
                    else -> "long"
                }
                
                reflectionManager.recordPattern(
                    pattern = "User communication style: $messageLength messages in ${entry.channel}",
                    description = "User prefers $messageLength messages in ${entry.channel}",
                    applicableTo = listOf("communication_style", entry.channel, "user_preference"),
                    tags = listOf("communication_analysis", "user_style", messageLength, entry.channel)
                )
            }
            
            // Learn question patterns
            if (entry.text.contains("?") || entry.text.lowercase().startsWith("how") || 
                entry.text.lowercase().startsWith("what") || entry.text.lowercase().startsWith("why")) {
                reflectionManager.recordPattern(
                    pattern = "Question asked in ${entry.channel}",
                    description = "${entry.sender} asked: ${entry.text.take(100)}",
                    applicableTo = listOf("questions", entry.channel, "help_seeking"),
                    tags = listOf("question_pattern", entry.channel, entry.sender)
                )
            }
            
        } catch (e: Exception) {
            Timber.w(e, "Failed to record conversation indexing patterns")
        }
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
