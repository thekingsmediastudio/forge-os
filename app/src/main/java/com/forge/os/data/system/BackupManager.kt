package com.forge.os.data.system

import android.content.Context
import android.net.Uri
import com.forge.os.domain.workspace.WorkspaceLayout
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Creates a full backup ZIP of the workspace, databases, and preferences.
     * @return The File pointing to the generated backup.
     */
    suspend fun createBackup(): File = withContext(Dispatchers.IO) {
        val backupDir = File(context.cacheDir, "backups")
        if (!backupDir.exists()) backupDir.mkdirs()

        val timestamp = System.currentTimeMillis()
        val backupFile = File(backupDir, "forge_backup_$timestamp.zip")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(backupFile))).use { zos ->
            // 1. Workspace
            val workspace = File(context.filesDir, "workspace")
            if (workspace.exists()) {
                zipFolder(workspace, "files/workspace/", zos)
            }

            // 2. Databases
            val dbDir = File(context.dataDir, "databases")
            if (dbDir.exists()) {
                zipFolder(dbDir, "databases/", zos)
            }

            // 3. Shared Prefs
            val prefsDir = File(context.dataDir, "shared_prefs")
            if (prefsDir.exists()) {
                zipFolder(prefsDir, "shared_prefs/", zos)
            }
        }

        Timber.d("Backup created at: ${backupFile.absolutePath}")
        backupFile
    }

    private fun zipFolder(folder: File, parentPath: String, zos: ZipOutputStream) {
        folder.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                zipFolder(file, "$parentPath${file.name}/", zos)
            } else {
                val entry = ZipEntry("$parentPath${file.name}")
                zos.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    /**
     * Helper to get a shareable URI for the backup file.
     */
    fun getBackupUri(file: File): Uri {
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
    }
}
