package com.forge.os.domain.missingmode

import android.content.Context
import android.content.SharedPreferences
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import com.forge.os.domain.security.SecureKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages "Missing Mode" — auto-respond to calls when the phone is missing.
 *
 * When enabled, incoming calls from trusted contacts trigger an automatic
 * response (SMS or TTS answer). Non-trusted callers ring normally.
 */
@Singleton
class MissingModeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureKeyStore: SecureKeyStore,
) {
    companion object {
        private const val TAG = "MissingModeManager"
        private const val PREFS_NAME = "missing_mode_prefs"
        private const val KEY_ENABLED = "missing_mode_enabled"
        private const val KEY_RESPONSE_TYPE = "response_type"
        private const val KEY_SMS_TEMPLATE = "sms_template"
        private const val KEY_TTS_MESSAGE = "tts_message"
        private const val KEY_TRUSTED_CONTACTS = "trusted_contacts"

        const val RESPONSE_SMS = "sms"
        const val RESPONSE_TTS = "tts"
        const val RESPONSE_BOTH = "both"

        const val DEFAULT_SMS_TEMPLATE = "I can't answer right now. My phone is in missing mode. Please leave a message or try again later."
        const val DEFAULT_TTS_MESSAGE = "Hello, I cannot answer right now. Please leave a message."
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _state = MutableStateFlow(MissingModeState())
    val state: StateFlow<MissingModeState> = _state

    data class MissingModeState(
        val enabled: Boolean = false,
        val responseType: String = RESPONSE_SMS,
        val smsTemplate: String = DEFAULT_SMS_TEMPLATE,
        val ttsMessage: String = DEFAULT_TTS_MESSAGE,
        val trustedContacts: Set<String> = emptySet(),
        val lastTriggered: Long = 0L,
        val lastCaller: String = "",
    )

    init {
        loadState()
    }

    private fun loadState() {
        _state.value = MissingModeState(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            responseType = prefs.getString(KEY_RESPONSE_TYPE, RESPONSE_SMS) ?: RESPONSE_SMS,
            smsTemplate = prefs.getString(KEY_SMS_TEMPLATE, DEFAULT_SMS_TEMPLATE) ?: DEFAULT_SMS_TEMPLATE,
            ttsMessage = prefs.getString(KEY_TTS_MESSAGE, DEFAULT_TTS_MESSAGE) ?: DEFAULT_TTS_MESSAGE,
            trustedContacts = prefs.getStringSet(KEY_TRUSTED_CONTACTS, emptySet()) ?: emptySet(),
        )
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.value = _state.value.copy(enabled = enabled)
        Log.i(TAG, "Missing mode ${if (enabled) "enabled" else "disabled"}")
    }

    fun setResponseType(type: String) {
        prefs.edit().putString(KEY_RESPONSE_TYPE, type).apply()
        _state.value = _state.value.copy(responseType = type)
    }

    fun setSmsTemplate(template: String) {
        prefs.edit().putString(KEY_SMS_TEMPLATE, template).apply()
        _state.value = _state.value.copy(smsTemplate = template)
    }

    fun setTtsMessage(message: String) {
        prefs.edit().putString(KEY_TTS_MESSAGE, message).apply()
        _state.value = _state.value.copy(ttsMessage = message)
    }

    fun addTrustedContact(phoneNumber: String) {
        val normalized = normalizePhoneNumber(phoneNumber)
        val updated = _state.value.trustedContacts + normalized
        prefs.edit().putStringSet(KEY_TRUSTED_CONTACTS, updated).apply()
        _state.value = _state.value.copy(trustedContacts = updated)
    }

    fun removeTrustedContact(phoneNumber: String) {
        val normalized = normalizePhoneNumber(phoneNumber)
        val updated = _state.value.trustedContacts - normalized
        prefs.edit().putStringSet(KEY_TRUSTED_CONTACTS, updated).apply()
        _state.value = _state.value.copy(trustedContacts = updated)
    }

    fun isTrustedContact(phoneNumber: String): Boolean {
        val normalized = normalizePhoneNumber(phoneNumber)
        return _state.value.trustedContacts.any { trusted ->
            normalized.endsWith(trusted) || trusted.endsWith(normalized)
        }
    }

    fun onCallHandled(callerNumber: String) {
        _state.value = _state.value.copy(
            lastTriggered = System.currentTimeMillis(),
            lastCaller = callerNumber,
        )
    }

    /**
     * Send auto-reply SMS to the caller.
     */
    fun sendAutoReplySms(phoneNumber: String): Boolean {
        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            val message = _state.value.smsTemplate
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            Log.i(TAG, "Auto-reply SMS sent to $phoneNumber")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send auto-reply SMS", e)
            false
        }
    }

    /**
     * Check if the device is currently in a call.
     */
    fun isInCall(): Boolean {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE
    }

    private fun normalizePhoneNumber(number: String): String {
        return number.replace(Regex("[^0-9+]"), "")
    }
}
