package com.forge.os.domain.cron

import kotlinx.serialization.Serializable

/**
 * A scheduled job persisted in the cron queue.
 */
@Serializable
data class CronJob(
    val id: String,
    val name: String,
    val taskType: TaskType,
    val payload: String,                 // python code, shell command, or agent prompt
    val schedule: CronSchedule,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val nextRunAt: Long,
    val lastRunAt: Long? = null,
    val runCount: Int = 0,
    val failureCount: Int = 0,
    val tags: List<String> = emptyList(),
    val notifyOnComplete: Boolean = true,
    /** Phase 3 — per-job model override (e.g. "openai", "gpt-4o"). If blank, use default routing. */
    val overrideProvider: String? = null,
    val overrideModel: String? = null
)

@Serializable
enum class TaskType {
    PYTHON,    // execute via SandboxManager.executePython
    SHELL,     // execute via SandboxManager.executeShell
    PROMPT     // execute via ReActAgent (requires API key)
}

/**
 * Simplified schedule representation supporting the most common patterns.
 *
 * - [intervalMinutes] non-null  → run every N minutes (must be ≥ 5)
 * - [dailyAt] non-null          → run once per day at HH:MM (24h, device local time)
 * - [oneShotAt] non-null        → run exactly once at the given epoch ms
 */
@Serializable
data class CronSchedule(
    val intervalMinutes: Int? = null,
    val dailyAt: String? = null,         // "HH:MM" 24-hour, local time
    val oneShotAt: Long? = null,
    val description: String = ""
) {
    val kind: String get() = when {
        intervalMinutes != null -> "interval"
        dailyAt != null         -> "daily"
        oneShotAt != null       -> "once"
        else                    -> "invalid"
    }

    fun pretty(): String = when {
        intervalMinutes != null -> "every ${intervalMinutes}m"
        dailyAt != null         -> "daily at $dailyAt"
        oneShotAt != null       -> "once at ${java.util.Date(oneShotAt)}"
        else                    -> description.ifBlank { "<unscheduled>" }
    }
}

/**
 * Single execution record persisted in cron history.
 */
@Serializable
data class CronExecution(
    val jobId: String,
    val jobName: String,
    val startedAt: Long,
    val finishedAt: Long,
    val success: Boolean,
    val output: String,
    val error: String? = null
) {
    val durationMs: Long get() = finishedAt - startedAt
}
