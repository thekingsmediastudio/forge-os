package com.forge.os.domain.snapshots

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.CoreConfig
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase F1 — Workspace snapshots.
 * Creates point-in-time `.zip` snapshots of `workspace/` (excluding the
 * `.snapshots/` and `system/cache/` directories) so the agent or the user can
 * roll back after a destructive run.
 *
 * Snapshots live at `workspace/.snapshots/<id>.zip` together with a small
 * `.meta.json` sidecar describing the snapshot.
 */
@Serializable
data class SnapshotInfo(
    val id: String,
    val label: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val fileCount: Int,
    val isDifferential: Boolean = false,
)

@Singleton
class SnapshotManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspaceLock: com.forge.os.domain.workspace.WorkspaceLock,
    // Enhanced Integration: Connect with other systems
    private val reflectionManager: com.forge.os.domain.agent.ReflectionManager,
    private val backupManager: com.forge.os.domain.backup.BackupManager,
) {
    private val workspace = File(context.filesDir, "workspace").apply { mkdirs() }
    private val snapDir = File(workspace, ".snapshots").apply { mkdirs() }
    private val vcsGitDir = File(snapDir, "vcs_storage").apply { mkdirs() }
    private val excludePrefixes = listOf(".snapshots", "system/cache")

    private val _snapshots = MutableStateFlow(load())
    val snapshots: StateFlow<List<SnapshotInfo>> = _snapshots

    fun list(): List<SnapshotInfo> = _snapshots.value

    fun create(label: String? = null): Result<SnapshotInfo> = runCatching {
        kotlinx.coroutines.runBlocking {
            workspaceLock.mutex.lock()
            try {
                val ts = System.currentTimeMillis()
                Timber.d("Creating snapshot: $label")
                val git = getGit()
                
                // 1. Stage all files
                Timber.d("Staging files...")
                git.add().addFilepattern(".").call()
                
                // 2. We need to handle deletions too (git add -u)
                Timber.d("Staging deletions...")
                git.add().addFilepattern(".").setUpdate(true).call()

                // 3. Commit
                val name = "Forge Snapshots"
                val email = "snapshots@forge.os"
                val commitMessage = label?.takeIf { it.isNotBlank() } ?: defaultLabel(ts)
                Timber.d("Committing snapshot: $commitMessage")
                val rev = git.commit()
                    .setAuthor(PersonIdent(name, email))
                    .setCommitter(PersonIdent(name, email))
                    .setMessage(commitMessage)
                    .call()

                val id = rev.name // full sha
                val info = SnapshotInfo(
                    id = id,
                    label = commitMessage,
                    createdAt = ts,
                    sizeBytes = 0, // Git handles storage, we'll estimate or leave at 0
                    fileCount = 0, // Git knows, but we'd have to walk the tree
                    isDifferential = true
                )
                File(snapDir, "$id.meta.json").writeText(metaJson(info))
                _snapshots.value = load()
                Timber.i("Snapshot created: $id")
                
                // Enhanced Integration: Learn snapshot creation patterns
                try {
                    reflectionManager.recordPattern(
                        pattern = "Snapshot creation: $commitMessage",
                        description = "Created workspace snapshot with label: $commitMessage",
                        applicableTo = listOf("snapshot_management", "workspace_backup", "version_control"),
                        tags = listOf("snapshot_creation", "workspace_management", "backup", "git")
                    )
                    
                    // Cross-reference with backup system
                    val backups = backupManager.listBackups()
                    if (backups.isNotEmpty()) {
                        reflectionManager.recordPattern(
                            pattern = "Snapshot with existing backups",
                            description = "Created snapshot while ${backups.size} backups exist - good backup hygiene",
                            applicableTo = listOf("backup_strategy", "data_protection"),
                            tags = listOf("backup_hygiene", "data_protection", "snapshot_backup_combo")
                        )
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to record snapshot creation patterns")
                }
                
                info
            } finally {
                workspaceLock.mutex.unlock()
            }
        }
    }.onFailure { Timber.e(it, "snapshot create (git) failed") }

    private fun getGit(): Git {
        val ignoreFile = File(workspace, ".gitignore")
        if (!ignoreFile.exists()) {
            ignoreFile.writeText(".snapshots/\nsystem/cache/\n")
        } else {
            val content = ignoreFile.readText()
            if (!content.contains(".snapshots/")) {
                ignoreFile.appendText("\n.snapshots/\nsystem/cache/\n")
            }
        }

        return if (!File(vcsGitDir, "config").exists()) {
            Git.init()
                .setDirectory(workspace)
                .setGitDir(vcsGitDir)
                .call()
        } else {
            val repo = FileRepositoryBuilder()
                .setGitDir(vcsGitDir)
                .setWorkTree(workspace)
                .build()
            Git(repo)
        }
    }

    fun delete(id: String): Boolean {
        val zip = File(snapDir, "$id.zip")
        val ok = if (zip.exists()) zip.delete() else true // If git, we just delete meta for now
        File(snapDir, "$id.meta.json").delete()
        _snapshots.value = load()
        return ok
    }

    /**
     * Restore a snapshot. Existing workspace files are wiped (except
     * `.snapshots/` itself), then the snapshot is unzipped over the workspace.
     * Returns the count of files restored.
     */
    fun restore(id: String): Result<Int> = runCatching {
        kotlinx.coroutines.runBlocking {
            workspaceLock.mutex.lock()
            try {
                val meta = File(snapDir, "$id.meta.json")
                require(meta.exists()) { "snapshot $id not found" }
                val info = parseMeta(meta.readText()) ?: throw Exception("invalid meta")

                if (info.isDifferential) {
                    val git = getGit()
                    // Forced checkout to restore the state
                    git.checkout()
                        .setName(id)
                        .setStartPoint(id)
                        .setAllPaths(true)
                        .setForce(true)
                        .call()
                    // Cleanup any untracked files that weren't in the snapshot
                    git.clean().setCleanDirectories(true).setIgnore(false).call()
                    
                    // Enhanced Integration: Learn snapshot restoration patterns
                    try {
                        reflectionManager.recordPattern(
                            pattern = "Snapshot restoration: ${info.label}",
                            description = "Restored workspace from snapshot: ${info.label}",
                            applicableTo = listOf("snapshot_restoration", "workspace_recovery", "version_control"),
                            tags = listOf("snapshot_restore", "workspace_recovery", "git_restore", "data_recovery")
                        )
                        
                        // Record recovery strategy
                        reflectionManager.recordFailureAndRecovery(
                            taskId = "snapshot_restore_$id",
                            failureReason = "Workspace restoration requested",
                            recoveryStrategy = "Successfully restored workspace from snapshot: ${info.label}",
                            tags = listOf("workspace_recovery", "snapshot_restore", "successful_recovery")
                        )
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to record snapshot restoration patterns")
                    }
                    
                    0 // We don't have a count easily
                } else {
                    val zipFile = File(snapDir, "$id.zip")
                    require(zipFile.exists()) { "ZIP snapshot $id file missing" }

                    // Wipe everything except .snapshots/
                    workspace.listFiles()?.forEach { f ->
                        if (f.name != ".snapshots" && f.name != ".gitignore") f.deleteRecursively()
                    }
                    var count = 0
                    ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val outFile = File(workspace, entry.name)
                            if (!outFile.canonicalPath.startsWith(workspace.canonicalPath)) {
                                Timber.w("snapshot restore: skipping path-traversal entry ${entry.name}")
                            } else if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                BufferedOutputStream(FileOutputStream(outFile)).use { zis.copyTo(it) }
                                count++
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                    count
                }
            } finally {
                workspaceLock.mutex.unlock()
            }
        }
    }.onFailure { Timber.e(it, "snapshot restore") }

    private fun load(): List<SnapshotInfo> = snapDir.listFiles { f -> f.name.endsWith(".meta.json") }
        ?.mapNotNull { meta ->
            parseMeta(meta.readText())
        }
        ?.sortedByDescending { it.createdAt }
        ?: emptyList()

    private fun defaultLabel(ts: Long): String =
        "Snapshot " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ts))

    private fun metaJson(info: SnapshotInfo): String =
        "{\"id\":${q(info.id)},\"label\":${q(info.label)},\"createdAt\":${info.createdAt}," +
        "\"sizeBytes\":${info.sizeBytes},\"fileCount\":${info.fileCount},\"isDifferential\":${info.isDifferential}}"

    private fun parseMeta(s: String): SnapshotInfo? = runCatching {
        val map = simpleJson(s)
        SnapshotInfo(
            id = map["id"] ?: return null,
            label = map["label"] ?: "",
            createdAt = map["createdAt"]?.toLongOrNull() ?: 0L,
            sizeBytes = map["sizeBytes"]?.toLongOrNull() ?: 0L,
            fileCount = map["fileCount"]?.toIntOrNull() ?: 0,
            isDifferential = map["isDifferential"]?.toBoolean() ?: false,
        )
    }.getOrNull()

    private fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Tiny dependency-free JSON object parser for our flat meta files. */
    private fun simpleJson(s: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val trimmed = s.trim().removePrefix("{").removeSuffix("}")
        var i = 0
        while (i < trimmed.length) {
            // Skip to key opening quote
            while (i < trimmed.length && trimmed[i] != '"') i++
            if (i >= trimmed.length) break
            i++ // skip opening quote
            val keyBuilder = StringBuilder()
            while (i < trimmed.length && trimmed[i] != '"') {
                if (trimmed[i] == '\\' && i + 1 < trimmed.length) {
                    i++ // skip backslash
                    keyBuilder.append(trimmed[i])
                } else {
                    keyBuilder.append(trimmed[i])
                }
                i++
            }
            val key = keyBuilder.toString()
            if (i < trimmed.length) i++ // skip closing quote
            // Skip to colon then value
            while (i < trimmed.length && trimmed[i] != ':') i++
            if (i < trimmed.length) i++ // skip colon
            while (i < trimmed.length && trimmed[i].isWhitespace()) i++
            // Parse value: quoted string or bare token
            val value = if (i < trimmed.length && trimmed[i] == '"') {
                i++ // skip opening quote
                val valBuilder = StringBuilder()
                while (i < trimmed.length && trimmed[i] != '"') {
                    if (trimmed[i] == '\\' && i + 1 < trimmed.length) {
                        i++ // skip backslash; interpret escape
                        when (trimmed[i]) {
                            'n'  -> valBuilder.append('\n')
                            'r'  -> valBuilder.append('\r')
                            't'  -> valBuilder.append('\t')
                            '"'  -> valBuilder.append('"')
                            '\\' -> valBuilder.append('\\')
                            else -> { valBuilder.append('\\'); valBuilder.append(trimmed[i]) }
                        }
                    } else {
                        valBuilder.append(trimmed[i])
                    }
                    i++
                }
                if (i < trimmed.length) i++ // skip closing quote
                valBuilder.toString()
            } else {
                val vs = i
                while (i < trimmed.length && trimmed[i] != ',' && trimmed[i] != '}') i++
                trimmed.substring(vs, i).trim()
            }
            if (key.isNotBlank()) out[key] = value
            // Skip to next comma
            while (i < trimmed.length && trimmed[i] != ',') i++
            if (i < trimmed.length) i++ // skip comma
        }
        return out
    }

    /**
     * Phase 3 — Full system backup export.
     * Zips the entire workspace (including snapshots and memory) into a single 
     * portable file. Returns the File object pointing to the ZIP.
     */
    fun exportFullBackup(destFile: File): Result<File> = runCatching {
        kotlinx.coroutines.runBlocking {
            workspaceLock.mutex.lock()
            try {
                ZipOutputStream(BufferedOutputStream(FileOutputStream(destFile))).use { zos ->
                    workspace.walkTopDown().forEach { file ->
                        if (file.canonicalPath != destFile.canonicalPath) {
                            val relPath = file.relativeTo(workspace).path
                            if (relPath.isNotEmpty() && !relPath.contains("system/cache")) {
                                val entry = ZipEntry(if (file.isDirectory) "$relPath/" else relPath)
                                zos.putNextEntry(entry)
                                if (file.isFile) {
                                    file.inputStream().use { it.copyTo(zos) }
                                }
                                zos.closeEntry()
                            }
                        }
                    }
                }
                destFile
            } finally {
                workspaceLock.mutex.unlock()
            }
        }
    }

    /**
     * Phase 3 — Full system backup import.
     * Wipes the current workspace and restores from a ZIP backup.
     */
    fun importFullBackup(sourceFile: File): Result<Int> = runCatching {
        kotlinx.coroutines.runBlocking {
            workspaceLock.mutex.lock()
            try {
                // Wipe everything
                workspace.listFiles()?.forEach { it.deleteRecursively() }

                val wsCanonical = workspace.canonicalPath
                var count = 0
                ZipInputStream(BufferedInputStream(FileInputStream(sourceFile))).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(workspace, entry.name)
                        // Zip-slip protection: reject any entry that escapes the workspace.
                        if (!outFile.canonicalPath.startsWith(wsCanonical + File.separator) &&
                            outFile.canonicalPath != wsCanonical) {
                            Timber.w("importFullBackup: skipping path-traversal entry '${entry.name}'")
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { zis.copyTo(it) }
                            count++
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                count
            } finally {
                workspaceLock.mutex.unlock()
            }
        }
    }
}
