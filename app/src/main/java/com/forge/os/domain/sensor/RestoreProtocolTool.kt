package com.forge.os.domain.sensor

import com.forge.os.domain.agent.AgentTool
import com.forge.os.data.sandbox.SandboxManager
import javax.inject.Inject

class RestoreProtocolTool @Inject constructor(
    private val sandboxManager: SandboxManager
) : AgentTool {
    override val name = "restore_protocol"
    override val description = "Deactivates Ghost Mode and restores the original workspace. Requires the 'master_key' parameter to authenticate restoration."

    override suspend fun execute(args: Map<String, String>): String {
        val key = args["master_key"] ?: return "Error: 'master_key' required for protocol restoration."
        
        return if (sandboxManager.restoreOriginalWorkspace(key)) {
            "✅ PROTOCOL RESTORED. Original workspace is now active. Reality calibration returned to normal."
        } else {
            "❌ RESTORATION DENIED. Invalid master key. System remains in Ghost Mode."
        }
    }
}
