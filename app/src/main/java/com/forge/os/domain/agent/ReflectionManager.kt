package com.forge.os.domain.agent

import com.forge.os.domain.memory.MemoryManager
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages agent reflection and learning from past interactions.
 * 
 * Stores:
 * - Execution history (steps taken, outcomes)
 * - Learned patterns (what works, what doesn't)
 * - Context from previous sessions
 * - Failure analysis and recovery strategies
 */
@Singleton
class ReflectionManager @Inject constructor(
    private val memoryManager: MemoryManager,
) {
    private val json = Json { prettyPrint = true }

    /**
     * Store a complete execution trace for learning.
     * Called at the end of each agent task.
     */
    suspend fun recordExecution(
        taskId: String,
        goal: String,
        steps: List<ExecutionStep>,
        success: Boolean,
        outcome: String,
        tags: List<String> = emptyList()
    ) {
        val trace = ExecutionTrace(
            taskId = taskId,
            goal = goal,
            steps = steps,
            success = success,
            outcome = outcome,
            timestamp = System.currentTimeMillis(),
            tags = tags
        )

        val key = "execution_${taskId}_${System.currentTimeMillis()}"
        val content = json.encodeToString(trace)
        
        memoryManager.store(
            key = key,
            content = content,
            tags = listOf("execution", "reflection") + trace.tags
        )

        Timber.i("Recorded execution trace: $taskId (success=$success)")
    }

    /**
     * Retrieve similar past executions to learn from.
     */
    suspend fun findSimilarExecutions(
        goal: String,
        limit: Int = 5
    ): List<ExecutionTrace> {
        val results = memoryManager.recall(goal, k = limit)
        return results
            .filter { it.tags.contains("execution") }
            .mapNotNull { entry ->
                runCatching {
                    json.decodeFromString<ExecutionTrace>(entry.content)
                }.getOrNull()
            }
    }

    /**
     * Get context from previous sessions.
     * Helps agent understand what was done before.
     */
    suspend fun getPreviousSessionContext(): String {
        val results = memoryManager.recall("session context", k = 3)
        if (results.isEmpty()) return "No previous session context found."

        return buildString {
            appendLine("📚 Previous Session Context:")
            results.forEach { entry ->
                appendLine("• ${entry.key}: ${entry.content.take(200)}")
            }
        }
    }

    /**
     * Store a failure and recovery strategy for future reference.
     */
    suspend fun recordFailureAndRecovery(
        taskId: String,
        failureReason: String,
        recoveryStrategy: String,
        tags: List<String> = emptyList()
    ) {
        val recovery = FailureRecovery(
            taskId = taskId,
            failureReason = failureReason,
            recoveryStrategy = recoveryStrategy,
            timestamp = System.currentTimeMillis()
        )

        val key = "recovery_${taskId}_${System.currentTimeMillis()}"
        val content = json.encodeToString(recovery)

        memoryManager.store(
            key = key,
            content = content,
            tags = listOf("failure", "recovery") + tags  // Use function parameter directly
        )

        Timber.i("Recorded failure recovery: $taskId")
    }

    /**
     * Get recovery strategies for similar failures.
     */
    suspend fun getRecoveryStrategies(failureType: String): List<FailureRecovery> {
        val results = memoryManager.recall(failureType, k = 5)
        return results
            .filter { it.tags.contains("recovery") }
            .mapNotNull { entry ->
                runCatching {
                    json.decodeFromString<FailureRecovery>(entry.content)
                }.getOrNull()
            }
    }

    /**
     * Store learned patterns (e.g., "Python projects need pytest", "Web projects need npm")
     */
    suspend fun recordPattern(
        pattern: String,
        description: String,
        applicableTo: List<String>,
        tags: List<String> = emptyList()
    ) {
        val learned = LearnedPattern(
            pattern = pattern,
            description = description,
            applicableTo = applicableTo,
            timestamp = System.currentTimeMillis(),
            useCount = 0
        )

        val key = "pattern_${pattern.replace(" ", "_")}_${System.currentTimeMillis()}"
        val content = json.encodeToString(learned)

        memoryManager.store(
            key = key,
            content = content,
            tags = listOf("pattern", "learned") + tags  // Use function parameter directly
        )

        Timber.i("Recorded learned pattern: $pattern")
    }

    /**
     * Get all learned patterns relevant to current task.
     */
    suspend fun getRelevantPatterns(taskType: String): List<LearnedPattern> {
        val results = memoryManager.recall(taskType, k = 10)
        return results
            .filter { it.tags.contains("pattern") }
            .mapNotNull { entry ->
                runCatching {
                    json.decodeFromString<LearnedPattern>(entry.content)
                }.getOrNull()
            }
    }

    /**
     * Create a reflection prompt for the agent to use.
     */
    suspend fun createReflectionPrompt(
        currentGoal: String,
        previousSteps: List<String> = emptyList()
    ): String {
        val similar = findSimilarExecutions(currentGoal, limit = 3)
        val patterns = getRelevantPatterns(currentGoal)
        val sessionContext = getPreviousSessionContext()

        return buildString {
            appendLine("🧠 REFLECTION & CONTEXT")
            appendLine()
            
            if (previousSteps.isNotEmpty()) {
                appendLine("📋 Previous Steps in This Session:")
                previousSteps.forEach { step ->
                    appendLine("  • $step")
                }
                appendLine()
            }

            if (similar.isNotEmpty()) {
                appendLine("📚 Similar Past Executions:")
                similar.forEach { trace ->
                    appendLine("  • Goal: ${trace.goal}")
                    appendLine("    Success: ${trace.success}")
                    appendLine("    Outcome: ${trace.outcome.take(100)}")
                }
                appendLine()
            }

            if (patterns.isNotEmpty()) {
                appendLine("💡 Learned Patterns:")
                patterns.forEach { pattern ->
                    appendLine("  • ${pattern.pattern}: ${pattern.description}")
                }
                appendLine()
            }

            appendLine(sessionContext)
        }
    }
}

@Serializable
data class ExecutionTrace(
    val taskId: String,
    val goal: String,
    val steps: List<ExecutionStep>,
    val success: Boolean,
    val outcome: String,
    val timestamp: Long,
    val tags: List<String> = emptyList()
)

@Serializable
data class ExecutionStep(
    val stepNumber: Int,
    val action: String,
    val tool: String,
    val args: String,
    val result: String,
    val duration: Long,
    val success: Boolean
)

@Serializable
data class FailureRecovery(
    val taskId: String,
    val failureReason: String,
    val recoveryStrategy: String,
    val timestamp: Long
)

@Serializable
data class LearnedPattern(
    val pattern: String,
    val description: String,
    val applicableTo: List<String>,
    val timestamp: Long,
    val useCount: Int = 0
)
