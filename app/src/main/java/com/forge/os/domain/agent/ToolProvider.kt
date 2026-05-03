package com.forge.os.domain.agent

import com.forge.os.data.api.ToolDefinition

/**
 * Interface for modular tool groups. Classes implementing this can be 
 * registered with the [ToolRegistry] to provide a set of tools to the agent.
 */
interface ToolProvider {
    /** Returns the list of tool definitions provided by this category. */
    fun getTools(): List<ToolDefinition>
    
    /** 
     * Dispatches a tool call to this provider. 
     * Returns the output string, or null if the tool name is not handled by this provider.
     */
    suspend fun dispatch(toolName: String, args: Map<String, Any>): String?
}
