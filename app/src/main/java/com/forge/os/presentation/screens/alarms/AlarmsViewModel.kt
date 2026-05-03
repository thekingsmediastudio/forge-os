package com.forge.os.presentation.screens.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.alarms.AlarmAction
import com.forge.os.domain.alarms.AlarmItem
import com.forge.os.domain.alarms.AlarmRepository
import com.forge.os.domain.alarms.AlarmSession
import com.forge.os.domain.alarms.AlarmSessionLog
import com.forge.os.domain.alarms.ForgeAlarmScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlarmsViewModel @Inject constructor(
    private val repository: AlarmRepository,
    private val scheduler: ForgeAlarmScheduler,
    private val sessionLog: AlarmSessionLog,
    private val aiApiManager: com.forge.os.data.api.AiApiManager,
) : ViewModel() {

    private val _alarms = MutableStateFlow<List<AlarmItem>>(emptyList())
    val alarms: StateFlow<List<AlarmItem>> = _alarms

    private val _sessions = MutableStateFlow<List<AlarmSession>>(emptyList())
    val sessions: StateFlow<List<AlarmSession>> = _sessions

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _alarms.value = repository.all()
            _sessions.value = sessionLog.all()
        }
    }

    fun addAlarm(label: String, triggerAt: Long, action: AlarmAction, payload: String, repeatMs: Long, provider: String? = null, model: String? = null) {
        viewModelScope.launch {
            val item = AlarmItem(
                id = java.util.UUID.randomUUID().toString(),
                label = label,
                triggerAt = triggerAt,
                action = action,
                payload = payload,
                repeatIntervalMs = repeatMs,
                enabled = true,
                overrideProvider = provider,
                overrideModel = model
            )
            repository.upsert(item)
            scheduler.scheduleExact(item)
            refresh()
        }
    }

    suspend fun availableModels() = aiApiManager.availableModels()

    fun toggle(item: AlarmItem) {
        viewModelScope.launch {
            val updated = item.copy(enabled = !item.enabled)
            repository.upsert(updated)
            if (updated.enabled) scheduler.scheduleExact(updated) else scheduler.cancel(updated.id)
            refresh()
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            scheduler.cancel(id)
            repository.remove(id)
            refresh()
        }
    }

    fun clearSessions() {
        viewModelScope.launch {
            sessionLog.clear()
            refresh()
        }
    }
}
