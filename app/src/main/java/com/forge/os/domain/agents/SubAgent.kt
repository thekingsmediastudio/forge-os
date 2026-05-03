package com.forge.os.domain.agents

import kotlinx.serialization.Serializable

@Serializable
enum class SubAgentStatus { SPAWNED, RUNNING, COMPLETED, FAILED, CANCELLED, TIMED_OUT }

/**
 * Persistent record of a delegated sub-agent task.
 *
 * One directory per sub-agent on disk:
 *   workspace/agents/<id>/record.json   ← this serialized record
 *   workspace/agents/<id>/transcript.txt ← full streaming transcript
 *   workspace/agents/<id>/result.txt    ← final result (or error)
 */
@Serializable
data class SubAgent(
    val id: String,
    val parentId: String? = null,                 // null = top-level user-spawned
    val goal: String,                             // task description from delegator
    val context: String = "",                     // optional extra context
    val status: SubAgentStatus = SubAgentStatus.SPAWNED,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val result: String? = null,
    val error: String? = null,
    val depth: Int = 0,                           // 0 = user, 1 = delegated, ...
    val toolCallCount: Int = 0,
    val tokenEstimate: Int = 0,
    val tags: List<String> = emptyList(),
    /** Phase 3 — model override for this sub-agent execution. */
    val overrideProvider: String? = null,
    val overrideModel: String? = null,
    /** Feature 14 — Isolated agent with ephemeral workspace. */
    val isGhost: Boolean = false
) {
    val durationMs: Long? get() = finishedAt?.let { it - (startedAt ?: createdAt) }
    val isTerminal: Boolean get() = status in setOf(
        SubAgentStatus.COMPLETED, SubAgentStatus.FAILED,
        SubAgentStatus.CANCELLED, SubAgentStatus.TIMED_OUT
    )
}

/**
 * Outcome of awaiting a delegation. Used by DelegationManager.spawnAndAwait
 * and by the delegate_task tool.
 */
data class DelegationOutcome(
    val agent: SubAgent,
    val transcript: String,
    val success: Boolean
) {
    val summary: String get() = buildString {
        appendLine("[${agent.status}] ${agent.id} — ${agent.goal.take(80)}")
        agent.durationMs?.let { appendLine("Duration: ${it}ms") }
        if (success) {
            appendLine("Result:")
            appendLine((agent.result ?: "").take(2000))
        } else {
            appendLine("Error: ${agent.error ?: "unknown"}")
        }
    }
}

/**
 * Strategy for combining results when multiple sub-agents are spawned together.
 */
enum class AggregationStrategy {
    SEQUENTIAL,      // run one at a time, append outputs
    PARALLEL,        // run simultaneously, collect all
    BEST_OF;         // run all, keep the longest/most-substantive result

    companion object {
        fun fromString(s: String?): AggregationStrategy = when (s?.lowercase()) {
            "parallel" -> PARALLEL
            "best_of", "best-of", "bestof" -> BEST_OF
            else -> SEQUENTIAL
        }
    }
}
