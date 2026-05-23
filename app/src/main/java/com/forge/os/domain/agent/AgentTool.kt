package com.forge.os.domain.agent

/**
 * Base interface for agent tools that can be executed by the agent.
 * 
 * Tools implementing this interface provide a standardized way to:
 * - Define tool metadata (name, description)
 * - Execute tool logic with string-based arguments
 * - Return string results
 */
interface AgentTool {
    /**
     * The unique name of the tool (e.g., "request_biometric_auth")
     */
    val name: String
    
    /**
     * Human-readable description of what the tool does and its parameters
     */
    val description: String
    
    /**
     * Execute the tool with the provided arguments
     * 
     * @param args Map of argument names to values
     * @return String result of the tool execution
     */
    suspend fun execute(args: Map<String, String>): String
}
