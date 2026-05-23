package com.forge.os.domain.memory

import android.content.Context
import com.forge.os.domain.projects.ProjectsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkspaceIndexer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspaceIndex: WorkspaceIndex,
    private val projectsRepository: ProjectsRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var indexJob: Job? = null

    private val workspaceRoot: File get() = context.filesDir.resolve("workspace/projects")

    fun startBackgroundIndexing() {
        if (indexJob?.isActive == true) return
        indexJob = scope.launch {
            while (isActive) {
                try {
                    performIndexingScan()
                } catch (e: Exception) {
                    Timber.e(e, "WorkspaceIndexer: Scan failed")
                }
                // Index every 15 minutes or when idle
                delay(15 * 60 * 1000L)
            }
        }
    }

    private suspend fun performIndexingScan() {
        Timber.i("WorkspaceIndexer: Starting full scan...")
        val projects = projectsRepository.list()
        
        for (project in projects) {
            val projectDir = File(workspaceRoot, project.slug)
            if (!projectDir.exists()) continue
            
            Timber.d("WorkspaceIndexer: Indexing project ${project.slug}")
            indexDirectory(projectDir, project.slug)
        }
        Timber.i("WorkspaceIndexer: Full scan complete.")
    }

    private suspend fun indexDirectory(dir: File, slug: String) {
        dir.walkTopDown().forEach { file ->
            if (file.isFile && isIndexable(file)) {
                indexFile(file, slug)
            }
        }
    }

    private fun isIndexable(file: File): Boolean {
        val ext = file.extension.lowercase()
        val indexableExtensions = setOf(
            "kt", "java", "py", "js", "ts", "html", "css", "json", "md", "txt", "sh", "sql"
        )
        // Skip large generated files or node_modules
        val path = file.absolutePath
        if (path.contains("node_modules") || path.contains("build/") || path.contains(".git")) return false
        if (file.length() > 500 * 1024) return false // Skip files > 500KB
        
        return ext in indexableExtensions
    }

    private suspend fun indexFile(file: File, slug: String) {
        val relPath = file.absolutePath.substringAfter("/workspace/projects/")
        val content = try { file.readText() } catch (e: Exception) { return }
        
        // Simple chunking strategy: 2000 chars with 200 char overlap
        val chunkSize = 2000
        val overlap = 200
        
        var start = 0
        var chunkIdx = 0
        while (start < content.length) {
            val end = (start + chunkSize).coerceAtMost(content.length)
            val chunk = content.substring(start, end)
            
            workspaceIndex.updateEntry(WorkspaceEntry(
                path = relPath,
                content = chunk,
                lastModified = file.lastModified(),
                projectSlug = slug,
                chunkIndex = chunkIdx
            ))
            
            start += (chunkSize - overlap)
            chunkIdx++
            
            // Yield to avoid blocking during massive indexing tasks
            yield()
        }
    }

    fun stop() {
        indexJob?.cancel()
    }
}
