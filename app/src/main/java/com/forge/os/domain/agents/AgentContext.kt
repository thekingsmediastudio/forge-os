package com.forge.os.domain.agents

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Phase 3 fix — context carried across the ReAct loop and tool dispatch.
 * Allows DelegationManager to correctly identify the calling agent's depth
 * without relying on global volatile variables.
 */
data class AgentContext(
    val agentId: String?,
    val depth: Int = 0,
    /** Feature 14 — Path to ephemeral sandbox root (null = use global workspace). */
    val sandboxPath: String? = null
) : AbstractCoroutineContextElement(AgentContext) {
    companion object Key : CoroutineContext.Key<AgentContext>
}
