package com.forge.os.presentation.screens.sentinel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.cron.TaskType
import com.forge.os.domain.sentinel.SentinelEventType
import com.forge.os.domain.sentinel.SentinelRepository
import com.forge.os.domain.sentinel.SentinelTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SentinelViewModel @Inject constructor(
    private val repository: SentinelRepository
) : ViewModel() {

    private val _sentinels = MutableStateFlow<List<SentinelTrigger>>(emptyList())
    val sentinels: StateFlow<List<SentinelTrigger>> = _sentinels.asStateFlow()

    init {
        loadSentinels()
    }

    private fun loadSentinels() {
        _sentinels.value = repository.all().sortedByDescending { it.lastFiredAt ?: 0L }
    }

    fun addSentinel(
        name: String,
        eventType: SentinelEventType,
        condition: String?,
        taskType: TaskType,
        payload: String
    ) {
        val trigger = SentinelTrigger(
            id = "sentinel_${UUID.randomUUID().toString().take(8)}",
            name = name,
            eventType = eventType,
            condition = condition?.takeIf { it.isNotBlank() },
            taskType = taskType,
            payload = payload
        )
        repository.save(trigger)
        loadSentinels()
    }

    fun deleteSentinel(id: String) {
        repository.remove(id)
        loadSentinels()
    }

    fun toggleSentinel(id: String, enabled: Boolean) {
        repository.setEnabled(id, enabled)
        loadSentinels()
    }
}
