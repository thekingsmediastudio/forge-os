package com.forge.os.presentation.screens.debugger

import androidx.lifecycle.ViewModel
import com.forge.os.domain.debug.ReplayTrace
import com.forge.os.domain.debug.TraceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class DebuggerUiState(
    val traces: List<ReplayTrace> = emptyList(),
    val selectedTrace: ReplayTrace? = null
)

@HiltViewModel
class DebuggerViewModel @Inject constructor(
    private val traceManager: TraceManager
) : ViewModel() {

    private val _state = MutableStateFlow(DebuggerUiState())
    val state: StateFlow<DebuggerUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val allTraces = traceManager.getAllTraces()
        _state.value = _state.value.copy(
            traces = allTraces,
            selectedTrace = allTraces.find { it.id == _state.value.selectedTrace?.id }
        )
    }

    fun selectTrace(id: String?) {
        if (id == null) {
            _state.value = _state.value.copy(selectedTrace = null)
            return
        }
        val trace = traceManager.getTrace(id)
        _state.value = _state.value.copy(selectedTrace = trace)
    }

    fun clearTraces() {
        traceManager.clear()
        refresh()
    }
}
