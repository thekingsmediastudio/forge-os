package com.forge.os.domain/workspace/GhostWorkspaceProvider.kt

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GhostWorkspaceProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    // Enhanced Integration: Connect with other systems
    private val reflectionManager: com.forge.os.domain.agent.ReflectionManager,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val ghostsBaseDir = File(context.filesDir, "workspace/temp/ghosts")

    init {
        if (!ghostsBaseDir.exists()) ghostsBaseDir.mkdirs()
    }

    /**
     * Creates a new ephemeral workspace for a ghost agent.
     * @return The absolute path to the sandbox root.
     */
    fun createSandbox(): File {
        val sandboxId = UUID.randomUUID().toString().take(8)
        val sandboxDir = File(ghostsBaseDir, sandboxId)
        sandboxDir.mkdirs()
        
        // Seed with basic structure if needed
        File(sandboxDir, "projects").mkdirs()
        File(sandboxDir, "notes").mkdirs()
        File(sandboxDir, "temp").mkdirs()
        
        Timber.d("Created ghost sandbox: ${sandboxDir.absolutePath}")
        
        // Enhanced Integration: Learn ghost workspace creation patterns
        scope.launch {
            try {
                reflectionManager.recordPattern(
                    pattern = "Ghost workspace creation: $sandboxId",
                    description = "Created ephemeral workspace for ghost agent with ID $sandboxId",
                    applicableTo = listOf("ghost_workspace", "delegation", "sandbox_management"),
                    tags = listOf("ghost_workspace", "sandbox_creation", "delegation", sandboxId)
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to record ghost workspace creation patterns")
            }
        }
        
        return sandboxDir
    }

    /**
     * Deletes a sandbox and all its contents.
     */
    fun destroySandbox(sandboxDir: File) {
        if (sandboxDir.startsWith(ghostsBaseDir)) {
            val sandboxId = sandboxDir.name
            sandboxDir.deleteRecursively()
            Timber.d("Destroyed ghost sandbox: ${sandboxDir.absolutePath}")
            
            // Enhanced Integration: Learn ghost workspace cleanup patterns
            scope.launch {
                try {
                    reflectionManager.recordPattern(
                        pattern = "Ghost workspace cleanup: $sandboxId",
                        description = "Cleaned up ephemeral workspace for ghost agent with ID $sandboxId",
                        applicableTo = listOf("ghost_workspace", "cleanup", "sandbox_management"),
                        tags = listOf("ghost_workspace", "sandbox_cleanup", "delegation", sandboxId)
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to record ghost workspace cleanup patterns")
                }
            }
        } else {
            Timber.w("Refusing to destroy non-sandbox dir: ${sandboxDir.absolutePath}")
        }
    }

    /**
     * Wipes all ghost sandboxes.
     */
    fun cleanupAll() {
        ghostsBaseDir.deleteRecursively()
        ghostsBaseDir.mkdirs()
    }
}
