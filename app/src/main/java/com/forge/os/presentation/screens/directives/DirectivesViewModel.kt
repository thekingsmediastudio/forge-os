package com.forge.os.presentation.screens.directives

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.directives.AgentDirective
import com.forge.os.domain.directives.DirectivesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DirectivesUiState(
    val rules: List<AgentDirective> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class DirectivesViewModel @Inject constructor(
    private val directivesManager: DirectivesManager
) : ViewModel() {

    private val _state = MutableStateFlow(DirectivesUiState())
    val state: StateFlow<DirectivesUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            try {
                val rules = directivesManager.listRules()
                _state.value = _state.value.copy(rules = rules, isLoading = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    fun toggleRule(id: String, enabled: Boolean) {
        directivesManager.toggleRule(id, enabled)
        refresh()
    }

    fun deleteRule(id: String) {
        directivesManager.deleteRule(id)
        refresh()
    }

    fun addRule(content: String, scope: String = "global") {
        if (content.isBlank()) return
        directivesManager.addRule(content, scope = scope)
        refresh()
    }
}
