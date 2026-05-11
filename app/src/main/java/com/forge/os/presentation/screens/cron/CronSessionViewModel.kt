package com.forge.os.presentation.screens.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.cron.CronExecution
import com.forge.os.domain.cron.CronManager
import com.forge.os.domain.cron.CronRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SessionFilter { ALL, SUCCESS, FAILURE }

data class CronSessionUiState(
    val all: List<CronExecution> = emptyList(),
    val filtered: List<CronExecution> = emptyList(),
    val jobNames: List<Pair<String, String>> = emptyList(), // (id, name)
    val filter: SessionFilter = SessionFilter.ALL,
    val search: String = "",
    val selectedJobId: String? = null,
    val liveTail: Boolean = false,
    val totalRuns: Int = 0,
    val failureCount: Int = 0,
    val successRatePct: Int = 0,
    val avgDurationMs: Long = 0L,
    val last24hCount: Int = 0,
    val dueCount: Int = 0,
    val activeJobs: Int = 0,
    val message: String? = null,
)

/**
 * Drives [CronSessionScreen]. Pulls the recent execution history (last 14 days,
 * up to ~500 records), derives stats and applies user filters in-memory.
 */
@HiltViewModel
class CronSessionViewModel @Inject constructor(
    private val cronManager: CronManager,
    private val cronRepository: CronRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CronSessionUiState())
    val state: StateFlow<CronSessionUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        val all = cronRepository.recentHistory(days = 14, limit = 500)
        val jobs = cronManager.listJobs()
        val now = System.currentTimeMillis()
        val last24 = all.count { now - it.startedAt < 86_400_000L }
        val failures = all.count { !it.success }
        val avg = if (all.isEmpty()) 0L else all.sumOf { it.durationMs } / all.size
        val pct = if (all.isEmpty()) 0
                  else (((all.size - failures).toDouble() / all.size) * 100).toInt()
        val due = jobs.count { it.enabled && it.nextRunAt <= now }
        val names = jobs.map { it.id to it.name }
            .distinctBy { it.first }
            .sortedBy { it.second }

        _state.value = _state.value.copy(
            all = all,
            jobNames = names,
            totalRuns = all.size,
            failureCount = failures,
            successRatePct = pct,
            avgDurationMs = avg,
            last24hCount = last24,
            dueCount = due,
            activeJobs = jobs.count { it.enabled },
        ).let { it.copy(filtered = applyFilters(it)) }
    }

    fun setFilter(f: SessionFilter) {
        _state.value = _state.value.copy(filter = f).let { it.copy(filtered = applyFilters(it)) }
    }

    fun setSearch(q: String) {
        _state.value = _state.value.copy(search = q).let { it.copy(filtered = applyFilters(it)) }
    }

    fun selectJob(id: String?) {
        _state.value = _state.value.copy(selectedJobId = id).let { it.copy(filtered = applyFilters(it)) }
    }

    fun setLiveTail(on: Boolean) {
        _state.value = _state.value.copy(liveTail = on)
    }

    fun runJobAgain(jobId: String) {
        viewModelScope.launch {
            val job = cronManager.getJob(jobId)
                ?: run {
                    _state.value = _state.value.copy(message = "Job no longer exists")
                    return@launch
                }
            val exec = cronManager.runJob(job)
            _state.value = _state.value.copy(
                message = if (exec.success) "✓ '${job.name}' (${exec.durationMs}ms)"
                          else "✗ '${job.name}': ${exec.error ?: "failed"}",
            )
            refresh()
        }
    }

    fun clearHistory() {
        val n = cronRepository.clearAllHistory()
        _state.value = _state.value.copy(message = "Cleared $n history file(s)")
        refresh()
    }

    fun exportHistory() {
        val path = cronRepository.exportHistoryAsJson()
        _state.value = _state.value.copy(
            message = if (path != null) "Exported → $path" else "Export failed",
        )
    }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }

    private fun applyFilters(s: CronSessionUiState): List<CronExecution> {
        val q = s.search.trim().lowercase()
        return s.all.asSequence()
            .filter {
                when (s.filter) {
                    SessionFilter.ALL -> true
                    SessionFilter.SUCCESS -> it.success
                    SessionFilter.FAILURE -> !it.success
                }
            }
            .filter { s.selectedJobId == null || it.jobId == s.selectedJobId }
            .filter {
                q.isEmpty() ||
                    it.jobName.lowercase().contains(q) ||
                    it.output.lowercase().contains(q) ||
                    (it.error?.lowercase()?.contains(q) ?: false)
            }
            .toList()
    }
}
