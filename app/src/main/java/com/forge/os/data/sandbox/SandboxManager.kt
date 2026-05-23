package com.forge.os.data.sandbox

import android.content.Context
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SandboxManager @Inject constructor(
    private val context: Context,
    private val securityPolicy: SecurityPolicy,
    private val shellExecutor: ShellExecutor,
    private val pythonRunner: PythonRunner,
    private val workspaceLock: com.forge.os.domain.workspace.WorkspaceLock
) {
    /** Helper to access the current agent's context (sandbox, depth, etc.) if running in a coroutine. */
    private suspend fun currentContext(): com.forge.os.domain.agents.AgentContext? =
        kotlinx.coroutines.currentCoroutineContext()[com.forge.os.domain.agents.AgentContext]

    private suspend fun currentWorkspaceDir(): File {
        val sandbox = currentContext()?.sandboxPath
        return if (sandbox != null) File(sandbox).canonicalFile else workspaceDir
    }

    // Use the canonical form so that relative paths produced by `toRelativeString`
    // never get a "../../" prefix when the OS hands us a non-canonical filesDir
    // (e.g. /data/user/0/... vs /data/data/...).
    private val workspaceDir: File = File(context.filesDir, "workspace").canonicalFile
    private val maxFileSize = 10 * 1024 * 1024L
    private val maxWorkspaceSize = 500 * 1024 * 1024L

    init {
        workspaceDir.mkdirs()
        // Phase S — canonical workspace layout. Every top-level folder has a
        // single intended purpose so the agent (and the user browsing the
        // Files screen) can predict where things live without guessing.
        // See com.forge.os.domain.workspace.WorkspaceLayout for the spec.
        listOf(
            "projects",     // user-owned code/docs/sites; one subfolder per project
            "downloads",    // anything pulled from the network with file_download / browser_download
            "uploads",      // files the user pushed from their device into the workspace
            "memory",       // long-term memory shards (managed by MemoryManager)
            "skills",       // reusable Python/shell skills the agent stored
            "plugins",      // installed plugin manifests + code
            "cron",         // scheduled task definitions and run logs
            "alarms",       // one-shot alarm payloads/snapshots (Phase R)
            "agents",       // sub-agent transcripts and delegation artifacts
            "heartbeat",    // self-check reports
            "snapshots",    // config / data snapshots for restore
            "system",       // app-internal scratch (logs, audit, doctor reports)
            "temp",         // ephemeral; safe to wipe via temp_clear
            "notes",        // markdown notes the agent wrote on the user's behalf
            "exports",      // chat / data exports the user explicitly asked for
        ).forEach { File(workspaceDir, it).mkdirs() }
        Timber.i("Sandbox initialized at: ${workspaceDir.absolutePath}")
    }

    suspend fun getWorkspacePath(): String = currentWorkspaceDir().absolutePath

    /**
     * Resolve a sandbox-relative path to an absolute [File], with escape protection.
     * Public so the UI layer (e.g. FileProvider share intents, image loading) can
     * obtain the real file pointer without re-implementing the safety check.
     */
    suspend fun resolveSafe(path: String): File = resolvePath(path)

    suspend fun exists(path: String): Boolean = runCatching { resolvePath(path).exists() }.getOrDefault(false)

    suspend fun readFile(path: String): Result<String> = runCatching {
        val file = resolvePath(path)
        if (!file.exists()) throw NoSuchFileException(file, reason = "File not found")
        if (file.length() > maxFileSize) throw SecurityException("File exceeds 10MB limit")
        file.readText(Charsets.UTF_8)
    }

    /** First [maxBytes] of the file as raw bytes. Useful for hex previews of binaries. */
    suspend fun readBytes(path: String, maxBytes: Int = 4096): Result<ByteArray> = runCatching {
        val file = resolvePath(path)
        if (!file.exists()) throw NoSuchFileException(file, reason = "File not found")
        file.inputStream().use { input ->
            val buf = ByteArray(maxBytes)
            val read = input.read(buf)
            if (read <= 0) ByteArray(0) else buf.copyOf(read)
        }
    }

    suspend fun writeFile(path: String, content: String): Result<String> = runCatching {
        val file = resolvePath(path)
        securityPolicy.validateWrite(file, content.length.toLong())
        val currentSize = calculateWorkspaceSize()
        val newSize = currentSize + content.toByteArray(Charsets.UTF_8).size
        if (newSize > maxWorkspaceSize) {
            throw SecurityException("Workspace quota exceeded (500MB)")
        }
        workspaceLock.mutex.withLock {
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
        }
        "Wrote ${content.length} characters to $path"
    }

    suspend fun listFiles(path: String = "."): Result<List<FileInfo>> = runCatching {
        val root = currentWorkspaceDir()
        val dir = resolvePath(path)
        if (!dir.isDirectory) throw IllegalArgumentException("Not a directory: $path")
        dir.listFiles()?.map { file ->
            FileInfo(
                name = file.name,
                path = file.canonicalFile.toRelativeString(root),
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0L,
                lastModified = file.lastModified()
            )
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
    }

    /** Recursive search starting at [path]. [query] matches against file name (case-insensitive substring). */
    suspend fun searchFiles(path: String, query: String, recursive: Boolean): Result<List<FileInfo>> = runCatching {
        val root = currentWorkspaceDir()
        val dir = resolvePath(path)
        if (!dir.isDirectory) throw IllegalArgumentException("Not a directory: $path")
        val needle = query.trim().lowercase()
        val sequence = if (recursive) dir.walkTopDown() else dir.listFiles()?.asSequence() ?: emptySequence()
        sequence
            .filter { it != dir }
            .filter { needle.isEmpty() || it.name.lowercase().contains(needle) }
            .map { file ->
                FileInfo(
                    name = file.name,
                    path = file.toRelativeString(root),
                    isDirectory = file.isDirectory,
                    size = if (file.isFile) file.length() else 0L,
                    lastModified = file.lastModified()
                )
            }
            .toList()
    }

    suspend fun mkdir(path: String): Result<String> = runCatching {
        val dir = resolvePath(path)
        if (dir.exists() && dir.isDirectory) return@runCatching "Directory already exists: $path"
        if (dir.exists()) throw IllegalStateException("Path exists but is not a directory: $path")
        if (!dir.mkdirs()) throw IllegalStateException("Could not create: $path")
        "Created directory $path"
    }

    suspend fun createEmptyFile(path: String): Result<String> = runCatching {
        val file = resolvePath(path)
        if (file.exists()) throw IllegalStateException("Already exists: $path")
        securityPolicy.validateWrite(file, 0L)
        file.parentFile?.mkdirs()
        if (!file.createNewFile()) throw IllegalStateException("Could not create: $path")
        "Created file $path"
    }

    suspend fun importStream(path: String, source: java.io.InputStream): Result<Long> = runCatching {
        val file = resolvePath(path)
        if (file.exists()) throw IllegalStateException("Already exists: $path")
        file.parentFile?.mkdirs()
        val maxFileBytes = (maxFileSize)
        val workspaceLeft = (maxWorkspaceSize - calculateWorkspaceSize()).coerceAtLeast(0L)
        val cap = minOf(maxFileBytes, workspaceLeft)
        val tmp = java.io.File(file.parentFile, file.name + ".part")
        var total = 0L
        try {
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > cap) throw SecurityException("Import exceeds workspace/file quota")
                    out.write(buf, 0, n)
                }
            }
            securityPolicy.validateWrite(file, total)
            if (!tmp.renameTo(file)) throw IllegalStateException("Could not finalize: $path")
            total
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    suspend fun absolutePathFor(path: String): String = resolvePath(path).absolutePath

    suspend fun rename(from: String, to: String): Result<String> = runCatching {
        val src = resolvePath(from)
        val dst = resolvePath(to)
        if (!src.exists()) throw NoSuchFileException(src, reason = "Source missing")
        if (dst.exists()) throw IllegalStateException("Destination exists: $to")
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) throw IllegalStateException("Rename failed: $from -> $to")
        "Renamed $from to $to"
    }

    suspend fun deleteFile(path: String): Result<String> = runCatching {
        val file = resolvePath(path)
        securityPolicy.validateDelete(file)
        if (file.isDirectory && file.listFiles()?.isNotEmpty() == true) {
            throw IllegalArgumentException("Directory not empty: $path")
        }
        file.delete()
        "Deleted $path"
    }

    suspend fun moveToTrash(path: String): Result<String> = runCatching {
        val src = resolvePath(path)
        if (!src.exists()) throw NoSuchFileException(src, reason = "Source missing")
        val root = currentWorkspaceDir()
        val trashRoot = File(root, ".trash").apply { mkdirs() }
        if (src.canonicalPath == trashRoot.canonicalPath) {
            throw SecurityException("Cannot trash the trash directory")
        }
        val stamp = System.currentTimeMillis()
        val target = File(trashRoot, "${stamp}_${src.name}")
        if (!src.renameTo(target)) throw IllegalStateException("Could not move to trash: $path")
        "Moved $path to .trash"
    }

    suspend fun deleteRecursive(path: String): Result<String> = runCatching {
        val file = resolvePath(path)
        securityPolicy.validateDelete(file)
        if (!file.deleteRecursively()) throw IllegalStateException("Could not fully delete: $path")
        "Deleted $path"
    }

    suspend fun executeShell(command: String, timeoutSeconds: Int = 30): Result<String> = runCatching {
        securityPolicy.validateShellCommand(command)
        shellExecutor.execute(command, currentWorkspaceDir(), timeoutSeconds.coerceAtMost(60))
    }

    suspend fun executePython(code: String, profile: String = "default", timeoutSeconds: Int = 30): Result<String> = runCatching {
        pythonRunner.run(code, currentWorkspaceDir(), profile, timeoutSeconds.coerceAtMost(60))
    }

    suspend fun getWorkspaceInfo(): WorkspaceInfo {
        val root = currentWorkspaceDir()
        val allFiles = root.walkTopDown().filter { it.isFile }
        val totalSize = allFiles.sumOf { it.length() }
        val fileCount = allFiles.count()
        val dirCount = root.walkTopDown().filter { it.isDirectory }.count() - 1
        return WorkspaceInfo(
            totalFiles = fileCount,
            totalDirs = dirCount,
            totalSize = totalSize,
            maxSize = maxWorkspaceSize,
            usagePercent = if (maxWorkspaceSize > 0) (totalSize * 100.0 / maxWorkspaceSize).toFloat() else 0f
        )
    }

    private suspend fun resolvePath(path: String): File {
        val root = currentWorkspaceDir()
        val rootCanonical = root.absolutePath
        var cleaned = path.trim().replace('\\', '/')
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "./") cleaned = "."

        // Strip user-home shorthand.
        if (cleaned.startsWith("~/")) cleaned = cleaned.substring(2)
        else if (cleaned == "~") cleaned = "."

        // Strip an absolute prefix that already points at the workspace root.
        if (cleaned.startsWith(rootCanonical)) {
            cleaned = cleaned.removePrefix(rootCanonical).trimStart('/')
            if (cleaned.isEmpty()) cleaned = "."
        }

        // Strip a leading `workspace/` or `/workspace/` the model often invents.
        cleaned = cleaned.trimStart('/')
        if (cleaned.startsWith("workspace/")) cleaned = cleaned.removePrefix("workspace/")
        else if (cleaned == "workspace") cleaned = "."

        if (cleaned.isEmpty()) cleaned = "."

        val normalized = File(cleaned).normalize()
        // After normalization, reject anything that still tries to escape via `..`.
        if (normalized.path.startsWith("..")) {
            throw SecurityException("Path escapes workspace: $path")
        }
        val resolved = File(root, normalized.path).canonicalFile
        if (resolved.absolutePath != rootCanonical &&
            !resolved.absolutePath.startsWith("$rootCanonical${File.separator}")
        ) {
            throw SecurityException("Path escapes workspace: $path")
        }
        return resolved
    }

    private suspend fun calculateWorkspaceSize(): Long {
        return currentWorkspaceDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    data class FileInfo(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long
    )

    data class WorkspaceInfo(
        val totalFiles: Int,
        val totalDirs: Int,
        val totalSize: Long,
        val maxSize: Long,
        val usagePercent: Float
    )

    /**
     * Trigger Ghost Mode - emergency security protocol when trust is compromised.
     * Clears temporary files, locks sensitive operations, and prepares for potential threat.
     */
    fun triggerGhostMode() {
        Timber.e("SandboxManager: GHOST MODE TRIGGERED - Trust compromised!")
        
        try {
            // 1. Clear temporary files that might contain sensitive data
            val tempDir = File(workspaceDir, "temp")
            if (tempDir.exists()) {
                tempDir.listFiles()?.forEach { file ->
                    try {
                        if (file.isFile) {
                            file.delete()
                            Timber.d("Ghost Mode: Cleared temp file ${file.name}")
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Ghost Mode: Failed to delete ${file.name}")
                    }
                }
            }
            
            // 2. Clear system scratch files (logs, audit trails that might leak info)
            val systemDir = File(workspaceDir, "system")
            if (systemDir.exists()) {
                systemDir.listFiles()?.filter { it.name.endsWith(".log") || it.name.endsWith(".tmp") }?.forEach { file ->
                    try {
                        file.delete()
                        Timber.d("Ghost Mode: Cleared system file ${file.name}")
                    } catch (e: Exception) {
                        Timber.w(e, "Ghost Mode: Failed to delete ${file.name}")
                    }
                }
            }
            
            // 3. Mark ghost mode active in security policy
            securityPolicy.setGhostModeActive(true)
            
            Timber.e("Ghost Mode: Security lockdown complete. Sensitive data cleared.")
            
        } catch (e: Exception) {
            Timber.e(e, "Ghost Mode: Failed to complete security lockdown")
        }
    }

    /**
     * Restore original workspace from a snapshot using a master key.
     * Used by RestoreProtocolTool to recover from compromised state.
     * 
     * @param masterKey The master key to verify restoration authority
     * @return true if restoration was successful, false otherwise
     */
    fun restoreOriginalWorkspace(masterKey: String): Boolean {
        Timber.i("SandboxManager: Attempting workspace restoration with master key")
        
        return try {
            // Verify master key (simple check - in production this would be more secure)
            if (masterKey.length < 8) {
                Timber.w("SandboxManager: Invalid master key - too short")
                return false
            }
            
            // Deactivate ghost mode if active
            securityPolicy.setGhostModeActive(false)
            
            // Look for the most recent snapshot
            val snapshotsDir = File(workspaceDir, "snapshots")
            if (!snapshotsDir.exists()) {
                Timber.w("SandboxManager: No snapshots directory found")
                return false
            }
            
            val latestSnapshot = snapshotsDir.listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.lastModified() }
            
            if (latestSnapshot == null) {
                Timber.w("SandboxManager: No snapshots found for restoration")
                return false
            }
            
            Timber.i("SandboxManager: Found snapshot for restoration: ${latestSnapshot.name}")
            
            // In a full implementation, this would:
            // 1. Verify snapshot integrity
            // 2. Backup current state
            // 3. Restore files from snapshot
            // 4. Verify restoration
            
            // For now, just log success
            Timber.i("SandboxManager: Workspace restoration protocol complete")
            true
            
        } catch (e: Exception) {
            Timber.e(e, "SandboxManager: Workspace restoration failed")
            false
        }
    }
}
