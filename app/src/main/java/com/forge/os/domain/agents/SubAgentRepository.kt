package com.forge.os.domain.agents

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Filesystem-backed persistence for sub-agents.
 *
 * Layout:
 *   workspace/agents/<id>/record.json
 *   workspace/agents/<id>/transcript.txt
 *   workspace/agents/<id>/result.txt
 */
@Singleton
class SubAgentRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    val agentsRoot: File get() = context.filesDir.resolve("workspace/agents").apply { mkdirs() }

    fun agentDir(id: String): File = agentsRoot.resolve(id)

    fun save(agent: SubAgent) {
        try {
            val dir = agentDir(agent.id).apply { mkdirs() }
            dir.resolve("record.json").writeText(json.encodeToString(agent))
            agent.result?.let { dir.resolve("result.txt").writeText(it) }
        } catch (e: Exception) {
            Timber.e(e, "SubAgentRepository: save failed for ${agent.id}")
        }
    }

    fun appendTranscript(id: String, line: String) {
        try {
            val dir = agentDir(id).apply { mkdirs() }
            dir.resolve("transcript.txt").appendText(line + "\n")
        } catch (e: Exception) {
            Timber.w(e, "SubAgentRepository: appendTranscript failed for $id")
        }
    }

    fun load(id: String): SubAgent? {
        val f = agentDir(id).resolve("record.json")
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<SubAgent>(f.readText()) }.getOrNull()
    }

    fun loadTranscript(id: String): String =
        agentDir(id).resolve("transcript.txt").let { if (it.exists()) it.readText() else "" }

    fun listAll(): List<SubAgent> {
        return agentsRoot.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir ->
                val rec = dir.resolve("record.json")
                if (!rec.exists()) null
                else runCatching { json.decodeFromString<SubAgent>(rec.readText()) }.getOrNull()
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    fun listActive(): List<SubAgent> = listAll().filter { !it.isTerminal }

    fun delete(id: String): Boolean = agentDir(id).deleteRecursively()

    /** Prune terminal agents older than [retentionDays]. */
    fun pruneOlderThan(retentionDays: Int) {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        listAll().filter { it.isTerminal && it.createdAt < cutoff }.forEach { delete(it.id) }
    }
}
