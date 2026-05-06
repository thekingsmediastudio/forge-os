package com.forge.os.domain.projects

import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import com.forge.os.domain.agent.params
import com.forge.os.domain.agent.tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectToolProvider @Inject constructor(
    private val repository: ProjectsRepository,
    private val scopeManager: ProjectScopeManager,
    private val sandboxManager: SandboxManager,
    // Enhanced Integration: Connect with other systems
    private val memoryManager: com.forge.os.domain.memory.MemoryManager,
    private val reflectionManager: com.forge.os.domain.agent.ReflectionManager,
) : ToolProvider {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun getTools(): List<ToolDefinition> = listOf(
        tool("project_list",
            "List all projects with metadata. Returns project name, description, file count, tags.",
            params()),
        
        tool("project_read_metadata",
            "Read the project.json metadata for a specific project.",
            params("slug" to "string:Project slug"),
            required = listOf("slug")),
        
        tool("project_write_metadata",
            "Update project metadata (name, description, tags, etc.).",
            params("slug" to "string:Project slug",
                   "updates" to "object:Metadata fields to update (JSON)"),
            required = listOf("slug", "updates")),
        
        tool("project_read_file",
            "Read a file from a project directory.",
            params("slug" to "string:Project slug",
                   "path" to "string:File path relative to project root"),
            required = listOf("slug", "path")),
        
        tool("project_write_file",
            "Write or create a file in a project directory.",
            params("slug" to "string:Project slug",
                   "path" to "string:File path relative to project root",
                   "content" to "string:File content"),
            required = listOf("slug", "path", "content")),
        
        tool("project_list_files",
            "List all files in a project directory.",
            params("slug" to "string:Project slug",
                   "path" to "string:Directory path (default '.')"),
            required = listOf("slug")),
        
        tool("project_activate",
            "Set a project as the active scope. Agent tools will be scoped to this project.",
            params("slug" to "string:Project slug"),
            required = listOf("slug")),
        
        tool("project_get_active",
            "Get the currently active project scope.",
            params()),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? {
        return when (toolName) {
            "project_list"           -> projectList()
            "project_read_metadata"  -> projectReadMetadata(args)
            "project_write_metadata" -> projectWriteMetadata(args)
            "project_read_file"      -> projectReadFile(args)
            "project_write_file"     -> projectWriteFile(args)
            "project_list_files"     -> projectListFiles(args)
            "project_activate"       -> projectActivate(args)
            "project_get_active"     -> projectGetActive()
            else -> null
        }
    }

    private fun projectList(): String {
        val projects = repository.list()
        if (projects.isEmpty()) return "No projects found."
        return buildString {
            appendLine("📁 Projects (${projects.size}):")
            projects.forEach { p ->
                val fileCount = repository.fileCount(p.slug)
                val tags = if (p.tags.isNotEmpty()) " [${p.tags.joinToString(", ")}]" else ""
                appendLine("  • ${p.name} (${p.slug})$tags")
                if (p.description.isNotBlank()) appendLine("    ${p.description}")
                appendLine("    $fileCount files • Created: ${formatDate(p.createdAt)}")
            }
        }
    }

    private fun projectReadMetadata(args: Map<String, Any>): String {
        val slug = args["slug"]?.toString() ?: return "Error: slug required"
        val project = repository.get(slug) ?: return "❌ Project not found: $slug"
        return buildString {
            appendLine("📋 Project: ${project.name}")
            appendLine("  Slug: ${project.slug}")
            appendLine("  Description: ${project.description.ifBlank { "(none)" }}")
            appendLine("  Created: ${formatDate(project.createdAt)}")
            appendLine("  Python: ${project.pythonVersion}")
            if (project.mainScript != null) appendLine("  Main script: ${project.mainScript}")
            if (project.requirements.isNotEmpty()) appendLine("  Requirements: ${project.requirements.joinToString(", ")}")
            if (project.tags.isNotEmpty()) appendLine("  Tags: ${project.tags.joinToString(", ")}")
            if (project.scopedTools.isNotEmpty()) appendLine("  Scoped tools: ${project.scopedTools.joinToString(", ")}")
            if (project.scopedMemoryTags.isNotEmpty()) appendLine("  Memory tags: ${project.scopedMemoryTags.joinToString(", ")}")
        }
    }

    private suspend fun projectWriteMetadata(args: Map<String, Any>): String {
        val slug = args["slug"]?.toString() ?: return "Error: slug required"
        val updates = args["updates"] as? Map<*, *> ?: return "Error: updates must be a JSON object"
        
        val project = repository.get(slug) ?: return "❌ Project not found: $slug"
        
        val updated = project.copy(
            name = (updates["name"] as? String) ?: project.name,
            description = (updates["description"] as? String) ?: project.description,
            pythonVersion = (updates["pythonVersion"] as? String) ?: project.pythonVersion,
            mainScript = (updates["mainScript"] as? String) ?: project.mainScript,
            requirements = (updates["requirements"] as? List<*>)?.mapNotNull { it as? String } ?: project.requirements,
            tags = (updates["tags"] as? List<*>)?.mapNotNull { it as? String } ?: project.tags,
            readme = (updates["readme"] as? String) ?: project.readme,
        )
        
        repository.save(updated)
        Timber.i("Updated project metadata: ${project.slug}")
        return "✅ Updated project: ${project.name}"
    }

    private suspend fun projectReadFile(args: Map<String, Any>): String {
        val slug = args["slug"]?.toString() ?: return "Error: slug required"
        val path = args["path"]?.toString() ?: return "Error: path required"
        
        if (repository.get(slug) == null) return "❌ Project not found: $slug"
        
        val projectPath = "projects/$slug/$path"
        return sandboxManager.readFile(projectPath).fold(
            onSuccess = { it },
            onFailure = { "❌ Failed to read file: ${it.message}" }
        )
    }

    private suspend fun projectWriteFile(args: Map<String, Any>): String {
        val slug = args["slug"]?.toString() ?: return "Error: slug required"
        val path = args["path"]?.toString() ?: return "Error: path required"
        val content = args["content"]?.toString() ?: return "Error: content required"
        
        if (repository.get(slug) == null) return "❌ Project not found: $slug"
        
        val projectPath = "projects/$slug/$path"
        val result = sandboxManager.writeFile(projectPath, content).fold(
            onSuccess = { 
                // Enhanced Integration: Learn from project file operations
                try {
                    // Store significant file changes in memory
                    if (content.length > 500) {
                        memoryManager.store(
                            key = "project_file_${slug}_${path.replace("/", "_")}_${System.currentTimeMillis()}",
                            content = "Updated file $path in project $slug (${content.length} chars)",
                            tags = listOf("project_file", "file_update", slug, path.substringAfterLast('.', ""))
                        )
                    }
                    
                    // Record file operation patterns
                    scope.launch {
                        try {
                            val fileType = path.substringAfterLast('.', "unknown")
                            reflectionManager.recordPattern(
                                pattern = "Project file write: $fileType in $slug",
                                description = "Wrote $fileType file $path in project $slug (${content.length} chars)",
                                applicableTo = listOf("project_file_operations", fileType, slug),
                                tags = listOf("file_write", "project_operation", fileType, slug)
                            )
                            
                            // Learn coding patterns from file content
                            if (fileType in listOf("py", "js", "ts", "kt", "java")) {
                                val codePatterns = mutableListOf<String>()
                                if (content.contains("class ")) codePatterns.add("class_definition")
                                if (content.contains("function ") || content.contains("def ")) codePatterns.add("function_definition")
                                if (content.contains("import ") || content.contains("from ")) codePatterns.add("imports")
                                if (content.contains("test") || content.contains("Test")) codePatterns.add("testing")
                                
                                codePatterns.forEach { pattern ->
                                    reflectionManager.recordPattern(
                                        pattern = "Code pattern: $pattern in $fileType",
                                        description = "Found $pattern in $fileType file $path",
                                        applicableTo = listOf("coding_patterns", pattern, fileType),
                                        tags = listOf("code_analysis", pattern, fileType, "project_code")
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to record project file operation patterns")
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to record project file operations")
                }
                
                "✅ Written to $path" 
            },
            onFailure = { 
                // Enhanced Integration: Record file operation failures
                scope.launch {
                    try {
                        reflectionManager.recordFailureAndRecovery(
                            taskId = "project_write_${System.currentTimeMillis()}",
                            failureReason = "Failed to write file $path in project $slug: ${it.message}",
                            recoveryStrategy = "Check file permissions, validate path, or ensure project exists",
                            tags = listOf("file_write_failure", slug, path)
                        )
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to record file write failure")
                    }
                }
                
                "❌ Failed to write file: ${it.message}" 
            }
        )
        return result
    }

    private suspend fun projectListFiles(args: Map<String, Any>): String {
        val slug = args["slug"]?.toString() ?: return "Error: slug required"
        val path = args["path"]?.toString() ?: "."
        
        if (repository.get(slug) == null) return "❌ Project not found: $slug"
        
        val projectPath = if (path == ".") "projects/$slug" else "projects/$slug/$path"
        return sandboxManager.listFiles(projectPath).fold(
            onSuccess = { files ->
                if (files.isEmpty()) "(empty)"
                else files.joinToString("\n") { 
                    "${if (it.isDirectory) "📁" else "📄"} ${it.name}" 
                }
            },
            onFailure = { "❌ Failed to list files: ${it.message}" }
        )
    }

    private fun projectActivate(args: Map<String, Any>): String {
        val slug = args["slug"]?.toString() ?: return "Error: slug required"
        val project = repository.get(slug) ?: return "❌ Project not found: $slug"
        
        scopeManager.setActive(project)
        Timber.i("Activated project: ${project.slug}")
        
        // Enhanced Integration: Learn project activation patterns and store in memory
        scope.launch {
            try {
                // Store project activation in memory for context
                memoryManager.store(
                    key = "active_project_${System.currentTimeMillis()}",
                    content = "Activated project: ${project.name} (${project.slug}) - ${project.description}",
                    tags = listOf("project_activation", "active_project", project.slug, "context")
                )
                
                // Record project activation patterns
                reflectionManager.recordPattern(
                    pattern = "Project activation: ${project.slug}",
                    description = "Activated project ${project.name} with ${repository.fileCount(project.slug)} files",
                    applicableTo = listOf("project_management", "project_activation", project.slug),
                    tags = listOf("project_activation", "project_scope", project.slug, "workflow")
                )
                
                // Learn project type patterns
                val projectType = when {
                    project.tags.contains("python") || project.pythonVersion.isNotBlank() -> "python"
                    project.tags.contains("web") || project.tags.contains("html") -> "web"
                    project.tags.contains("api") || project.tags.contains("backend") -> "api"
                    project.tags.contains("mobile") || project.tags.contains("android") -> "mobile"
                    else -> "general"
                }
                
                reflectionManager.recordPattern(
                    pattern = "Project type activation: $projectType",
                    description = "Activated $projectType project: ${project.name}",
                    applicableTo = listOf("project_types", projectType, "activation"),
                    tags = listOf("project_type", projectType, "activation_pattern")
                )
                
            } catch (e: Exception) {
                Timber.w(e, "Failed to record project activation patterns")
            }
        }
        
        return "✅ Activated project: ${project.name}"
    }

    private fun projectGetActive(): String {
        val active = scopeManager.active.value
        return if (active != null) {
            "📌 Active project: ${active.name} (${active.slug})"
        } else {
            "No active project scope"
        }
    }

    private fun formatDate(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return fmt.format(Date(ms))
    }
}
