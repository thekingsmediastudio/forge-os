package com.forge.os.domain.alarms

import kotlinx.serialization.Serializable

@Serializable
data class AlarmItem(
    val id: String,
    val label: String,
    val triggerAt: Long,
    val repeatIntervalMs: Long = 0L,
    val payload: String = "",
    val action: AlarmAction = AlarmAction.NOTIFY,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    /** Phase 3 — model override for PROMPT_AGENT / RUN_TOOL actions. */
    val overrideProvider: String? = null,
    val overrideModel: String? = null
)

@Serializable
enum class AlarmAction {
    NOTIFY,        // fire a notification
    RUN_TOOL,      // dispatch to ToolRegistry (payload = tool_name|argsJson)
    RUN_PYTHON,    // run payload as Python in sandbox
    PROMPT_AGENT,  // queue payload as a prompt for the agent
}
