package com.forge.os.domain.agents

import com.forge.os.data.api.ApiMessage
import com.forge.os.domain.agent.AgentEvent
import com.forge.os.domain.agent.ReActAgent
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.memory.MemoryManager
import com.forge.os.domain.notifications.AgentNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Spawns and supervises sub-agents that run on the same ReActAgent infrastructure.
 *
 * Re-entrancy guard: tracks the active depth via a coroutine-thread-local map
 * (keyed by agent id) so that delegate_task inside a sub-agent can be rejected
 * unless config.delegationRules.allowRecursiveDelegation is true.
 *
 * ReActAgent is injected via Provider to break the circular dependency
 * ToolRegistry → DelegationManager → ReActAgent → ToolRegistry.
 */
@Singleton
class DelegationManager @Inject constructor(
    private val repository: SubAgentRepository,
    private val configRepository: ConfigRepository,
    private val memoryManager: MemoryManager,
    private val reActAgentProvider: Provider<ReActAgent>,
    private val agentNotifier: AgentNotifier,
    private val aiApiManager: com.forge.os.data.api.AiApiManager,
    private val ghostWorkspaceProvider: com.forge.os.domain.workspace.GhostWorkspaceProvider,
    private val backgroundLog: com.forge.os.domain.debug.BackgroundTaskLogManager,
    // Enhanced Integration: Connect with learning systems
    private val reflectionManager: com.forge.os.domain.agent.ReflectionManager,
    private val executionHistoryManager: com.forge.os.domain.agent.ExecutionHistoryManager,
) {
    private val supervisorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** id → running coroutine (so we can cancel it). */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    // ─── Spawn API ───────────────────────────────────────────────────────────

    /**
     * Spawn a sub-agent and immediately await its completion.
     * Suspends until the sub-agent terminates (success, failure, timeout, cancel).
     */
    suspend fun spawnAndAwait(
        goal: String,
        context: String = "",
        parentId: String? = null,
        tags: List<String> = emptyList(),
        overrideProvider: String? = null,
        overrideModel: String? = null,
        /** Phase 3 fix — depth of the CALLER (0 = user, 1+ = sub-agent). */
        callerDepth: Int = 0,
        /** Feature 14 — Isolated agent. */
        isGhost: Boolean = false
    ): DelegationOutcome {
        val rules = configRepository.get().delegationRules
        if (callerDepth >= 1 && !rules.allowRecursiveDelegation) {
            val rejected = SubAgent(
                id = "agent_rejected_${System.currentTimeMillis()}",
                goal = goal, context = context, parentId = parentId,
                status = SubAgentStatus.FAILED,
                error = "Recursive delegation disabled (depth=$callerDepth)",
                depth = callerDepth + 1, tags = tags
            )
            repository.save(rejected)
            return DelegationOutcome(rejected, "", success = false)
        }

        val active = repository.listActive().size
        if (active >= rules.maxSubAgents) {
            val rejected = SubAgent(
                id = "agent_quota_${System.currentTimeMillis()}",
                goal = goal, context = context, parentId = parentId,
                status = SubAgentStatus.FAILED,
                error = "Sub-agent quota reached (${rules.maxSubAgents})",
                depth = callerDepth + 1, tags = tags
            )
            repository.save(rejected)
            return DelegationOutcome(rejected, "", success = false)
        }
        val agent = SubAgent(
            id = "agent_${UUID.randomUUID().toString().take(8)}",
            parentId = parentId,
            goal = goal,
            context = context,
            depth = callerDepth + 1,
            tags = tags,
            overrideProvider = overrideProvider,
            overrideModel = overrideModel,
            isGhost = isGhost
        )
        repository.save(agent)
        agentNotifier.notifyAgentStarted(agent.id, agent.goal)
        Timber.i("DelegationManager: spawned ${agent.id} (depth=${agent.depth}) — $goal")

        val outcome = runAgent(agent, rules.subAgentTimeout)
        agentNotifier.notifyAgentFinished(
            agentId = outcome.agent.id,
            success = outcome.success,
            summary = outcome.agent.result?.take(180) ?: outcome.agent.error.orEmpty(),
            durationMs = outcome.agent.durationMs
        )
        return outcome
    }

    /**
     * Spawn N sub-agents (one per goal) and aggregate using the chosen strategy.
     */
    suspend fun spawnBatch(
        goals: List<String>,
        strategy: AggregationStrategy = AggregationStrategy.SEQUENTIAL,
        sharedContext: String = "",
        parentId: String? = null
    ): List<DelegationOutcome> {
        if (goals.isEmpty()) return emptyList()
        val rules = configRepository.get().delegationRules
        if (goals.size > rules.maxSubAgents) {
            return listOf(DelegationOutcome(
                SubAgent(
                    id = "batch_rejected_${System.currentTimeMillis()}",
                    goal = goals.joinToString(" | ").take(120),
                    status = SubAgentStatus.FAILED,
                    error = "Batch size ${goals.size} exceeds maxSubAgents=${rules.maxSubAgents}"
                ), "", false
            ))
        }

        return when (strategy) {
            AggregationStrategy.SEQUENTIAL ->
                goals.map { spawnAndAwait(it, sharedContext, parentId) }

            AggregationStrategy.PARALLEL, AggregationStrategy.BEST_OF -> {
                withContext(Dispatchers.IO) {
                    goals.map { goal ->
                        async { spawnAndAwait(goal, sharedContext, parentId) }
                    }.awaitAll()
                }.let { results ->
                    if (strategy == AggregationStrategy.BEST_OF) {
                        results.sortedByDescending { (it.agent.result ?: "").length }
                    } else results
                }
            }
        }
    }

    // ─── Inspection / control ────────────────────────────────────────────────

    fun listAll(): List<SubAgent> = repository.listAll()
    fun listActive(): List<SubAgent> = repository.listActive()
    fun get(id: String): SubAgent? = repository.load(id)
    fun transcript(id: String): String = repository.loadTranscript(id)

    fun cancel(id: String): Boolean {
        val job = activeJobs[id] ?: return false
        job.cancel()
        repository.load(id)?.let {
            repository.save(it.copy(
                status = SubAgentStatus.CANCELLED,
                finishedAt = System.currentTimeMillis(),
                error = "cancelled by user"
            ))
        }
        activeJobs.remove(id)
        return true
    }

    fun summary(): String {
        val all = listAll()
        val active = all.count { !it.isTerminal }
        val succeeded = all.count { it.status == SubAgentStatus.COMPLETED }
        val failed = all.count { it.status == SubAgentStatus.FAILED }
        return "🤖 Sub-agents: ${all.size} total ($active active, $succeeded ✓, $failed ✗)"
    }

    /** Helper for spawning an isolated ghost agent. */
    suspend fun spawnGhost(
        goal: String,
        context: String = "",
        parentId: String? = null,
        tags: List<String> = emptyList()
    ): DelegationOutcome = spawnAndAwait(
        goal = goal,
        context = context,
        parentId = parentId,
        tags = tags + "ghost",
        isGhost = true
    )

    // ─── Internals ───────────────────────────────────────────────────────────

    private suspend fun runAgent(agent: SubAgent, timeoutSeconds: Int): DelegationOutcome {
        var record = agent.copy(status = SubAgentStatus.RUNNING, startedAt = System.currentTimeMillis())
        repository.save(record)

        val transcriptBuf = StringBuilder()
        var finalResult: String? = null
        var toolCalls = 0
        var lastError: String? = null

        // Compose the user message for the sub-agent
        val userMessage = if (record.context.isBlank()) record.goal
        else "${record.goal}\n\n--- context from delegator ---\n${record.context}"

        val jobScope = SupervisorJob()
        // Feature 14: Ghost Sandbox provisioning
        val sandbox = if (record.isGhost) ghostWorkspaceProvider.createSandbox() else null
        
        try {
            val outcome = withTimeoutOrNull(timeoutSeconds * 1000L) {
                supervisorScope.launch(jobScope + AgentContext(record.id, record.depth, sandbox?.absolutePath)) {
                    try {
                        val spec = aiApiManager.resolveSpec(record.overrideProvider, record.overrideModel)
                        reActAgentProvider.get().run(
                            userMessage = userMessage,
                            history = emptyList<ApiMessage>(),
                            spec = spec,
                            agentId = record.id,
                            depth = record.depth
                        ).collect { ev ->
                            when (ev) {
                                is AgentEvent.Thinking   -> { /* streamed; skip to keep transcript readable */ }
                                is AgentEvent.ToolCall   -> {
                                    toolCalls++
                                    transcriptBuf.appendLine("→ tool ${ev.name}(${ev.args.take(160)})")
                                    repository.appendTranscript(record.id, "→ tool ${ev.name}")
                                }
                                is AgentEvent.ToolResult -> {
                                    transcriptBuf.appendLine("← ${ev.name}: ${ev.result.take(200)}")
                                    repository.appendTranscript(record.id, "← ${ev.name}")
                                }
                                is AgentEvent.Response   -> {
                                    finalResult = ev.text
                                    transcriptBuf.appendLine("✓ ${ev.text.take(200)}")
                                    repository.appendTranscript(record.id, "✓ response (${ev.text.length} chars)")
                                }
                                is AgentEvent.Error      -> {
                                    lastError = ev.message
                                    transcriptBuf.appendLine("✗ error: ${ev.message}")
                                    repository.appendTranscript(record.id, "✗ ${ev.message}")
                                }
                                is AgentEvent.CostApprovalRequired -> { /* sub-agents auto-approve within budget rules */ }
                                is AgentEvent.Done       -> { /* loop exit */ }
                            }
                        }
                    } catch (e: Exception) {
                        lastError = e.message ?: e::class.java.simpleName
                    }
                }.also { activeJobs[record.id] = it }.join()
                true
            }

            val now = System.currentTimeMillis()
            record = if (outcome == null) {
                record.copy(
                    status = SubAgentStatus.TIMED_OUT,
                    finishedAt = now,
                    toolCallCount = toolCalls,
                    error = "timeout after ${timeoutSeconds}s"
                )
            } else if (lastError != null && finalResult == null) {
                record.copy(
                    status = SubAgentStatus.FAILED,
                    finishedAt = now,
                    toolCallCount = toolCalls,
                    error = lastError,
                    result = transcriptBuf.toString().take(2000)
                )
            } else {
                record.copy(
                    status = SubAgentStatus.COMPLETED,
                    finishedAt = now,
                    toolCallCount = toolCalls,
                    result = finalResult ?: transcriptBuf.toString().take(2000)
                )
            }
        } finally {
            activeJobs.remove(record.id)
            sandbox?.let { ghostWorkspaceProvider.destroySandbox(it) }
        }

        repository.save(record)
        
        // Enhanced Integration: Record delegation patterns for learning
        try {
            reflectionManager.recordPattern(
                pattern = "Sub-agent delegation: ${record.goal.take(50)}",
                description = "Delegated task '${record.goal}' to sub-agent at depth ${record.depth}",
                applicableTo = listOf("delegation", "sub_agent", "task_breakdown"),
                tags = listOf("delegation_pattern", "sub_agent_usage", "task_management") + record.tags
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to record delegation pattern")
        }
        
        try {
            memoryManager.logEvent(
                role = "delegation",
                content = "Sub-agent ${record.id} ${record.status} — '${record.goal.take(80)}' (${record.toolCallCount} tools)",
                tags = listOf("agent", record.id) + record.tags
            )
        } catch (_: Exception) {}

        runCatching {
            backgroundLog.addLog(
                com.forge.os.domain.debug.BackgroundTaskLog(
                    id = "agent_${record.id}",
                    source = com.forge.os.domain.debug.TaskSource.DELEGATION,
                    label = record.goal.take(50),
                    success = record.status == com.forge.os.domain.agents.SubAgentStatus.COMPLETED,
                    output = record.result ?: record.error ?: "Agent finished with status ${record.status}",
                    error = record.error,
                    durationMs = record.durationMs ?: 0L,
                    timestamp = record.finishedAt ?: System.currentTimeMillis()
                )
            )
        }

        // Enhanced Integration: Record delegation execution for learning
        try {
            val steps = listOf(
                com.forge.os.domain.agent.ExecutionStep(
                    stepNumber = 1,
                    action = "Sub-agent delegation",
                    tool = "delegate_task",
                    args = record.goal,
                    result = record.result ?: record.error ?: "Sub-agent execution completed",
                    duration = record.durationMs ?: 0L,
                    success = record.status == SubAgentStatus.COMPLETED
                )
            )
            
            reflectionManager.recordExecution(
                taskId = "delegation_${record.id}",
                goal = "Delegate task: ${record.goal}",
                steps = steps,
                success = record.status == SubAgentStatus.COMPLETED,
                outcome = record.result ?: record.error ?: "Delegation completed with status ${record.status}",
                tags = listOf("delegation", "sub_agent", "task_execution") + record.tags
            )
            
            // Record delegation success/failure patterns
            if (record.status == SubAgentStatus.COMPLETED) {
                reflectionManager.recordPattern(
                    pattern = "Successful delegation pattern",
                    description = "Successfully delegated '${record.goal.take(50)}' with ${record.toolCallCount} tool calls in ${record.durationMs}ms",
                    applicableTo = listOf("delegation", "task_breakdown", "sub_agent_success"),
                    tags = listOf("delegation_success", "efficiency_pattern", "task_management")
                )
            } else if (record.status == SubAgentStatus.FAILED) {
                reflectionManager.recordFailureAndRecovery(
                    taskId = "delegation_${record.id}",
                    failureReason = "Sub-agent delegation failed: ${record.error}",
                    recoveryStrategy = "Consider breaking down the task further, providing more context, or handling the task directly",
                    tags = listOf("delegation_failure", "sub_agent_error", "task_management")
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to record delegation execution in ReflectionManager")
        }

        return DelegationOutcome(
            agent = record,
            transcript = transcriptBuf.toString(),
            success = record.status == SubAgentStatus.COMPLETED
        )
    }
}
