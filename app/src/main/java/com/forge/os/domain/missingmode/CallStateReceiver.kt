package com.forge.os.domain.missingmode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * BroadcastReceiver that detects incoming calls and triggers auto-response
 * when Missing Mode is enabled.
 */
@AndroidEntryPoint
class CallStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallStateReceiver"
    }

    @Inject
    lateinit var missingModeManager: MissingModeManager

    @Inject
    lateinit var autoResponder: AutoResponder

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: return

        Log.d(TAG, "Phone state changed: $state, number: $incomingNumber")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                handleIncomingCall(context, incomingNumber)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // Call answered
                Log.d(TAG, "Call answered")
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                // Call ended
                Log.d(TAG, "Call ended")
            }
        }
    }

    private fun handleIncomingCall(context: Context, phoneNumber: String) {
        val state = missingModeManager.state.value

        if (!state.enabled) {
            Log.d(TAG, "Missing mode disabled, ignoring call")
            return
        }

        if (phoneNumber.isBlank()) {
            Log.d(TAG, "Unknown caller, ignoring")
            return
        }

        val isTrusted = missingModeManager.isTrustedContact(phoneNumber)
        Log.d(TAG, "Incoming call from $phoneNumber, trusted: $isTrusted")

        if (isTrusted) {
            // Auto-respond to trusted contact
            autoResponder.handleTrustedCall(phoneNumber)
        } else {
            // Let non-trusted calls ring normally
            Log.d(TAG, "Non-trusted caller, letting ring")
        }
    }
}
