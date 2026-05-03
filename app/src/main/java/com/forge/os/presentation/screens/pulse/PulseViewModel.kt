package com.forge.os.presentation.screens.pulse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.data.api.CostMeter
import com.forge.os.domain.heartbeat.HeartbeatMonitor
import com.forge.os.domain.heartbeat.SystemStatus
import com.forge.os.domain.cron.CronManager
import com.forge.os.domain.agents.DelegationManager
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.debug.BackgroundTaskLog
import com.forge.os.domain.debug.BackgroundTaskLogManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PulseUiState(
    val system: SystemStatus = SystemStatus(),
    val dailySpend: Double = 0.0,
    val dailyLimit: Double = 1.0,
    val budgetEnabled: Boolean = false,
    val activeCronCount: Int = 0,
    val activeAgentCount: Int = 0,
    val backgroundLogs: List<com.forge.os.domain.debug.BackgroundTaskLog> = emptyList(),
    val lastRefresh: Long = System.currentTimeMillis()
)

@HiltViewModel
class PulseViewModel @Inject constructor(
    private val heartbeatMonitor: HeartbeatMonitor,
    private val costMeter: CostMeter,
    private val cronManager: CronManager,
    private val delegationManager: DelegationManager,
    private val configRepository: ConfigRepository,
    private val backgroundLog: com.forge.os.domain.debug.BackgroundTaskLogManager
) : ViewModel() {

    val state: StateFlow<PulseUiState> = combine(
        heartbeatMonitor.status,
        costMeter.snapshot,
        configRepository.configFlow,
        backgroundLog.logs
    ) { status: SystemStatus, cost: com.forge.os.data.api.CostMeter.CostSnapshot, config: com.forge.os.domain.config.ForgeConfig, logs: List<BackgroundTaskLog> ->
        PulseUiState(
            system = status,
            dailySpend = costMeter.getDailyTotal(),
            dailyLimit = config.costBudget.dailyLimitUsd,
            budgetEnabled = config.costBudget.enabled,
            activeCronCount = cronManager.listJobs().count { it.enabled },
            activeAgentCount = delegationManager.listAll().count { it.status == com.forge.os.domain.agents.SubAgentStatus.COMPLETED || it.status == com.forge.os.domain.agents.SubAgentStatus.RUNNING },
            backgroundLogs = logs,
            lastRefresh = status.timestamp
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PulseUiState())

    fun refresh() {
        viewModelScope.launch {
            heartbeatMonitor.checkNow()
        }
    }
}
