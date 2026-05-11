package com.forge.os.presentation.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.config.UserOverrides
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AdvancedOverridesUiState(
    val lockAgentOut: Boolean = true,
    val blockedHosts: List<String> = emptyList(),
    val blockedExtensions: List<String> = emptyList(),
    val blockedConfigPaths: List<String> = emptyList(),
)

@HiltViewModel
class AdvancedOverridesViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AdvancedOverridesUiState())
    val state: StateFlow<AdvancedOverridesUiState> = _state.asStateFlow()

    init { refresh() }

    private fun refresh() {
        val o = configRepository.get().permissions.userOverrides
        _state.value = AdvancedOverridesUiState(
            lockAgentOut = o.lockAgentOut,
            blockedHosts = o.extraBlockedHosts.sorted(),
            blockedExtensions = o.extraBlockedExtensions.sorted(),
            blockedConfigPaths = o.extraBlockedConfigPaths.sorted(),
        )
    }

    fun setLockAgentOut(value: Boolean) = mutate { it.copy(lockAgentOut = value) }

    fun addHost(value: String)         = mutate { it.copy(extraBlockedHosts = (it.extraBlockedHosts + value.lowercase()).distinct()) }
    fun removeHost(value: String)      = mutate { it.copy(extraBlockedHosts = it.extraBlockedHosts - value) }
    fun resetHosts()                   = mutate { it.copy(extraBlockedHosts = emptyList()) }

    fun addExtension(value: String)    = mutate { it.copy(extraBlockedExtensions = (it.extraBlockedExtensions + value.lowercase().removePrefix(".")).distinct()) }
    fun removeExtension(value: String) = mutate { it.copy(extraBlockedExtensions = it.extraBlockedExtensions - value) }
    fun resetExtensions()              = mutate { it.copy(extraBlockedExtensions = emptyList()) }

    fun addConfigPath(value: String)    = mutate { it.copy(extraBlockedConfigPaths = (it.extraBlockedConfigPaths + value).distinct()) }
    fun removeConfigPath(value: String) = mutate { it.copy(extraBlockedConfigPaths = it.extraBlockedConfigPaths - value) }
    fun resetConfigPaths()              = mutate { it.copy(extraBlockedConfigPaths = emptyList()) }

    private fun mutate(transform: (UserOverrides) -> UserOverrides) {
        viewModelScope.launch {
            configRepository.update { c ->
                c.copy(permissions = c.permissions.copy(
                    userOverrides = transform(c.permissions.userOverrides)
                ))
            }
            refresh()
        }
    }
}
