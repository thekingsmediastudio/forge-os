package com.forge.os.security.antitheft

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives incoming SMS and processes anti-theft commands from trusted contacts.
 *
 * Commands: LOCK, LOCATE, WIPE, ALARM, STATUS
 */
@AndroidEntryPoint
class SmsCommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsCommandReceiver"
    }

    @Inject
    lateinit var antiTheftManager: AntiTheftManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        for (sms in messages) {
            val sender = sms.displayOriginatingAddress ?: continue
            val body = sms.displayMessageBody ?: continue

            Log.d(TAG, "SMS received from $sender: $body")

            // Check if it's a command (all caps, short)
            if (isCommand(body)) {
                handleCommand(sender, body)
            }
        }
    }

    private fun isCommand(text: String): Boolean {
        val commands = listOf("LOCK", "LOCATE", "WIPE", "WIPE CONFIRM", "ALARM", "STATUS")
        val upper = text.uppercase().trim()
        return commands.any { upper == it }
    }

    private fun handleCommand(sender: String, command: String) {
        val state = antiTheftManager.state.value

        if (!state.enabled) {
            Log.d(TAG, "Anti-theft disabled, ignoring command")
            return
        }

        val response = antiTheftManager.handleSmsCommand(sender, command)
        Log.i(TAG, "Command '$command' from $sender: $response")

        // Send response back
        antiTheftManager.sendSms(sender, response)
    }
}
