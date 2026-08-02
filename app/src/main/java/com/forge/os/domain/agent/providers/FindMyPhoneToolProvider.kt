package com.forge.os.domain.agent.providers

import com.forge.os.domain.agent.Tool
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

    override fun getTools(): List<Tool> = listOf(
        Tool(
            name = "find_my_phone_status",
            description = "Get the current Find My Phone status and settings",
            parameters = emptyMap(),
        ) { _ ->
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
        },

        Tool(
            name = "find_my_phone_enable",
            description = "Enable Find My Phone mode",
            parameters = emptyMap(),
        ) { _ ->
            findMyPhoneManager.setEnabled(true)
            "✅ Find My Phone enabled"
        },

        Tool(
            name = "find_my_phone_disable",
            description = "Disable Find My Phone mode",
            parameters = emptyMap(),
        ) { _ ->
            findMyPhoneManager.setEnabled(false)
            "✅ Find My Phone disabled"
        },

        Tool(
            name = "find_my_phone_set_response",
            description = "Set the response type (ring_loud, speak_location, flash_led, all)",
            parameters = mapOf(
                "type" to "string" to "Response type",
            ),
        ) { args ->
            val type = args["type"] as? String ?: return@Tool "❌ type required"
            findMyPhoneManager.setResponseType(type)
            "✅ Response type set to $type"
        },

        Tool(
            name = "find_my_phone_set_message",
            description = "Set the TTS message to speak",
            parameters = mapOf(
                "message" to "string" to "TTS message",
            ),
        ) { args ->
            val message = args["message"] as? String ?: return@Tool "❌ message required"
            findMyPhoneManager.setTtsMessage(message)
            "✅ TTS message updated"
        },

        Tool(
            name = "find_my_phone_set_duration",
            description = "Set the response duration in seconds (10-60)",
            parameters = mapOf(
                "seconds" to "number" to "Duration in seconds",
            ),
        ) { args ->
            val seconds = (args["seconds"] as? Number)?.toInt() ?: return@Tool "❌ seconds required"
            findMyPhoneManager.setDurationSeconds(seconds)
            "✅ Duration set to $seconds seconds"
        },

        Tool(
            name = "find_my_phone_test",
            description = "Test the Find My Phone response",
            parameters = emptyMap(),
        ) { _ ->
            findMyPhoneResponder.triggerManualResponse()
            "✅ Find My Phone test triggered"
        },
    )
}
