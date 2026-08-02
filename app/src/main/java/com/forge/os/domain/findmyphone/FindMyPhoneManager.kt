package com.forge.os.domain.findmyphone

import android.content.Context
import android.content.SharedPreferences
import android.telephony.TelephonyManager
import android.util.Log
import com.forge.os.domain.security.SecureKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages "Find My Phone" — helps locate a lost phone by answering incoming
 * calls and playing loud sounds or speaking location hints.
 *
 * When enabled, ANY incoming call triggers an automatic response to help
 * the user find their phone (loud ring, TTS announcement, camera flash).
 */
@Singleton
class FindMyPhoneManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureKeyStore: SecureKeyStore,
) {
    companion object {
        private const val TAG = "FindMyPhoneManager"
        private const val PREFS_NAME = "find_my_phone_prefs"
        private const val KEY_ENABLED = "find_my_phone_enabled"
        private const val KEY_RESPONSE_TYPE = "response_type"
        private const val KEY_TTS_MESSAGE = "tts_message"
        private const val KEY_DURATION_SECONDS = "duration_seconds"

        const val RESPONSE_RING_LOUD = "ring_loud"
        const val RESPONSE_SPEAK_LOCATION = "speak_location"
        const val RESPONSE_FLASH_LED = "flash_led"
        const val RESPONSE_ALL = "all"

        const val DEFAULT_TTS_MESSAGE = "I'm here! You found me!"
        const val DEFAULT_DURATION_SECONDS = 30
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _state = MutableStateFlow(FindMyPhoneState())
    val state: StateFlow<FindMyPhoneState> = _state

    data class FindMyPhoneState(
        val enabled: Boolean = false,
        val responseType: String = RESPONSE_ALL,
        val ttsMessage: String = DEFAULT_TTS_MESSAGE,
        val durationSeconds: Int = DEFAULT_DURATION_SECONDS,
        val lastTriggered: Long = 0L,
        val lastCaller: String = "",
    )

    init {
        loadState()
    }

    private fun loadState() {
        _state.value = FindMyPhoneState(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            responseType = prefs.getString(KEY_RESPONSE_TYPE, RESPONSE_ALL) ?: RESPONSE_ALL,
            ttsMessage = prefs.getString(KEY_TTS_MESSAGE, DEFAULT_TTS_MESSAGE) ?: DEFAULT_TTS_MESSAGE,
            durationSeconds = prefs.getInt(KEY_DURATION_SECONDS, DEFAULT_DURATION_SECONDS),
        )
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.value = _state.value.copy(enabled = enabled)
        Log.i(TAG, "Find My Phone ${if (enabled) "enabled" else "disabled"}")
    }

    fun setResponseType(type: String) {
        prefs.edit().putString(KEY_RESPONSE_TYPE, type).apply()
        _state.value = _state.value.copy(responseType = type)
    }

    fun setTtsMessage(message: String) {
        prefs.edit().putString(KEY_TTS_MESSAGE, message).apply()
        _state.value = _state.value.copy(ttsMessage = message)
    }

    fun setDurationSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_DURATION_SECONDS, seconds).apply()
        _state.value = _state.value.copy(durationSeconds = seconds)
    }

    fun onCallHandled(callerNumber: String) {
        _state.value = _state.value.copy(
            lastTriggered = System.currentTimeMillis(),
            lastCaller = callerNumber,
        )
    }

    /**
     * Check if the device is currently in a call.
     */
    fun isInCall(): Boolean {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE
    }
}
