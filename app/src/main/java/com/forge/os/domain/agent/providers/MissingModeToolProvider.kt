package com.forge.os.domain.agent.providers

import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import com.forge.os.domain.missingmode.AutoResponder
import com.forge.os.domain.missingmode.MissingModeManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Missing Mode tools — auto-respond to calls when phone is missing.
 *
 * Tools:
 *   missing_mode_status         — get current status
 *   missing_mode_enable         — enable auto-response
 *   missing_mode_disable        — disable auto-response
 *   missing_mode_add_contact    — add trusted contact
 *   missing_mode_remove_contact — remove trusted contact
 *   missing_mode_set_response   — set response type (sms/tts/both)
 *   missing_mode_test           — send test SMS
 */
@Singleton
class MissingModeToolProvider @Inject constructor(
    private val missingModeManager: MissingModeManager,
    private val autoResponder: AutoResponder,
) : ToolProvider {

    override fun getTools(): List<ToolDefinition> = listOf(
        tool(
            name = "missing_mode_status",
            description = "Get the current Missing Mode status including enabled state, response type, and trusted contacts.",
            params = emptyMap(),
            required = emptyList(),
        ),
        tool(
            name = "missing_mode_enable",
            description = "Enable Missing Mode. Incoming calls from trusted contacts will trigger auto-response.",
            params = emptyMap(),
            required = emptyList(),
        ),
        tool(
            name = "missing_mode_disable",
            description = "Disable Missing Mode. All calls will ring normally.",
            params = emptyMap(),
            required = emptyList(),
        ),
        tool(
            name = "missing_mode_add_contact",
            description = "Add a phone number to trusted contacts. Calls from this number will trigger auto-response when Missing Mode is enabled.",
            params = mapOf("phone_number" to ("string" to "Phone number to add")),
            required = listOf("phone_number"),
        ),
        tool(
            name = "missing_mode_remove_contact",
            description = "Remove a phone number from trusted contacts.",
            params = mapOf("phone_number" to ("string" to "Phone number to remove")),
            required = listOf("phone_number"),
        ),
        tool(
            name = "missing_mode_set_response",
            description = "Set the response type: 'sms' (reject + SMS), 'tts' (answer + voice), or 'both'.",
            params = mapOf("type" to ("string" to "Response type: sms, tts, or both")),
            required = listOf("type"),
        ),
        tool(
            name = "missing_mode_test",
            description = "Send a test auto-reply SMS to a phone number.",
            params = mapOf("phone_number" to ("string" to "Phone number to send test SMS")),
            required = listOf("phone_number"),
        ),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? {
        return when (toolName) {
            "missing_mode_status" -> getStatus()
            "missing_mode_enable" -> enable()
            "missing_mode_disable" -> disable()
            "missing_mode_add_contact" -> addContact(args["phone_number"]?.toString() ?: "")
            "missing_mode_remove_contact" -> removeContact(args["phone_number"]?.toString() ?: "")
            "missing_mode_set_response" -> setResponseType(args["type"]?.toString() ?: "")
            "missing_mode_test" -> testResponse(args["phone_number"]?.toString() ?: "")
            else -> null
        }
    }

    private fun getStatus(): String {
        val state = missingModeManager.state.value
        val sb = StringBuilder()
        sb.appendLine("## Missing Mode Status")
        sb.appendLine()
        sb.appendLine("- **Enabled:** ${if (state.enabled) "Yes" else "No"}")
        sb.appendLine("- **Response Type:** ${state.responseType}")
        sb.appendLine("- **Trusted Contacts:** ${state.trustedContacts.size}")
        if (state.trustedContacts.isNotEmpty()) {
            state.trustedContacts.forEach { sb.appendLine("  - $it") }
        }
        if (state.lastTriggered > 0) {
            val sdf = java.text.SimpleDateFormat("MMM dd HH:mm", java.util.Locale.getDefault())
            sb.appendLine("- **Last Triggered:** ${state.lastCaller} at ${sdf.format(java.util.Date(state.lastTriggered))}")
        }
        return sb.toString()
    }

    private fun enable(): String {
        missingModeManager.setEnabled(true)
        return "Missing Mode enabled. Incoming calls from trusted contacts will trigger auto-response."
    }

    private fun disable(): String {
        missingModeManager.setEnabled(false)
        return "Missing Mode disabled. All calls will ring normally."
    }

    private fun addContact(phoneNumber: String): String {
        if (phoneNumber.isBlank()) return "Error: phone_number is required"
        missingModeManager.addTrustedContact(phoneNumber)
        return "Added $phoneNumber to trusted contacts."
    }

    private fun removeContact(phoneNumber: String): String {
        if (phoneNumber.isBlank()) return "Error: phone_number is required"
        missingModeManager.removeTrustedContact(phoneNumber)
        return "Removed $phoneNumber from trusted contacts."
    }

    private fun setResponseType(type: String): String {
        if (type !in listOf("sms", "tts", "both")) {
            return "Error: Invalid type. Must be 'sms', 'tts', or 'both'."
        }
        missingModeManager.setResponseType(type)
        return "Response type set to '$type'."
    }

    private fun testResponse(phoneNumber: String): String {
        if (phoneNumber.isBlank()) return "Error: phone_number is required"
        val success = missingModeManager.sendAutoReplySms(phoneNumber)
        return if (success) {
            "Test SMS sent to $phoneNumber."
        } else {
            "Error: Failed to send test SMS to $phoneNumber."
        }
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
