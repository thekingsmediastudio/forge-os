package com.forge.os.domain.debug

import android.util.LruCache
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

@Serializable
data class TraceToolCall(
    val id: String,
    val name: String,
    val argsJson: String,
    val result: String? = null,
    val isError: Boolean = false,
    val durationMs: Long = 0L
)

@Serializable
data class TraceStep(
    val iteration: Int,
    val memoryContext: String,
    val systemPrompt: String,
    val rawResponse: String,
    val toolCalls: List<TraceToolCall> = emptyList(),
    val nestedTraces: List<ReplayTrace> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class ReplayTrace(
    val id: String,
    val parentId: String? = null,
    val prompt: String,
    val agentName: String,
    val model: String,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val success: Boolean = false,
    val finalResult: String? = null,
    val steps: List<TraceStep> = emptyList()
)

@Singleton
class TraceManager @Inject constructor() {
    // Keep last 20 traces in memory
    private val traces = LruCache<String, ReplayTrace>(20)

    fun startTrace(prompt: String, agentName: String, model: String, traceId: String = UUID.randomUUID().toString(), parentId: String? = null): String {
        traces.put(traceId, ReplayTrace(
            id = traceId,
            parentId = parentId,
            prompt = prompt,
            agentName = agentName,
            model = model
        ))
        return traceId
    }

    fun appendStep(traceId: String, step: TraceStep) {
        val trace = traces.get(traceId) ?: return
        traces.put(traceId, trace.copy(steps = trace.steps + step))
    }

    fun finishTrace(traceId: String, success: Boolean, finalResult: String?) {
        val trace = traces.get(traceId) ?: return
        traces.put(traceId, trace.copy(
            completedAt = System.currentTimeMillis(),
            success = success,
            finalResult = finalResult
        ))
    }

    fun removeTrace(id: String) {
        traces.remove(id)
    }

    fun getTrace(id: String): ReplayTrace? = traces.get(id)

    fun getAllTraces(): List<ReplayTrace> {
        val snapshot = traces.snapshot()
        return snapshot.values.sortedByDescending { it.startedAt }
    }
    
    fun clear() {
        traces.evictAll()
    }
}
