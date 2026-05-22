package com.forge.os.domain.security

import com.forge.os.domain.agent.AgentTool
import com.forge.os.domain.agent.UserInputBroker
import javax.inject.Inject

/**
 * Tool to request a native biometric (Fingerprint/FaceID) confirmation.
 * 
 * Use this for high-risk actions (file_delete, config_write, large transactions) 
 * when operating in a Low-Trust environment.
 */
class BiometricChallengeTool @Inject constructor(
    private val userInputBroker: UserInputBroker,
    private val configRepository: com.forge.os.domain.config.ConfigRepository
) : AgentTool {
    override val name = "request_biometric_auth"
    override val description = "Forces a native biometric (Fingerprint/FaceID) check on the device. Use this for high-stake operations. Parameters: reason (e.g. 'Confirm deletion of Project X')."

    override suspend fun execute(args: Map<String, String>): String {
        val config = configRepository.get()
        if (!config.sovereignty.enabled || !config.sovereignty.biometricGateEnabled) {
            return "✅ Biometric gate is currently DISABLED in system settings. Proceeding as trusted."
        }

        val reason = args["reason"] ?: "Agent is performing a high-risk operation."
        
        // Use the special route key 'BIOMETRIC' which the UI intercepts 
        // to show a system biometric prompt instead of a text input.
        val response = userInputBroker.awaitResponse(
            question = reason,
            explicitRouteKey = "BIOMETRIC"
        )
        
        return if (response == "SUCCESS") {
            "✅ Biometric identity verified. Proceeding with operation."
        } else {
            "❌ Biometric verification FAILED or cancelled by user. Operation aborted."
        }
    }
}
