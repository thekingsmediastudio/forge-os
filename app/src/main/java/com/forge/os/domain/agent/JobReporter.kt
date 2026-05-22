package com.forge.os.domain.agent

import com.forge.os.data.conversations.ConversationRepository
import com.forge.os.domain.channels.ChannelManager
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes output from background jobs (Cron, Alarms) to the requested destination.
 */
@Singleton
class JobReporter @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val channelManager: ChannelManager
) {
    /**
     * Reports output to the specified destination.
     * @param reportTo Destination identifier (e.g., "main", "channel:Telegram", "Telegram")
     * @param toolName The name of the tool/job that produced the output
     * @param output The content to report
     */
    suspend fun report(reportTo: String?, toolName: String, output: String) {
        if (reportTo.isNullOrBlank()) {
            Timber.d("JobReporter: No report destination for $toolName")
            return
        }

        Timber.i("JobReporter: Reporting $toolName output to $reportTo")

        when {
            reportTo.equals("main", ignoreCase = true) -> {
                val header = "📋 **Background Job Result: $toolName**\n\n"
                conversationRepository.appendAssistantMessage(header + output, toolName)
            }
            reportTo.startsWith("channel:", ignoreCase = true) -> {
                val channelName = reportTo.removePrefix("channel:").trim()
                sendToChannel(channelName, toolName, output)
            }
            else -> {
                // Assume it's a channel name if not "main" and no prefix
                sendToChannel(reportTo, toolName, output)
            }
        }
    }

    private suspend fun sendToChannel(channelName: String, toolName: String, output: String) {
        val channel = channelManager.getChannelByName(channelName)
        if (channel == null) {
            Timber.w("JobReporter: Channel '$channelName' not found for reporting $toolName")
            // Fallback to main chat so the result isn't lost?
            val msg = "⚠️ **Job Reporter**: Channel '$channelName' not found. Result for $toolName:\n\n$output"
            conversationRepository.appendAssistantMessage(msg, toolName)
            return
        }

        val message = "📋 **Forge Background Job: $toolName**\n\n$output"
        channelManager.sendMessage(channel.id, message)
    }
}
