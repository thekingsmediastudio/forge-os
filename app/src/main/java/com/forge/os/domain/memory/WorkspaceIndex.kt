package com.forge.os.domain.memory

import android.content.Context
import com.forge.os.data.api.AiApiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Serializable
data class WorkspaceEntry(
    val path: String,
    val content: String,
    val lastModified: Long,
    val projectSlug: String,
    val chunkIndex: Int = 0
)

@Serializable
data class WorkspaceSlot(
    val entry: WorkspaceEntry,
    val vector: FloatArray,
    val model: String
)

@Singleton
class WorkspaceIndex @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiManager: AiApiManager
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val file: File get() = context.filesDir.resolve("workspace/system/workspace_index.json")
    private val mutex = Mutex()
    private var index: MutableList<WorkspaceSlot> = mutableListOf()
    private var loaded = false

    private fun loadIfNeeded() {
        if (loaded) return
        index = try {
            if (file.exists()) {
                json.decodeFromString<List<WorkspaceSlot>>(file.readText()).toMutableList()
            } else mutableListOf()
        } catch (e: Exception) {
            Timber.w(e, "WorkspaceIndex: load failed")
            mutableListOf()
        }
        loaded = true
    }

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(index.toList()))
        } catch (e: Exception) {
            Timber.e(e, "WorkspaceIndex: persist failed")
        }
    }

    suspend fun updateEntry(entry: WorkspaceEntry) = mutex.withLock {
        loadIfNeeded()
        
        // Remove existing chunks for this path to avoid duplicates
        index.removeAll { it.entry.path == entry.path && it.entry.chunkIndex == entry.chunkIndex }

        val vecs = try {
            apiManager.embed(listOf(entry.content))
        } catch (e: Exception) {
            Timber.w(e, "WorkspaceIndex: embed failed for ${entry.path}")
            null
        } ?: return@withLock

        index.add(WorkspaceSlot(
            entry = entry,
            vector = vecs.first(),
            model = apiManager.embeddingModelLabel()
        ))
        
        // Cap index size to 10k segments for performance
        if (index.size > 10000) {
            index.removeAt(0)
        }
        
        persist()
    }

    suspend fun search(query: String, projectSlug: String? = null, k: Int = 5): List<WorkspaceEntry> = mutex.withLock {
        loadIfNeeded()
        if (index.isEmpty() || query.isBlank()) return@withLock emptyList()

        val qVec = try {
            apiManager.embed(listOf(query))?.firstOrNull()
        } catch (e: Exception) { null } ?: return@withLock emptyList()

        return@withLock index.asSequence()
            .filter { projectSlug == null || it.entry.projectSlug == projectSlug }
            .map { it to dot(qVec, it.vector) }
            .filter { it.second > 0.45f } // Slightly lower threshold for code RAG
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
    
    suspend fun clearProject(slug: String) = mutex.withLock {
        loadIfNeeded()
        index.removeAll { it.entry.projectSlug == slug }
        persist()
    }
}
