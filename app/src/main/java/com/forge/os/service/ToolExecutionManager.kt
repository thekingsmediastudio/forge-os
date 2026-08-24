package com.forge.os.service

import com.forge.os.data.api.ToolError
import com.forge.os.data.api.ToolProgressInfo
import com.forge.os.data.api.ToolStatusResponse
import com.forge.os.data.api.ResourceUsage
import timber.log.Timber
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages asynchronous tool execution operations.
 * Tracks operation status, progress, and results.
 * Integrated with EventBroadcaster for real-time updates.
 */
@Singleton
class ToolExecutionManager @Inject constructor(
    private val eventBroadcaster: dagger.Lazy<EventBroadcaster>
) {
    
    private val operations = ConcurrentHashMap<String, ToolOperation>()
    
    /**
     * Internal representation of a tool operation
     */
    private data class ToolOperation(
        val opId: String,
        val toolName: String,
        var status: String,
        val startTime: Long,
        var endTime: Long? = null,
        var progress: ToolProgressInfo? = null,
        var output: String? = null,
        var error: ToolError? = null,
        var resourceUsage: ResourceUsage? = null,
        var job: Job? = null
    )
    
    /**
     * Register a new tool operation
     * Emits tool_start event
     * @return operation ID
     * Requirements: 4.4, 4.8
     */
    fun registerOperation(opId: String, toolName: String, args: Map<String, Any> = emptyMap()): String {
        val operation = ToolOperation(
            opId = opId,
            toolName = toolName,
            status = "pending",
            startTime = System.currentTimeMillis()
        )
        operations[opId] = operation
        Timber.d("ToolExecutionManager: Registered operation $opId for tool $toolName")
        
        // Emit tool_start event
        try {
            eventBroadcaster.get().emitToolStart(opId, toolName, args)
        } catch (e: Exception) {
            Timber.e(e, "ToolExecutionManager: Failed to emit tool_start event")
        }
        
        return opId
    }
    
    /**
     * Attach the coroutine Job executing this operation so cancellation can
     * actually stop it (Task 8.3).
     */
    fun attachJob(opId: String, job: Job) {
        operations[opId]?.let { op -> op.job = job }
    }

    /**
     * Update operation status
     */
    fun updateStatus(opId: String, status: String) {
        operations[opId]?.let { op ->
            op.status = status
            if (status == "completed" || status == "failed" || status == "cancelled") {
                op.endTime = System.currentTimeMillis()
            }
            Timber.d("ToolExecutionManager: Updated operation $opId status to $status")
        }
    }
    
    /**
     * Update operation progress
     * Emits tool_progress event
     * Requirement: 4.4
     */
    fun updateProgress(opId: String, percent: Int, message: String? = null) {
        operations[opId]?.let { op ->
            op.progress = ToolProgressInfo(percent = percent, message = message)
            if (op.status == "pending") {
                op.status = "running"
            }
            Timber.d("ToolExecutionManager: Updated operation $opId progress to $percent%")
            
            // Emit tool_progress event
            try {
                eventBroadcaster.get().emitToolProgress(opId, percent, message)
            } catch (e: Exception) {
                Timber.e(e, "ToolExecutionManager: Failed to emit tool_progress event")
            }
        }
    }

    /**
     * Requirement 4.4 - progress reporting alias (spec name).
     */
    fun reportProgress(opId: String, percent: Int, message: String? = null) {
        updateProgress(opId, percent, message)
    }

    /**
     * Requirement 4.7 - current thread CPU time in ms (ThreadMXBean).
     */
    private fun currentCpuMs(): Long {
        return try {
            val bean = ManagementFactory.getThreadMXBean()
            if (bean.isCurrentThreadCpuTimeSupported) bean.currentThreadCpuTime / 1_000_000 else 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Requirement 4.7 - RSS-ish memory estimate via Runtime.
     */
    private fun currentMemoryBytes(): Long {
        return try {
            val rt = Runtime.getRuntime()
            rt.totalMemory() - rt.freeMemory()
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Requirement 4.8 - finalize endTime, duration and resourceUsage.
     */
    private fun finalizeOperation(op: ToolOperation) {
        op.endTime = System.currentTimeMillis()
        op.resourceUsage = ResourceUsage(
            cpuMs = currentCpuMs(),
            memoryBytes = currentMemoryBytes()
        )
    }

    /**
     * Set operation output (for completed operations)
     * Emits tool_complete event with duration and resource usage
     * Requirement: 4.8
     */
    fun setOutput(opId: String, output: String) {
        operations[opId]?.let { op ->
            op.output = output
            op.status = "completed"
            finalizeOperation(op)
            Timber.d("ToolExecutionManager: Set output for operation $opId")
            
            // Emit tool_complete event
            try {
                val duration = op.endTime!! - op.startTime
                val resourceUsage = op.resourceUsage ?: ResourceUsage(cpuMs = 0, memoryBytes = 0)
                
                eventBroadcaster.get().emitToolComplete(
                    opId = opId,
                    output = output,
                    duration = duration,
                    resourceUsage = ResourceUsagePayload(
                        cpuMs = resourceUsage.cpuMs,
                        memoryBytes = resourceUsage.memoryBytes
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "ToolExecutionManager: Failed to emit tool_complete event")
            }
        }
    }
    
    /**
     * Set operation error (for failed operations)
     * Emits tool_error event
     * Requirement: 4.8
     */
    fun setError(opId: String, error: ToolError) {
        operations[opId]?.let { op ->
            op.error = error
            op.status = "failed"
            finalizeOperation(op)
            Timber.d("ToolExecutionManager: Set error for operation $opId: ${error.message}")
            
            // Emit tool_error event
            try {
                eventBroadcaster.get().emitToolError(
                    opId = opId,
                    error = ToolErrorPayload(
                        code = error.code,
                        message = error.message,
                        stackTrace = error.stackTrace
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "ToolExecutionManager: Failed to emit tool_error event")
            }
        }
    }
    
    /**
     * Set resource usage statistics
     */
    fun setResourceUsage(opId: String, cpuMs: Long, memoryBytes: Long) {
        operations[opId]?.let { op ->
            op.resourceUsage = ResourceUsage(cpuMs = cpuMs, memoryBytes = memoryBytes)
        }
    }
    
    /**
     * Get operation status
     */
    fun getStatus(opId: String): ToolStatusResponse? {
        val op = operations[opId] ?: return null
        
        return ToolStatusResponse(
            opId = op.opId,
            toolName = op.toolName,
            status = op.status,
            startTime = op.startTime,
            endTime = op.endTime,
            progress = op.progress,
            output = op.output,
            error = op.error,
            resourceUsage = op.resourceUsage
        )
    }
    
    /**
     * Cancel an operation
     * Emits tool_error event with cancellation reason
     * Requirement: 4.5, 4.6
     */
    fun cancelOperation(opId: String): Boolean {
        val op = operations[opId] ?: return false
        
        if (op.status == "completed" || op.status == "failed" || op.status == "cancelled") {
            return false // Already finished
        }
        
        op.job?.cancel()
        op.status = "cancelled"
        op.endTime = System.currentTimeMillis()
        Timber.d("ToolExecutionManager: Cancelled operation $opId")
        
        // Emit tool_error event with cancellation reason
        try {
            eventBroadcaster.get().emitToolError(
                opId = opId,
                error = ToolErrorPayload(
                    code = "CANCELLED",
                    message = "Operation cancelled by user",
                    stackTrace = null
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "ToolExecutionManager: Failed to emit cancellation event")
        }
        
        return true
    }
    
    /**
     * Clean up old operations (older than 1 hour)
     */
    fun cleanup() {
        val cutoffTime = System.currentTimeMillis() - (60 * 60 * 1000) // 1 hour
        val toRemove = mutableListOf<String>()
        
        operations.forEach { (opId, op) ->
            val timeToCheck = op.endTime ?: op.startTime
            if (timeToCheck < cutoffTime) {
                toRemove.add(opId)
            }
        }
        
        toRemove.forEach { opId ->
            operations.remove(opId)
            Timber.d("ToolExecutionManager: Cleaned up old operation $opId")
        }
        
        if (toRemove.isNotEmpty()) {
            Timber.i("ToolExecutionManager: Cleaned up ${toRemove.size} old operations")
        }
    }
}
