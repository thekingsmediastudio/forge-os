package com.forge.os.presentation.screens.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.backup.BackupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
