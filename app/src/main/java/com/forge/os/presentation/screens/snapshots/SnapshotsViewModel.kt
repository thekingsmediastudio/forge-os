package com.forge.os.presentation.screens.snapshots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.snapshots.SnapshotInfo
import com.forge.os.domain.snapshots.SnapshotManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SnapshotsState(
    val items: List<SnapshotInfo> = emptyList(),
    val message: String? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class SnapshotsViewModel @Inject constructor(
    private val manager: SnapshotManager,
) : ViewModel() {
    private val _state = MutableStateFlow(SnapshotsState(items = manager.list()))
    val state: StateFlow<SnapshotsState> = _state

    fun refresh() { _state.value = _state.value.copy(items = manager.list()) }

    fun create(label: String?) {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { manager.create(label) }
            _state.value = SnapshotsState(
                items = manager.list(),
                message = r.fold(
                    onSuccess = { "✅ Created ${it.id} (${it.fileCount} files)" },
                    onFailure = { "❌ ${it.message}" }
                ),
                busy = false,
            )
        }
    }

    fun restore(id: String) {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { manager.restore(id) }
            _state.value = _state.value.copy(
                message = r.fold(
                    onSuccess = { "✅ Restored $it files from $id" },
                    onFailure = { "❌ ${it.message}" }
                ),
                busy = false,
            )
        }
    }

    fun delete(id: String) {
        val ok = manager.delete(id)
        _state.value = SnapshotsState(
            items = manager.list(),
            message = if (ok) "Deleted $id" else "Could not delete $id"
        )
    }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }
}
