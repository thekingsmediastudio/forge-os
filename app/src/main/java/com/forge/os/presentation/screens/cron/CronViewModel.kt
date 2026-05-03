package com.forge.os.presentation.screens.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.cron.CronExecution
import com.forge.os.domain.cron.CronJob
import com.forge.os.domain.cron.CronManager
import com.forge.os.domain.cron.CronRepository
import com.forge.os.domain.cron.TaskType
import com.forge.os.data.api.AiApiManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CronUiState(
    val jobs: List<CronJob> = emptyList(),
    val history: List<CronExecution> = emptyList(),
    val running: String? = null,
    val message: String? = null,
    val availableModels: List<AiApiManager.Quad> = emptyList(),
)

@HiltViewModel
class CronViewModel @Inject constructor(
    private val cronManager: CronManager,
    private val cronRepository: CronRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CronUiState())
    val state: StateFlow<CronUiState> = _state.asStateFlow()

    init { 
        refresh()
        viewModelScope.launch {
            val models = cronManager.availableModels()
            _state.value = _state.value.copy(availableModels = models)
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(
            jobs = cronManager.listJobs(),
            history = cronManager.recentHistory(limit = 30),
        )
    }

    fun create(
        name: String, 
        taskType: TaskType, 
        payload: String, 
        schedule: String,
        overrideProvider: String? = null,
        overrideModel: String? = null
    ) {
        val r = cronManager.addJob(
            name = name, 
            taskType = taskType, 
            payload = payload, 
            scheduleText = schedule,
            overrideProvider = overrideProvider,
            overrideModel = overrideModel
        )
        _state.value = _state.value.copy(message = r.fold({ "Created '${it.name}'" }, { "❌ ${it.message}" }))
        refresh()
    }

    fun toggle(id: String, enabled: Boolean) {
        cronManager.toggleJob(id, enabled); refresh()
    }

    fun remove(id: String) { cronManager.removeJob(id); refresh() }

    fun runNow(id: String) {
        viewModelScope.launch {
            val job = cronManager.getJob(id) ?: return@launch
            _state.value = _state.value.copy(running = id)
            val exec = cronManager.runJob(job)
            _state.value = _state.value.copy(
                running = null,
                message = if (exec.success) "✓ '${job.name}' (${exec.durationMs}ms)"
                          else "✗ '${job.name}': ${exec.error ?: "failed"}",
            )
            refresh()
        }
    }

    fun historyFor(id: String): List<CronExecution> = cronRepository.historyForJob(id)

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }
}
