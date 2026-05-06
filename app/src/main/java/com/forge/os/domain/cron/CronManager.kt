package com.forge.os.domain.cron

import com.forge.os.data.api.AiApiManager
import com.forge.os.domain.security.ProviderSpec
import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.domain.agent.AgentEvent
import com.forge.os.domain.agent.ReActAgent
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.memory.MemoryManager
import com.forge.os.domain.notifications.NotificationHelper
import dagger.Lazy
import kotlinx.coroutines.flow.toList
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level cron façade used by tools, the UI, and the worker.
 *
 * - addJob / listJobs / removeJob / toggleJob — CRUD
 * - runDueJobs — invoked by CronExecutionWorker (or manually for tests)
 */
@Singleton
class CronManager @Inject constructor(
    private val repository: CronRepository,
    private val sandboxManager: SandboxManager,
    private val configRepository: ConfigRepository,
    private val memoryManager: MemoryManager,
    private val notificationHelper: NotificationHelper,
    private val aiApiManager: Lazy<AiApiManager>,
    // Lazy because ReActAgent transitively depends on a lot of singletons; using
    // dagger.Lazy avoids any cycle risk and defers construction until a PROMPT
    // job is actually run.
    private val reActAgent: Lazy<ReActAgent>,
    private val backgroundLog: com.forge.os.domain.debug.BackgroundTaskLogManager,
    // Enhanced Integration: Connect with learning systems (Lazy to break circular dependency)
    private val reflectionManager: Lazy<com.forge.os.domain.agent.ReflectionManager>,
    private val userPreferencesManager: com.forge.os.domain.user.UserPreferencesManager,
) {

    // ─── CRUD ────────────────────────────────────────────────────────────────

    /**
     * Add a new cron job with a free-text schedule expression
     * (e.g. "every 15 minutes", "daily at 09:00").
     */
    fun addJob(
        name: String,
        taskType: TaskType,
        payload: String,
        scheduleText: String,
        notifyOnComplete: Boolean = true,
        tags: List<String> = emptyList(),
        overrideProvider: String? = null,
        overrideModel: String? = null,
    ): Result<CronJob> {
        val limits = configRepository.get().cronSettings
        if (repository.all().size >= limits.maxJobs) {
            return Result.failure(IllegalStateException("Cron quota reached (${limits.maxJobs} jobs)"))
        }
        val schedule = CronScheduler.parse(scheduleText)
            ?: return Result.failure(IllegalArgumentException("Could not parse schedule: '$scheduleText'"))

        val now = System.currentTimeMillis()
        val job = CronJob(
            id = "cron_${UUID.randomUUID().toString().take(8)}",
            name = name,
            taskType = taskType,
            payload = payload,
            schedule = schedule,
            nextRunAt = CronScheduler.nextRun(schedule, lastRun = null, now = now),
            tags = tags,
            notifyOnComplete = notifyOnComplete,
            overrideProvider = overrideProvider,
            overrideModel = overrideModel
        )
        repository.add(job)
        Timber.i("CronManager: added job ${job.id} '$name' — next run at ${java.util.Date(job.nextRunAt)}")
        return Result.success(job)
    }

    fun listJobs(): List<CronJob> = repository.all().sortedBy { it.nextRunAt }

    fun getJob(id: String): CronJob? = repository.byId(id)

    fun removeJob(id: String): Boolean {
        val ok = repository.remove(id)
        Timber.i("CronManager: removed job $id (existed=$ok)")
        return ok
    }

    fun toggleJob(id: String, enabled: Boolean): Boolean = repository.setEnabled(id, enabled)

    fun recentHistory(limit: Int = 20) = repository.recentHistory(limit = limit)

    // ─── Execution ───────────────────────────────────────────────────────────

    /**
     * Execute every job that is due right now.
     * Called by [com.forge.os.data.workers.CronExecutionWorker].
     */
    suspend fun runDueJobs(): List<CronExecution> {
        val due = repository.dueJobs()
        if (due.isEmpty()) return emptyList()
        Timber.i("CronManager: ${due.size} job(s) due")
        return due.map { runJob(it) }
    }

    /**
     * Execute a single job immediately, regardless of its schedule. Returns the
     * execution record. The job is rescheduled (or removed if one-shot).
     */
    suspend fun runJob(job: CronJob): CronExecution {
        val started = System.currentTimeMillis()
        var success = false
        var output = ""
        var error: String? = null

        // Enhanced Integration: Learn cron job patterns
        try {
            userPreferencesManager.recordInteractionPattern("uses_cron_${job.taskType.name.lowercase()}", 1)
            
            // Learn scheduling patterns
            val hour = java.time.LocalDateTime.now().hour
            userPreferencesManager.recordInteractionPattern("cron_active_hour_$hour", 1)
            
            // Learn job frequency patterns
            val scheduleType = when {
                job.schedule.intervalMinutes != null -> "interval_based"
                job.schedule.dailyAt != null -> "daily_scheduled"
                job.schedule.oneShotAt != null -> "one_shot"
                else -> "custom_schedule"
            }
            userPreferencesManager.recordInteractionPattern("prefers_$scheduleType", 1)
        } catch (e: Exception) {
            Timber.w(e, "Failed to learn cron patterns")
        }

        try {
            val result: Result<String> = when (job.taskType) {
                TaskType.PYTHON -> sandboxManager.executePython(job.payload)
                TaskType.SHELL  -> sandboxManager.executeShell(job.payload)
                TaskType.PROMPT -> {
                    val spec = aiApiManager.get().resolveSpec(job.overrideProvider, job.overrideModel)
                    runPromptJob(job.payload, spec)
                }
            }
            output = result.getOrElse { throw it }
            success = true
        } catch (e: Exception) {
            Timber.e(e, "Cron job ${job.id} failed")
            error = e.message ?: e::class.java.simpleName
            output = "Error: $error"
        }

        val finished = System.currentTimeMillis()
        val execution = CronExecution(
            jobId = job.id, jobName = job.name,
            startedAt = started, finishedAt = finished,
            success = success, output = output.take(4000), error = error
        )
        repository.recordExecution(execution)

        // Enhanced Integration: Record cron execution patterns
        try {
            val steps = listOf(
                com.forge.os.domain.agent.ExecutionStep(
                    stepNumber = 1,
                    action = "Cron job execution: ${job.taskType}",
                    tool = "cron_${job.taskType.name.lowercase()}",
                    args = job.payload.take(200),
                    result = output.take(500),
                    duration = finished - started,
                    success = success
                )
            )
            
            reflectionManager.get().recordExecution(
                taskId = "cron_${job.id}_${started}",
                goal = "Execute scheduled job: ${job.name}",
                steps = steps,
                success = success,
                outcome = if (success) "Cron job completed successfully" else "Cron job failed: $error",
                tags = listOf("cron_execution", "scheduled_task", job.taskType.name.lowercase()) + job.tags
            )
            
            if (success) {
                reflectionManager.get().recordPattern(
                    pattern = "Successful cron execution: ${job.taskType}",
                    description = "Cron job '${job.name}' executed successfully in ${finished - started}ms",
                    applicableTo = listOf("cron", "scheduling", job.taskType.name.lowercase()),
                    tags = listOf("cron_success", "scheduling_pattern", "automation")
                )
            } else {
                reflectionManager.get().recordFailureAndRecovery(
                    taskId = "cron_${job.id}_${started}",
                    failureReason = "Cron job failed: $error",
                    recoveryStrategy = "Check job configuration, validate payload, or adjust schedule",
                    tags = listOf("cron_failure", "scheduling_error", job.taskType.name.lowercase())
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to record cron execution in ReflectionManager")
        }

        // Reschedule or remove
        if (job.schedule.oneShotAt != null) {
            repository.remove(job.id)
        } else {
            val next = CronScheduler.nextRun(job.schedule, lastRun = finished, now = finished)
            repository.update(job.copy(
                lastRunAt = finished,
                nextRunAt = next,
                runCount = job.runCount + 1,
                failureCount = job.failureCount + if (success) 0 else 1
            ))
        }

        // Side effects: memory log + notification
        try {
            memoryManager.logEvent(
                role = "cron",
                content = "Job '${job.name}' ${if (success) "✓" else "✗"} in ${execution.durationMs}ms",
                tags = listOf("cron", job.id) + job.tags
            )
        } catch (_: Exception) {}

        if (job.notifyOnComplete && (configRepository.get().cronSettings.notifyOnFailure || success)) {
            notificationHelper.notifyJobComplete(job, execution)
        }

        // Feature: Unified Background Log
        backgroundLog.addLog(
            com.forge.os.domain.debug.BackgroundTaskLog(
                id = "cron_${System.currentTimeMillis()}",
                source = com.forge.os.domain.debug.TaskSource.CRON,
                label = job.name,
                success = success,
                output = output,
                error = error,
                durationMs = execution.durationMs,
                timestamp = finished
            )
        )

        return execution
    }

    /**
     * Drive ReActAgent.run to completion for a scheduled PROMPT job and
     * collapse the resulting flow into a single text result. Tool errors
     * within the loop don't fail the job — only an emitted [AgentEvent.Error]
     * does. The final assistant message is returned (or a synthesised summary
     * if the agent only produced tool output).
     */
    /**
     * Phase R — public entry point for one-shot agent prompts (used by
     * AlarmReceiver.PROMPT_AGENT). Executes the prompt through the
     * ReActAgent loop, collapses the flow to a single text result, and
     * logs to memory. Throws on agent error.
     */
    suspend fun runPromptOnce(label: String, prompt: String, spec: ProviderSpec? = null): String {
        Timber.i("CronManager.runPromptOnce: $label")
        val out = runPromptJob(prompt, spec).getOrThrow()
        try {
            memoryManager.logEvent(
                role = "agent_background",
                content = "[$label] ${out.take(800)}",
                tags = listOf("background", "alarm_or_cron"),
            )
        } catch (_: Exception) {}
        return out
    }

    private suspend fun runPromptJob(prompt: String, spec: ProviderSpec? = null): Result<String> = runCatching {
        val events = reActAgent.get().run(userMessage = prompt, spec = spec).toList()
        val error = events.filterIsInstance<AgentEvent.Error>().firstOrNull()
        if (error != null) throw IllegalStateException(error.message)
        val finalText = events.filterIsInstance<AgentEvent.Response>()
            .lastOrNull()?.text
        finalText ?: events.filterIsInstance<AgentEvent.ToolResult>()
            .joinToString("\n") { "${it.name} → ${it.result}" }
            .ifBlank { "(prompt completed with no textual output)" }
    }

    fun summary(): String {
        val all = repository.all()
        val enabled = all.count { it.enabled }
        val dueSoon = all.count { it.enabled && it.nextRunAt - System.currentTimeMillis() < 3_600_000L }
        return "⏰ Cron: ${all.size} jobs ($enabled enabled, $dueSoon due within 1h)"
    }

    /** Phase U2 — all selectable (provider, model) pairs across both built-in
     *  providers and custom endpoints. Wraps [AiApiManager.availableSpecsExpanded]. */
    suspend fun availableModels(): List<AiApiManager.Quad> {
        val specs = runCatching { aiApiManager.get().availableSpecsExpanded() }
            .getOrElse { emptyList() }
        return specs.mapNotNull { spec ->
            when (spec) {
                is ProviderSpec.Builtin -> AiApiManager.Quad(
                    providerKey = spec.provider.name,
                    providerLabel = spec.provider.displayName,
                    model = spec.effectiveModel,
                    kind = "builtin",
                )
                is ProviderSpec.Custom -> AiApiManager.Quad(
                    providerKey = "custom:${spec.endpoint.id}",
                    providerLabel = spec.endpoint.name,
                    model = spec.effectiveModel,
                    kind = "custom",
                )
            }
        }
    }

    fun resolveSpec(provider: String?, model: String?) = aiApiManager.get().resolveSpec(provider, model)
}
