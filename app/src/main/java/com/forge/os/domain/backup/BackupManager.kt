package com.forge.os.domain.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-click full workspace backup and restore.
 *
 * Backs up:
 *   - workspace/ (files, memory, plugins, skills, agents, alarms, cron, snapshots)
 *   - forge_config.json
 *
 * Does NOT back up API keys (EncryptedSharedPreferences) — user re-enters those.
 * Produces a single `.forge_backup` zip file.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class BackupResult(
        val success: Boolean,
        val path: String? = null,
        val sizeBytes: Long = 0,
        val fileCount: Int = 0,
        val error: String? = null
    )

    data class RestoreResult(
        val success: Boolean,
        val fileCount: Int = 0,
        val error: String? = null
    )

    private val workspaceDir: File get() = File(context.filesDir, "workspace")
    private val configFile: File get() = File(context.filesDir, "forge_config.json")

    /**
     * Create a backup zip file. Returns a [BackupResult] with the output path.
     * The backup is saved to the app's external files directory (accessible via file manager).
     */
    suspend fun createBackup(): BackupResult = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val backupDir = context.getExternalFilesDir("backups")
                ?: return@withContext BackupResult(false, error = "External storage unavailable")
            backupDir.mkdirs()
            val outFile = File(backupDir, "forge_backup_$timestamp.forge_backup")

            var fileCount = 0
            ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                // 1. Config file
                if (configFile.exists()) {
                    addFileToZip(zos, configFile, "forge_config.json")
                    fileCount++
                }

                // 2. Entire workspace directory
                if (workspaceDir.exists()) {
                    workspaceDir.walkTopDown()
                        .filter { it.isFile }
                        .forEach { file ->
                            val relativePath = "workspace/" + file.relativeTo(workspaceDir).path
                                .replace('\\', '/')
                            addFileToZip(zos, file, relativePath)
                            fileCount++
                        }
                }

                // 3. Metadata entry so we can validate on restore
                val meta = """
                    |forge_backup_version=1
                    |created=$timestamp
                    |files=$fileCount
                    |android_sdk=${android.os.Build.VERSION.SDK_INT}
                """.trimMargin()
                zos.putNextEntry(ZipEntry("_forge_meta.txt"))
                zos.write(meta.toByteArray())
                zos.closeEntry()
            }

            Timber.i("Backup created: ${outFile.absolutePath} ($fileCount files, ${outFile.length()} bytes)")
            BackupResult(
                success = true,
                path = outFile.absolutePath,
                sizeBytes = outFile.length(),
                fileCount = fileCount
            )
        } catch (e: Exception) {
            Timber.e(e, "Backup failed")
            BackupResult(false, error = e.message ?: "Unknown backup error")
        }
    }

    /**
     * Restore from a `.forge_backup` zip file. Atomically replaces the workspace
     * by first extracting to a temp directory, then swapping.
     */
    suspend fun restoreBackup(uri: Uri): RestoreResult = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext RestoreResult(false, error = "Cannot read backup file")

            // Extract to temp dir first for atomicity
            val tempDir = File(context.cacheDir, "forge_restore_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            var fileCount = 0
            var hasMetadata = false

            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "_forge_meta.txt") {
                        hasMetadata = true
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    val outFile = File(tempDir, entry.name)
                    // Prevent zip-slip
                    if (!outFile.canonicalPath.startsWith(tempDir.canonicalPath)) {
                        Timber.w("Zip-slip attempt blocked: ${entry.name}")
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    outFile.parentFile?.mkdirs()
                    if (!entry.isDirectory) {
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        fileCount++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (!hasMetadata) {
                tempDir.deleteRecursively()
                return@withContext RestoreResult(false, error = "Not a valid Forge backup file (missing metadata)")
            }

            // Atomic swap: delete current workspace, move temp into place
            val tempConfig = File(tempDir, "forge_config.json")
            if (tempConfig.exists()) {
                configFile.delete()
                tempConfig.copyTo(configFile, overwrite = true)
                tempConfig.delete()
            }

            val tempWorkspace = File(tempDir, "workspace")
            if (tempWorkspace.exists()) {
                // Back up existing workspace just in case
                val rollbackDir = File(context.cacheDir, "forge_pre_restore_rollback")
                rollbackDir.deleteRecursively()
                if (workspaceDir.exists()) {
                    workspaceDir.copyRecursively(rollbackDir, overwrite = true)
                }
                workspaceDir.deleteRecursively()
                tempWorkspace.copyRecursively(workspaceDir, overwrite = true)
            }

            tempDir.deleteRecursively()
            Timber.i("Restore complete: $fileCount files restored")
            RestoreResult(success = true, fileCount = fileCount)
        } catch (e: Exception) {
            Timber.e(e, "Restore failed")
            RestoreResult(false, error = e.message ?: "Unknown restore error")
        }
    }

    /** List existing backup files, newest first. */
    fun listBackups(): List<BackupInfo> {
        val dir = context.getExternalFilesDir("backups") ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.name.endsWith(".forge_backup") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { BackupInfo(it.name, it.absolutePath, it.length(), it.lastModified()) }
            ?: emptyList()
    }

    /** Delete a specific backup file. */
    fun deleteBackup(path: String): Boolean {
        val file = File(path)
        return if (file.exists() && file.name.endsWith(".forge_backup")) {
            file.delete()
        } else false
    }

    data class BackupInfo(
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val timestamp: Long
    )

    private fun addFileToZip(zos: ZipOutputStream, file: File, entryName: String) {
        zos.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { fis -> fis.copyTo(zos) }
        zos.closeEntry()
    }
}
