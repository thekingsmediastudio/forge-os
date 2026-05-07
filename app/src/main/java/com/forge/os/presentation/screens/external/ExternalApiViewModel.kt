package com.forge.os.presentation.screens.external

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.external.Capabilities
import com.forge.os.external.ExternalApiBridge
import com.forge.os.external.ExternalAuditEntry
import com.forge.os.external.ExternalAuditLog
import com.forge.os.external.ExternalCaller
import com.forge.os.external.ExternalCallerRegistry
import com.forge.os.external.GrantStatus
import com.forge.os.external.RateLimit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ExternalApiState(
    val masterEnabled: Boolean = false,
    val callers: List<ExternalCaller> = emptyList(),
    val recentAudit: List<ExternalAuditEntry> = emptyList(),
)

@HiltViewModel
class ExternalApiViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val registry: ExternalCallerRegistry,
    private val audit: ExternalAuditLog,
    private val bridge: ExternalApiBridge,
) : ViewModel() {

    private val _state = MutableStateFlow(ExternalApiState())
    val state: StateFlow<ExternalApiState> = _state.asStateFlow()

    init { refresh(); observeRegistry() }

    private fun observeRegistry() {
        viewModelScope.launch {
            registry.callers.collect { _state.value = _state.value.copy(callers = it) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val cfg = configRepository.get()
            val tail = withContext(Dispatchers.IO) { audit.tail(100) }
            _state.value = _state.value.copy(
                masterEnabled = cfg.externalApi.enabled,
                callers = registry.list(),
                recentAudit = tail,
            )
        }
    }

    fun setMasterEnabled(enabled: Boolean) {
        viewModelScope.launch {
            configRepository.update { it.copy(externalApi = it.externalApi.copy(enabled = enabled)) }
            _state.value = _state.value.copy(masterEnabled = enabled)
        }
    }

    fun grant(caller: ExternalCaller, caps: Capabilities) {
        registry.upsert(caller.copy(status = GrantStatus.GRANTED, capabilities = caps))
        refresh()
    }

    fun deny(caller: ExternalCaller) {
        registry.setStatus(caller.packageName, GrantStatus.DENIED); refresh()
    }

    fun revoke(caller: ExternalCaller) {
        registry.setStatus(caller.packageName, GrantStatus.REVOKED); refresh()
    }

    fun remove(caller: ExternalCaller) {
        registry.remove(caller.packageName); refresh()
    }

    fun setRateLimit(caller: ExternalCaller, callsPerMin: Int, tokensPerDay: Int) {
        registry.setRateLimit(caller.packageName, RateLimit(callsPerMin, tokensPerDay)); refresh()
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            audit.clear()
            _state.value = _state.value.copy(recentAudit = emptyList())
        }
    }
}
