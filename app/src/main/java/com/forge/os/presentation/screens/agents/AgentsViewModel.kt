package com.forge.os.presentation.screens.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.agents.DelegationManager
import com.forge.os.domain.agents.SubAgent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AgentsUiState(
    val agents: List<SubAgent> = emptyList(),
    val message: String? = null,
    val spawning: Boolean = false,
)

@HiltViewModel
class AgentsViewModel @Inject constructor(
    private val delegationManager: DelegationManager,
    private val aiApiManager: com.forge.os.data.api.AiApiManager,
) : ViewModel() {

    private val _state = MutableStateFlow(AgentsUiState())
    val state: StateFlow<AgentsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() { _state.value = _state.value.copy(agents = delegationManager.listAll()) }

    fun spawn(goal: String, contextText: String, provider: String? = null, model: String? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(spawning = true)
            val outcome = delegationManager.spawnAndAwait(goal, contextText, overrideProvider = provider, overrideModel = model)
            _state.value = _state.value.copy(
                spawning = false,
                message = if (outcome.success) "✓ ${outcome.agent.id}" else "✗ ${outcome.agent.error ?: "failed"}",
            )
            refresh()
        }
    }

    suspend fun availableModels() = aiApiManager.availableModels()

    fun cancel(id: String) {
        delegationManager.cancel(id)
        _state.value = _state.value.copy(message = "Cancel requested for $id")
        refresh()
    }

    fun transcript(id: String): String = delegationManager.transcript(id)
    fun get(id: String): SubAgent? = delegationManager.get(id)
    fun dismissMessage() { _state.value = _state.value.copy(message = null) }
}
