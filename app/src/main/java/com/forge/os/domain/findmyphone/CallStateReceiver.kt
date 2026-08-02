package com.forge.os.domain.findmyphone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Listens for incoming calls and triggers Find My Phone response if enabled.
 */
@AndroidEntryPoint
class CallStateReceiver : BroadcastReceiver() {

    @Inject lateinit var findMyPhoneManager: FindMyPhoneManager
    @Inject lateinit var findMyPhoneResponder: FindMyPhoneResponder

    companion object {
        private const val TAG = "CallStateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "Unknown"

        Log.d(TAG, "Phone state changed: $state, number: $phoneNumber")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // Check if Find My Phone is enabled
                if (findMyPhoneManager.state.value.enabled) {
                    Log.i(TAG, "Find My Phone enabled, triggering response for $phoneNumber")
                    findMyPhoneResponder.handleIncomingCall(phoneNumber)
                }
            }
        }
    }
}
