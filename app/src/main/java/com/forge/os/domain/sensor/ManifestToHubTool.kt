package com.forge.os.domain.sensor

import com.forge.os.domain.agent.AgentTool
import com.forge.os.domain.notifications.AgentNotificationBuilder
import com.forge.os.domain.agent.UserInputBroker
import javax.inject.Inject

/**
 * Manifests the background Sovereign WebView into the visible Hub.
 * 
 * Use this when headless automation hits a wall (CAPTCHA, MFA, complex choice)
 * and requires human intervention.
 */
class ManifestToHubTool @Inject constructor(
    private val headedBrowserManager: HeadedBrowserManager,
    private val agentNotifier: AgentNotificationBuilder,
    private val userInputBroker: UserInputBroker
) : AgentTool {
    override val name = "manifest_to_hub"
    override val description = "Manifests the secret background browser into the visible Hub for human intervention. Use this when you hit a CAPTCHA, 2FA, or a complex decision. Parameters: reason (e.g. 'Solve this CAPTCHA')."

    override suspend fun execute(args: Map<String, String>): String {
        val reason = args["reason"] ?: "Agent requires intervention."
        
        headedBrowserManager.setAgentActive(true, "INTERVENTION REQUIRED: $reason")
        
        // Post a high-priority notification to pull the user in
        agentNotifier.postWithActions(
            title = "🛡️ Browser Protocol Manifested",
            body = "The Agent is stuck: $reason. Tap to intervene.",
            channelId = "forge_confirmations",
            actions = emptyList(),
            navRoute = "browser" // Route directly to the browser hub
        )

        // Block the agent until the user signals completion
        val response = userInputBroker.awaitResponse(
            "I have manifested the browser so you can solve this: $reason\nReply 'DONE' when you want me to cloak and resume background work.",
            "BROWSER_INTERVENTION"
        )

        headedBrowserManager.setAgentActive(false)
        return "✅ Intervention complete. User responded: $response. Cloaking and resuming background task."
    }
}
