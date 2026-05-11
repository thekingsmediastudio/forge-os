package com.forge.os.domain.projects

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A scoped "workspace within the workspace". Backed by
 * `workspace/projects/<slug>/project.json`. Other modules (Chat, Tools, Memory)
 * read [ProjectScopeManager.activeProject] to know what slice of the world to
 * surface.
 */
@Serializable
data class Project(
    val slug: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val description: String = "",
    val scopedAgentId: String? = null,
    val scopedTools: List<String> = emptyList(),     // empty = all enabled tools visible
    val scopedMemoryTags: List<String> = emptyList(), // empty = full memory
    // Phase 1 additions for AI interaction
    val pythonVersion: String = "3.11",
    val mainScript: String? = null,  // e.g., "main.py"
    val requirements: List<String> = emptyList(),  // e.g., ["requests", "numpy"]
    val tags: List<String> = emptyList(),  // e.g., ["web", "api"]
    val readme: String? = null,  // e.g., "README.md"
)

@Singleton
class ProjectsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
    val root: File get() = context.filesDir.resolve("workspace/projects").apply { mkdirs() }

    fun list(): List<Project> = root.listFiles { f -> f.isDirectory }
        ?.mapNotNull { dir ->
            val mf = dir.resolve("project.json")
            if (mf.exists()) {
                runCatching { json.decodeFromString<Project>(mf.readText()) }.getOrNull()
            } else {
                // Fallback: surface bare directories the user dropped into
                // workspace/projects/ that don't yet have a project.json.
                // Synthesize a Project from the folder name, derive a stable
                // creation timestamp from the directory's lastModified so
                // ordering is sensible, and persist a project.json on first
                // sight so subsequent edits can be saved against it.
                val slug = dir.name
                val synthesized = Project(
                    slug = slug,
                    name = slug.replace('-', ' ').replace('_', ' ')
                        .split(' ')
                        .joinToString(" ") { word ->
                            if (word.isEmpty()) word
                            else word.replaceFirstChar { it.uppercase() }
                        },
                    createdAt = dir.lastModified().takeIf { it > 0L }
                        ?: System.currentTimeMillis(),
                    description = "(auto-detected — no project.json)",
                )
                runCatching { mf.writeText(json.encodeToString(synthesized)) }
                synthesized
            }
        }?.sortedBy { it.name } ?: emptyList()

    fun get(slug: String): Project? = list().find { it.slug == slug }

    fun save(project: Project): Project {
        val dir = root.resolve(project.slug).apply { mkdirs() }
        dir.resolve("project.json").writeText(json.encodeToString(project))
        Timber.i("ProjectsRepository: saved ${project.slug}")
        return project
    }

    fun delete(slug: String): Boolean {
        val dir = root.resolve(slug)
        if (!dir.exists()) return false
        return dir.deleteRecursively()
    }

    fun create(name: String, description: String = ""): Project {
        val slug = name.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
            .ifBlank { "project-${System.currentTimeMillis() / 1000}" }
        return save(Project(slug = slug, name = name.trim(), description = description))
    }

    fun fileCount(slug: String): Int {
        val dir = root.resolve(slug)
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile && it.name != "project.json" }.count()
    }
}

/**
 * In-memory holder of the currently-active project. Persisted as a slug into
 * `workspace/system/active_project.txt` so the choice survives app restarts.
 */
@Singleton
class ProjectScopeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ProjectsRepository,
) {
    private val pointerFile: File get() = context.filesDir.resolve("workspace/system/active_project.txt")
        .also { it.parentFile?.mkdirs() }

    private val _active = MutableStateFlow<Project?>(loadActive())
    val active: StateFlow<Project?> = _active.asStateFlow()

    fun setActive(project: Project?) {
        if (project == null) {
            if (pointerFile.exists()) pointerFile.delete()
        } else {
            pointerFile.writeText(project.slug)
        }
        _active.value = project
    }

    fun refresh() { _active.value = loadActive() }

    private fun loadActive(): Project? {
        if (!pointerFile.exists()) return null
        val slug = runCatching { pointerFile.readText().trim() }.getOrNull() ?: return null
        return repository.get(slug)
    }
}
