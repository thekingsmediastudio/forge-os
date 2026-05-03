package com.forge.os.domain.memory

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val SKILLS_FILE = "workspace/memory/skills/skills.jsonl"
private const val MAX_SKILLS = 200

/**
 * Tier 3: Skill snippet memory.
 * Stores named Python/shell patterns the agent has found useful.
 * Persisted as JSONL; each line is a SkillEntry.
 * The agent can store, recall, and execute learned skill snippets.
 */
@Singleton
class SkillMemory @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val skillsFile: File get() = context.filesDir.resolve(SKILLS_FILE)
    private var skills: MutableList<SkillEntry> = mutableListOf()

    init {
        load()
    }

    private fun load() {
        skills = try {
            if (skillsFile.exists()) {
                skillsFile.readLines()
                    .mapNotNull { runCatching { json.decodeFromString<SkillEntry>(it) }.getOrNull() }
                    .toMutableList()
            } else {
                mutableListOf()
            }
        } catch (e: Exception) {
            Timber.e(e, "SkillMemory: load failed")
            mutableListOf()
        }
    }

    @Synchronized
    fun store(name: String, description: String, code: String, tags: List<String> = emptyList()) {
        val existing = skills.indexOfFirst { it.name == name }
        val entry = SkillEntry(name, description, code, tags = tags,
            useCount = if (existing >= 0) skills[existing].useCount else 0)
        if (existing >= 0) skills[existing] = entry else skills.add(entry)
        if (skills.size > MAX_SKILLS) {
            skills.sortByDescending { it.useCount }
            skills.dropLast(skills.size - MAX_SKILLS)
        }
        persist()
    }

    @Synchronized
    fun recall(name: String): SkillEntry? {
        val idx = skills.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        if (idx < 0) return null
        skills[idx] = skills[idx].copy(useCount = skills[idx].useCount + 1,
            lastUsed = System.currentTimeMillis())
        persist()
        return skills[idx]
    }

    fun search(query: String): List<SkillEntry> {
        val q = query.lowercase()
        return skills.filter {
            it.name.lowercase().contains(q) ||
            it.description.lowercase().contains(q) ||
            it.tags.any { t -> t.lowercase().contains(q) }
        }.sortedByDescending { it.useCount }
    }

    fun getAll(): List<SkillEntry> = skills.toList()

    fun delete(name: String): Boolean {
        val removed = skills.removeIf { it.name == name }
        if (removed) persist()
        return removed
    }

    @Synchronized
    private fun persist() {
        try {
            skillsFile.parentFile?.mkdirs()
            skillsFile.writeText(skills.joinToString("\n") { json.encodeToString(it) })
        } catch (e: Exception) {
            Timber.e(e, "SkillMemory: persist failed")
        }
    }

    fun summary(): String = "Skills: ${skills.size} stored. " +
        "Top: ${skills.sortedByDescending { it.useCount }.take(3).joinToString { it.name }}"
}
