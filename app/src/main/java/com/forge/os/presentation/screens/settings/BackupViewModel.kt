package com.forge.os.presentation.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.backup.BackupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject

data class BackupUiState(
    val status: BackupStatus = BackupStatus.Idle,
    val backups: List<BackupManager.BackupInfo> = emptyList(),
    val lastMessage: String? = null
)

sealed class BackupStatus {
    object Idle : BackupStatus()
    object Running : BackupStatus()
    data class Done(val path: String, val sizeBytes: Long, val fileCount: Int) : BackupStatus()
    data class Restored(val fileCount: Int) : BackupStatus()
    data class Error(val message: String) : BackupStatus()
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: BackupManager
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state

    init {
        refreshBackupList()
    }

    fun createBackup() {
        _state.value = _state.value.copy(status = BackupStatus.Running)
        viewModelScope.launch {
            val result = backupManager.createBackup()
            _state.value = if (result.success) {
                _state.value.copy(
                    status = BackupStatus.Done(
                        path = result.path ?: "",
                        sizeBytes = result.sizeBytes,
                        fileCount = result.fileCount
                    ),
                    lastMessage = "Backup created: ${result.fileCount} files (${formatSize(result.sizeBytes)})"
                )
            } else {
                _state.value.copy(
                    status = BackupStatus.Error(result.error ?: "Backup failed"),
                    lastMessage = result.error
                )
            }
            refreshBackupList()
        }
    }

    fun restoreBackup(uri: Uri) {
        _state.value = _state.value.copy(status = BackupStatus.Running)
        viewModelScope.launch {
            val result = backupManager.restoreBackup(uri)
            _state.value = if (result.success) {
                _state.value.copy(
                    status = BackupStatus.Restored(result.fileCount),
                    lastMessage = "Restored ${result.fileCount} files. Restart recommended."
                )
            } else {
                _state.value.copy(
                    status = BackupStatus.Error(result.error ?: "Restore failed"),
                    lastMessage = result.error
                )
            }
        }
    }

    fun deleteBackup(path: String) {
        backupManager.deleteBackup(path)
        refreshBackupList()
    }

    /** Copy the just-created backup to a user-chosen URI (from ACTION_CREATE_DOCUMENT). */
    fun copyBackupTo(destUri: Uri) {
        val currentPath = (state.value.status as? BackupStatus.Done)?.path ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val src = File(currentPath)
                context.contentResolver.openOutputStream(destUri)?.use { out ->
                    FileInputStream(src).use { it.copyTo(out) }
                }
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(
                        lastMessage = "✅ Backup saved successfully"
                    )
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(lastMessage = "❌ Save failed: ${e.message}")
                }
            }
        }
    }

    /** Share an existing backup file via the system share sheet. */
    fun shareBackup(path: String) {
        runCatching {
            val file = File(path)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share backup").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(lastMessage = null)
    }

    fun resetStatus() {
        _state.value = _state.value.copy(status = BackupStatus.Idle)
    }

    private fun refreshBackupList() {
        _state.value = _state.value.copy(backups = backupManager.listBackups())
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    }
}
