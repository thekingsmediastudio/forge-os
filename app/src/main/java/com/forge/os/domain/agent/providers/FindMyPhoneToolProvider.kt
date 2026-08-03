package com.forge.os.domain.agent.providers

import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import com.forge.os.domain.findmyphone.FindMyPhoneManager
import com.forge.os.domain.findmyphone.FindMyPhoneResponder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FindMyPhoneToolProvider @Inject constructor(
    private val findMyPhoneManager: FindMyPhoneManager,
    private val findMyPhoneResponder: FindMyPhoneResponder,
) : ToolProvider {

    override fun getTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = FunctionDefinition(
                name = "find_my_phone_status",
                description = "Get the current Find My Phone status and settings",
                parameters = FunctionParameters(
                    properties = emptyMap(),
                    required = emptyList()
                )
            )
        ),
        ToolDefinition(
            function = FunctionDefinition(
                name = "find_my_phone_enable",
                description = "Enable Find My Phone mode",
                parameters = FunctionParameters(
                    properties = emptyMap(),
                    required = emptyList()
                )
            )
        ),
        ToolDefinition(
            function = FunctionDefinition(
                name = "find_my_phone_disable",
                description = "Disable Find My Phone mode",
                parameters = FunctionParameters(
                    properties = emptyMap(),
                    required = emptyList()
                )
            )
        ),
        ToolDefinition(
            function = FunctionDefinition(
                name = "find_my_phone_set_response",
                description = "Set the response type (ring_loud, speak_location, flash_led, all)",
                parameters = FunctionParameters(
                    properties = mapOf(
                        "type" to ParameterProperty(type = "string", description = "Response type")
                    ),
                    required = listOf("type")
                )
            )
        ),
        ToolDefinition(
            function = FunctionDefinition(
                name = "find_my_phone_set_message",
                description = "Set the TTS message to speak",
                parameters = FunctionParameters(
                    properties = mapOf(
                        "message" to ParameterProperty(type = "string", description = "TTS message")
                    ),
                    required = listOf("message")
                )
            )
        ),
        ToolDefinition(
            function = FunctionDefinition(
                name = "find_my_phone_set_duration",
                description = "Set the response duration in seconds (10-60)",
                parameters = FunctionParameters(
                    properties = mapOf(
                        "seconds" to ParameterProperty(type = "number", description = "Duration in seconds")
                    ),
                    required = listOf("seconds")
                )
            )
        ),
        ToolDefinition(
            function = FunctionDefinition(
                name = "find_my_phone_test",
                description = "Test the Find My Phone response",
                parameters = FunctionParameters(
                    properties = emptyMap(),
                    required = emptyList()
                )
            )
        ),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? {
        return when (toolName) {
            "find_my_phone_status" -> {
                val state = findMyPhoneManager.state.value
                buildString {
                    appendLine("Find My Phone Status:")
                    appendLine("  Enabled: ${state.enabled}")
                    appendLine("  Response Type: ${state.responseType}")
                    appendLine("  Duration: ${state.durationSeconds} seconds")
                    if (state.lastTriggered > 0) {
                        appendLine("  Last Triggered: ${state.lastCaller} at ${state.lastTriggered}")
                    }
                }
            }
            "find_my_phone_enable" -> {
                findMyPhoneManager.setEnabled(true)
                "✅ Find My Phone enabled"
            }
            "find_my_phone_disable" -> {
                findMyPhoneManager.setEnabled(false)
                "✅ Find My Phone disabled"
            }
            "find_my_phone_set_response" -> {
                val type = args["type"]?.toString() ?: return "❌ type required"
                findMyPhoneManager.setResponseType(type)
                "✅ Response type set to $type"
            }
            "find_my_phone_set_message" -> {
                val message = args["message"]?.toString() ?: return "❌ message required"
                findMyPhoneManager.setTtsMessage(message)
                "✅ TTS message updated"
            }
            "find_my_phone_set_duration" -> {
                val seconds = (args["seconds"] as? Number)?.toInt() ?: return "❌ seconds required"
                findMyPhoneManager.setDurationSeconds(seconds)
                "✅ Duration set to $seconds seconds"
            }
            "find_my_phone_test" -> {
                findMyPhoneResponder.triggerManualResponse()
                "✅ Find My Phone test triggered"
            }
            else -> null
        }
    }
}
