package com.forge.os.domain.companion

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase J1 — file-backed CRUD for [EpisodicMemory] plus the small
 * "Recent context" prompt-block builder consumed by [com.forge.os.presentation.screens.companion.CompanionViewModel].
 *
 * Storage layout:
 *   workspace/companion/episodes/<id>.json
 *
 * The Companion screen lists episodes via [observe]; the system-prompt
 * builder uses [recentEpisodes] for inline injection.
 */
@Singleton
class EpisodicMemoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private val dir: File by lazy {
        File(context.filesDir, "workspace/companion/episodes").apply { mkdirs() }
    }

    private val _episodes = MutableStateFlow<List<EpisodicMemory>>(loadAll())
    val episodes: StateFlow<List<EpisodicMemory>> = _episodes

    private fun loadAll(): List<EpisodicMemory> {
        if (!dir.exists()) return emptyList()
        return (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { f ->
                try { json.decodeFromString(EpisodicMemory.serializer(), f.readText()) }
                catch (e: Exception) { Timber.w(e, "EpisodicMemoryStore: bad file ${f.name}"); null }
            }
            .sortedByDescending { it.endedAt }
    }

    fun add(ep: EpisodicMemory) {
        try {
            File(dir, "${ep.id}.json").writeText(json.encodeToString(ep))
            _episodes.value = listOf(ep) + _episodes.value.filter { it.id != ep.id }
        } catch (e: Exception) {
            Timber.e(e, "EpisodicMemoryStore.add failed")
        }
    }

    fun delete(id: String) {
        try { File(dir, "$id.json").delete() } catch (_: Exception) {}
        _episodes.value = _episodes.value.filter { it.id != id }
    }

    fun deleteAll() {
        try { dir.listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
        _episodes.value = emptyList()
    }

    fun all(): List<EpisodicMemory> = _episodes.value

    /** Most recent [limit] episodes (newest first), optionally filtered by age. */
    fun recentEpisodes(limit: Int = 5, sinceMs: Long? = null): List<EpisodicMemory> {
        val cutoff = sinceMs ?: 0L
        return _episodes.value.asSequence()
            .filter { it.endedAt >= cutoff }
            .take(limit)
            .toList()
    }

    /**
     * Phase J1-3 — produces a short "RECENT CONTEXT" block for the COMPANION
     * system prompt. Empty string when no episodes exist.
     */
    fun buildContextBlock(limit: Int = 3): String {
        val recent = recentEpisodes(limit)
        if (recent.isEmpty()) return ""
        return buildString {
            appendLine("RECENT CONTEXT (past sessions, newest first):")
            recent.forEachIndexed { i, e ->
                val ago = humanAgo(System.currentTimeMillis() - e.endedAt)
                appendLine("- $ago — ${e.summary.trim()}")
                if (e.keyTopics.isNotEmpty()) {
                    appendLine("  topics: ${e.keyTopics.joinToString(", ")}")
                }
                if (e.moodTrajectory.isNotBlank()) {
                    appendLine("  mood: ${e.moodTrajectory}")
                }
                val pendingFollowUps = e.followUps.filter { fu -> fu.dueAtMs <= System.currentTimeMillis() }
                if (pendingFollowUps.isNotEmpty()) {
                    appendLine("  follow up if it fits: ${pendingFollowUps.joinToString("; ") { it.prompt }}")
                }
                if (i < recent.lastIndex) appendLine()
            }
            appendLine()
            appendLine("Use this naturally if relevant. Do not list it back to the user; weave it in.")
        }.trimEnd()
    }

    private fun humanAgo(ms: Long): String {
        val mins = ms / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 60 * 24 -> "${mins / 60}h ago"
            else -> "${mins / (60 * 24)}d ago"
        }
    }
}
