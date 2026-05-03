package com.forge.os.domain.debug

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque

enum class TaskSource {
    CRON, ALARM, PROACTIVE, REFLECTION, DELEGATION, SYSTEM
}

data class BackgroundTaskLog(
    val id: String,
    val source: TaskSource,
    val label: String,
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val durationMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
)

@Singleton
class BackgroundTaskLogManager @Inject constructor() {
    private val _logs = MutableStateFlow<List<BackgroundTaskLog>>(emptyList())
    val logs: StateFlow<List<BackgroundTaskLog>> = _logs.asStateFlow()

    private val logBuffer = ConcurrentLinkedDeque<BackgroundTaskLog>()
    private val MAX_LOGS = 100

    fun addLog(log: BackgroundTaskLog) {
        logBuffer.addFirst(log)
        while (logBuffer.size > MAX_LOGS) {
            logBuffer.removeLast()
        }
        _logs.value = logBuffer.toList()
    }

    fun clear() {
        logBuffer.clear()
        _logs.value = emptyList()
    }
}
