package com.forge.os.domain.agent

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages agent reflection and learning from past interactions.
 *
 * ## What gets stored
 * Only meaningful, user-visible signals:
 * - Task execution traces (goal, success, outcome) — one entry per taskId
 * - Failure + recovery strategies — one entry per taskId
 * - Learned behavioural patterns (e.g. "user prefers Python projects") — deduplicated by name
 *
 * ## What does NOT get stored
 * Internal plumbing events (memory reads/writes, heartbeat ticks, notification
 * sends, etc.) — those were the source of 100+ irrelevant entries per message.
 *
 * ## Storage
 * All data goes to [ReflectionStore] (workspace/memory/reflection/patterns.json),
 * completely isolated from the user's LongtermMemory fact space. Patterns are
 * deduplicated: recording the same pattern name twice increments useCount
 * instead of creating a new entry.
 */
@Singleton
class ReflectionManager @Inject constructor(
    private val store: ReflectionStore,
) {

    // ── Execution traces ──────────────────────────────────────────────────────

    /**
     * Record a completed task execution. Deduplicated by [taskId].
     * Call this at the end of a ReAct agent loop.
     */
    suspend fun recordExecution(
        taskId: String,
        goal: String,
        steps: List<ExecutionStep>,
        success: Boolean,
        outcome: String,
        tags: List<String> = emptyList(),
    ) {
        store.upsertExecution(
            taskId  = taskId,
            goal    = goal,
            success = success,
            outcome = outcome,
            tags    = tags,
        )
        Timber.i("ReflectionManager: recorded execution $taskId (success=$success)")
    }

    /**
     * Retrieve execution summaries similar to [goal].
     */
    suspend fun findSimilarExecutions(goal: String, limit: Int = 5): List<StoredPattern> {
        return store.getExecutions(limit).filter {
            it.description.lowercase().contains(goal.lowercase().take(30))
        }
    }

    // ── Failure recovery ──────────────────────────────────────────────────────

    /**
     * Record a failure and the strategy used to recover. Deduplicated by [taskId].
     */
    suspend fun recordFailureAndRecovery(
        taskId: String,
        failureReason: String,
        recoveryStrategy: String,
        tags: List<String> = emptyList(),
    ) {
        store.upsertRecovery(
            taskId           = taskId,
            failureReason    = failureReason,
            recoveryStrategy = recoveryStrategy,
        )
        Timber.i("ReflectionManager: recorded failure recovery for $taskId")
    }

    /**
     * Get recovery strategies relevant to [failureType].
     */
    suspend fun getRecoveryStrategies(failureType: String): List<StoredPattern> {
        return store.getRecoveries(failureType)
    }

    // ── Behavioural patterns ──────────────────────────────────────────────────

    /**
     * Record a learned behavioural pattern.
     *
     * Only call this for **meaningful, user-visible signals** — things that
     * would genuinely help the agent behave better next time. Examples:
     *   - "User prefers Python for scripting tasks"
     *   - "Code review: project_x (Score: 87)"
     *   - "Test generation: python project"
     *
     * Do NOT call this for internal plumbing events (memory reads/writes,
     * heartbeat ticks, notification sends). Those are noise.
     */
    suspend fun recordPattern(
        pattern: String,
        description: String,
        applicableTo: List<String> = emptyList(),
        tags: List<String> = emptyList(),
    ) {
        store.upsert(
            name        = pattern,
            description = description,
            applicableTo = applicableTo,
            tags        = tags,
        )
        Timber.i("ReflectionManager: recorded pattern '$pattern'")
    }

    /**
     * Get patterns relevant to [taskType].
     */
    suspend fun getRelevantPatterns(taskType: String): List<StoredPattern> {
        return store.getRelevant(taskType)
    }

    // ── Context building ──────────────────────────────────────────────────────

    /**
     * Build a reflection prompt to prepend to the agent's system context.
     * Only includes high-signal entries (useCount > 1 or tagged "execution").
     */
    suspend fun createReflectionPrompt(
        currentGoal: String,
        previousSteps: List<String> = emptyList(),
    ): String {
        val similar  = findSimilarExecutions(currentGoal, limit = 3)
        val patterns = getRelevantPatterns(currentGoal)
            .filter { it.useCount > 1 }   // only patterns seen more than once
            .take(5)

        if (similar.isEmpty() && patterns.isEmpty() && previousSteps.isEmpty()) return ""

        return buildString {
            appendLine("🧠 REFLECTION & CONTEXT")
            appendLine()

            if (previousSteps.isNotEmpty()) {
                appendLine("📋 Previous Steps in This Session:")
                previousSteps.forEach { appendLine("  • $it") }
                appendLine()
            }

            if (similar.isNotEmpty()) {
                appendLine("📚 Similar Past Executions:")
                similar.forEach { p ->
                    appendLine("  • ${p.description.take(120)}")
                }
                appendLine()
            }

            if (patterns.isNotEmpty()) {
                appendLine("💡 Learned Patterns:")
                patterns.forEach { p ->
                    appendLine("  • ${p.name}: ${p.description.take(80)} (seen ${p.useCount}×)")
                }
                appendLine()
            }
        }
    }

    /**
     * Get context from previous sessions (top 3 most-used patterns).
     */
    suspend fun getPreviousSessionContext(): String {
        val top = store.getAll().take(3)
        if (top.isEmpty()) return "No previous session context found."
        return buildString {
            appendLine("📚 Previous Session Context:")
            top.forEach { p -> appendLine("• ${p.name}: ${p.description.take(150)}") }
        }
    }

    /** Human-readable summary of what's been learned. */
    fun summary(): String = store.summary()
}

// ── Data classes (kept for call-site compatibility) ───────────────────────────

data class ExecutionStep(
    val stepNumber: Int,
    val action: String,
    val tool: String,
    val args: String,
    val result: String,
    val duration: Long,
    val success: Boolean,
)

data class LearnedPattern(
    val pattern: String,
    val description: String,
    val applicableTo: List<String>,
    val timestamp: Long,
    val useCount: Int = 0,
)
