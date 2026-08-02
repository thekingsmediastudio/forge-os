package com.forge.os.domain.agent.providers

import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import com.forge.os.security.antitheft.AntiTheftManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Anti-Theft tools — "Grab & Run" protection.
 *
 * Tools:
 *   antitheft_status         — get current status
 *   antitheft_enable         — enable protection
 *   antitheft_disable        — disable protection
 *   antitheft_lock           — lock device now
 *   antitheft_locate         — get current location
 *   antitheft_alert          — send alert to trusted contacts
 *   antitheft_add_contact    — add trusted contact
 *   antitheft_remove_contact — remove trusted contact
 *   antitheft_trigger        — manually trigger theft mode
 *   antitheft_clear          — clear triggered state
 */
@Singleton
class AntiTheftToolProvider @Inject constructor(
    private val antiTheftManager: AntiTheftManager,
) : ToolProvider {

    override fun getTools(): List<ToolDefinition> = listOf(
        tool(
            name = "antitheft_status",
            description = "Get the current anti-theft status including enabled state, triggered state, and trusted contacts.",
            params = emptyMap(),
            required = emptyList(),
        ),
        tool(
            name = "antitheft_enable",
            description = "Enable anti-theft protection. Requires device admin to be activated first.",
            params = emptyMap(),
            required = emptyList(),
        ),
        tool(
            name = "antitheft_disable",
            description = "Disable anti-theft protection.",
            params = emptyMap(),
            required = emptyList(),
        ),
        tool(
            name = "antitheft_lock",
            description = "Lock the device immediately. Requires device admin.",
            params = emptyMap(),
            required = emptyList(),
        ),
        tool(
            name = "antitheft_locate",
            description = "Get the current device location and return a Google Maps link.",
            params = emptyMap(),
            required = emptyList(),
        ),
        tool(
            name = "antitheft_alert",
            description = "Send theft alert to all trusted contacts with current location.",
            params = emptyMap(),
            required = emptyList(),
        ),
        tool(
            name = "antitheft_add_contact",
            description = "Add a phone number to trusted contacts for anti-theft alerts.",
            params = mapOf("phone_number" to ("string" to "Phone number to add")),
            required = listOf("phone_number"),
        ),
        tool(
            name = "antitheft_remove_contact",
            description = "Remove a phone number from trusted contacts.",
            params = mapOf("phone_number" to ("string" to "Phone number to remove")),
            required = listOf("phone_number"),
        ),
        tool(
            name = "antitheft_trigger",
            description = "Manually trigger theft mode. Locks device, gets location, and sends alerts.",
            params = mapOf("reason" to ("string" to "Reason for triggering (optional)")),
            required = emptyList(),
        ),
        tool(
            name = "antitheft_clear",
            description = "Clear the triggered state after recovering the device.",
            params = emptyMap(),
            required = emptyList(),
        ),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? {
        return when (toolName) {
            "antitheft_status" -> getStatus()
            "antitheft_enable" -> enable()
            "antitheft_disable" -> disable()
            "antitheft_lock" -> lock()
            "antitheft_locate" -> locate()
            "antitheft_alert" -> alert()
            "antitheft_add_contact" -> addContact(args["phone_number"]?.toString() ?: "")
            "antitheft_remove_contact" -> removeContact(args["phone_number"]?.toString() ?: "")
            "antitheft_trigger" -> trigger(args["reason"]?.toString() ?: "Manual trigger")
            "antitheft_clear" -> clear()
            else -> null
        }
    }

    private fun getStatus(): String {
        val state = antiTheftManager.state.value
        val sb = StringBuilder()
        sb.appendLine("## Anti-Theft Status")
        sb.appendLine()
        sb.appendLine("- **Enabled:** ${if (state.enabled) "Yes" else "No"}")
        sb.appendLine("- **Triggered:** ${if (state.triggered) "⚠️ Yes" else "No"}")
        sb.appendLine("- **Device Admin:** ${if (state.deviceAdminActive) "Active" else "Not activated"}")
        sb.appendLine("- **Trusted Contacts:** ${state.trustedContacts.size}")
        if (state.trustedContacts.isNotEmpty()) {
            state.trustedContacts.forEach { sb.appendLine("  - $it") }
        }
        if (state.lastLocationTime > 0) {
            val sdf = java.text.SimpleDateFormat("MMM dd HH:mm", java.util.Locale.getDefault())
            sb.appendLine("- **Last Location:** ${state.lastLatitude}, ${state.lastLongitude}")
            sb.appendLine("  - Time: ${sdf.format(java.util.Date(state.lastLocationTime))}")
        }
        if (state.triggeredTime > 0) {
            val sdf = java.text.SimpleDateFormat("MMM dd HH:mm", java.util.Locale.getDefault())
            sb.appendLine("- **Triggered At:** ${sdf.format(java.util.Date(state.triggeredTime))}")
        }
        return sb.toString()
    }

    private fun enable(): String {
        val state = antiTheftManager.state.value
        if (!state.deviceAdminActive) {
            return "Error: Device admin not activated. Please go to Anti-Theft settings and activate device admin first."
        }
        antiTheftManager.setEnabled(true)
        return "Anti-theft protection enabled."
    }

    private fun disable(): String {
        antiTheftManager.setEnabled(false)
        return "Anti-theft protection disabled."
    }

    private fun lock(): String {
        val success = antiTheftManager.lockDevice()
        return if (success) "Device locked." else "Error: Failed to lock device. Is device admin activated?"
    }

    private fun locate(): String {
        val loc = antiTheftManager.updateLocation()
        return if (loc != null) {
            "Current location: https://maps.google.com/?q=${loc.first},${loc.second}"
        } else {
            "Error: Location unavailable. Check location permissions."
        }
    }

    private fun alert(): String {
        val state = antiTheftManager.state.value
        if (state.trustedContacts.isEmpty()) {
            return "Error: No trusted contacts configured. Add contacts first."
        }
        antiTheftManager.sendAlerts()
        return "Alert sent to ${state.trustedContacts.size} trusted contacts."
    }

    private fun addContact(phoneNumber: String): String {
        if (phoneNumber.isBlank()) return "Error: phone_number is required"
        antiTheftManager.addTrustedContact(phoneNumber)
        return "Added $phoneNumber to trusted contacts."
    }

    private fun removeContact(phoneNumber: String): String {
        if (phoneNumber.isBlank()) return "Error: phone_number is required"
        antiTheftManager.removeTrustedContact(phoneNumber)
        return "Removed $phoneNumber from trusted contacts."
    }

    private fun trigger(reason: String): String {
        val state = antiTheftManager.state.value
        if (!state.enabled) {
            return "Error: Anti-theft is not enabled. Enable it first."
        }
        antiTheftManager.triggerTheft(reason)
        return "Theft mode triggered. Device locked, location captured, alerts sent."
    }

    private fun clear(): String {
        antiTheftManager.clearTriggered()
        return "Theft trigger cleared."
    }

    private fun tool(
        name: String,
        description: String,
        params: Map<String, Pair<String, String>>,
        required: List<String>,
    ) = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = FunctionParameters(
                properties = params.mapValues { (_, v) -> ParameterProperty(v.first, v.second) },
                required = required,
            ),
        ),
    )
}
