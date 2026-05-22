package com.forge.os.domain.sensor

import com.forge.os.domain.agent.AgentTool
import timber.log.Timber
import javax.inject.Inject

/**
 * Tool to visually highlight an element for the user in the Manifested Hub.
 */
class HeadedBrowserPingTool @Inject constructor(
    private val headedBrowserManager: HeadedBrowserManager
) : AgentTool {
    override val name = "headed_browser_ping"
    override val description = "Visually pulses/highlights a specific element on the screen for the user. Use this to point things out during intervention. Params: selector (CSS selector)."

    override suspend fun execute(args: Map<String, String>): String {
        val selector = args["selector"] ?: return "Error: Missing selector"
        
        // Inject a CSS animation and pulse the element
        val script = """
            (function() {
                var el = document.querySelector('${selector.replace("'", "\\'")}');
                if (el) {
                    el.style.outline = '4px solid #FF8C00';
                    el.style.transition = 'outline 0.5s ease-in-out';
                    var count = 0;
                    var interval = setInterval(function() {
                        el.style.outlineColor = (count % 2 === 0) ? 'transparent' : '#FF8C00';
                        count++;
                        if (count > 6) {
                            clearInterval(interval);
                            el.style.outline = '';
                        }
                    }, 500);
                    return 'Success: Element pinged.';
                }
                return 'Error: Element not found.';
            })()
        """.trimIndent()
        
        val result = headedBrowserManager.evalJs(script)
        return result ?: "Error: Hub not active or script failed."
    }
}

/**
 * Tool to hide the manifest browser back to the background shadows.
 */
class HeadedBrowserCloakTool @Inject constructor(
    private val headedBrowserManager: HeadedBrowserManager,
    private val provisioner: com.forge.os.data.web.SovereigntyProvisioner
) : AgentTool {
    override val name = "headed_browser_cloak"
    override val description = "Hides the interactive browser hub and returns the agent to headless background mode. Use this when the user intervention is complete."

    override suspend fun execute(args: Map<String, String>): String {
        headedBrowserManager.setAgentActive(false)
        provisioner.undockFromUI()
        return "✅ Protocol: CLOAKED. Moving session back to background shadows."
    }
}
