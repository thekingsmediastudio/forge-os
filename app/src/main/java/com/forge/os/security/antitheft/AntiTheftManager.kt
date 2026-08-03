package com.forge.os.security.antitheft

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.telephony.SmsManager
import android.util.Log
import com.forge.os.domain.security.SecureKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Anti-Theft "Grab & Run" protection.
 *
 * Features:
 * - Device admin integration (lock, wipe)
 * - Trusted contacts for alerts
 * - Location tracking when triggered
 * - SMS command receiver (LOCK, LOCATE, WIPE, ALARM)
 * - Theft detection (SIM change, sudden motion, charger disconnect)
 */
@Singleton
class AntiTheftManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureKeyStore: SecureKeyStore,
) {
    companion object {
        private const val TAG = "AntiTheftManager"
        private const val PREFS_NAME = "antitheft_prefs"
        private const val KEY_ENABLED = "antitheft_enabled"
        private const val KEY_TRIGGERED = "antitheft_triggered"
        private const val KEY_TRUSTED_CONTACTS = "trusted_contacts"
        private const val KEY_ALERT_MESSAGE = "alert_message"
        private const val KEY_LAST_LOCATION = "last_location"
        private const val KEY_SIM_SERIAL = "sim_serial"

        const val DEFAULT_ALERT_MESSAGE = "ALERT: My phone may have been stolen. Last known location attached."

        // SMS Commands
        const val CMD_LOCK = "LOCK"
        const val CMD_LOCATE = "LOCATE"
        const val CMD_WIPE = "WIPE"
        const val CMD_ALARM = "ALARM"
        const val CMD_STATUS = "STATUS"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val devicePolicyManager: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private val adminComponent: ComponentName by lazy {
        ComponentName(context, AntiTheftReceiver::class.java)
    }

    private val _state = MutableStateFlow(AntiTheftState())
    val state: StateFlow<AntiTheftState> = _state

    data class AntiTheftState(
        val enabled: Boolean = false,
        val triggered: Boolean = false,
        val deviceAdminActive: Boolean = false,
        val trustedContacts: Set<String> = emptySet(),
        val alertMessage: String = DEFAULT_ALERT_MESSAGE,
        val lastLatitude: Double = 0.0,
        val lastLongitude: Double = 0.0,
        val lastLocationTime: Long = 0L,
        val triggeredTime: Long = 0L,
    )

    init {
        loadState()
    }

    private fun loadState() {
        val lastLoc = prefs.getString(KEY_LAST_LOCATION, null)?.split(",")
        _state.value = AntiTheftState(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            triggered = prefs.getBoolean(KEY_TRIGGERED, false),
            deviceAdminActive = isDeviceAdminActive(),
            trustedContacts = prefs.getStringSet(KEY_TRUSTED_CONTACTS, emptySet()) ?: emptySet(),
            alertMessage = prefs.getString(KEY_ALERT_MESSAGE, DEFAULT_ALERT_MESSAGE) ?: DEFAULT_ALERT_MESSAGE,
            lastLatitude = lastLoc?.getOrNull(0)?.toDoubleOrNull() ?: 0.0,
            lastLongitude = lastLoc?.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
            lastLocationTime = lastLoc?.getOrNull(2)?.toLongOrNull() ?: 0L,
        )
    }

    // ── Device Admin ─────────────────────────────────────────────────────────

    fun isDeviceAdminActive(): Boolean {
        return devicePolicyManager.isAdminActive(adminComponent)
    }

    fun requestDeviceAdmin(): Intent {
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Forge OS needs device admin to lock and wipe your phone in case of theft.")
        }
    }

    // ── Enable/Disable ───────────────────────────────────────────────────────

    fun setEnabled(enabled: Boolean) {
        if (enabled && !isDeviceAdminActive()) {
            Log.w(TAG, "Cannot enable anti-theft without device admin")
            return
        }
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.value = _state.value.copy(enabled = enabled, deviceAdminActive = isDeviceAdminActive())
        Log.i(TAG, "Anti-theft ${if (enabled) "enabled" else "disabled"}")
    }

    // ── Trusted Contacts ─────────────────────────────────────────────────────

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
        // Exact match only — suffix matching is vulnerable to number spoofing.
        // We also try matching with/without country code prefix for convenience.
        return _state.value.trustedContacts.any { trusted ->
            normalized == trusted ||
            // Allow matching without leading '+' (e.g. "1234567890" vs "+1234567890")
            normalized.removePrefix("+") == trusted.removePrefix("+")
        }
    }

    // ── Alert Message ────────────────────────────────────────────────────────

    fun setAlertMessage(message: String) {
        prefs.edit().putString(KEY_ALERT_MESSAGE, message).apply()
        _state.value = _state.value.copy(alertMessage = message)
    }

    // ── Theft Trigger ────────────────────────────────────────────────────────

    fun triggerTheft(reason: String) {
        if (!_state.value.enabled) return
        if (_state.value.triggered) return // Already triggered

        Log.w(TAG, "THEFT TRIGGERED: $reason")
        prefs.edit().putBoolean(KEY_TRIGGERED, true).apply()
        _state.value = _state.value.copy(triggered = true, triggeredTime = System.currentTimeMillis())

        // Lock device immediately
        lockDevice()

        // Get and save location
        updateLocation()

        // Send alerts to trusted contacts
        sendAlerts()
    }

    fun clearTriggered() {
        prefs.edit().putBoolean(KEY_TRIGGERED, false).apply()
        _state.value = _state.value.copy(triggered = false, triggeredTime = 0L)
        Log.i(TAG, "Theft trigger cleared")
    }

    // ── Device Actions ───────────────────────────────────────────────────────

    fun lockDevice(): Boolean {
        return try {
            if (isDeviceAdminActive()) {
                devicePolicyManager.lockNow()
                Log.i(TAG, "Device locked")
                true
            } else {
                Log.w(TAG, "Device admin not active, cannot lock")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock device", e)
            false
        }
    }

    fun wipeDevice(): Boolean {
        return try {
            if (isDeviceAdminActive()) {
                devicePolicyManager.wipeData(0)
                Log.w(TAG, "Device wipe initiated")
                true
            } else {
                Log.w(TAG, "Device admin not active, cannot wipe")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wipe device", e)
            false
        }
    }

    // ── Location ─────────────────────────────────────────────────────────────

    fun updateLocation(): Pair<Double, Double>? {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

            var bestLocation: Location? = null
            for (provider in providers) {
                try {
                    val loc = locationManager.getLastKnownLocation(provider)
                    if (loc != null && (bestLocation == null || loc.accuracy < bestLocation!!.accuracy)) {
                        bestLocation = loc
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Location permission not granted for $provider")
                }
            }

            bestLocation?.let { loc ->
                val locStr = "${loc.latitude},${loc.longitude},${System.currentTimeMillis()}"
                prefs.edit().putString(KEY_LAST_LOCATION, locStr).apply()
                _state.value = _state.value.copy(
                    lastLatitude = loc.latitude,
                    lastLongitude = loc.longitude,
                    lastLocationTime = System.currentTimeMillis(),
                )
                Log.i(TAG, "Location updated: ${loc.latitude}, ${loc.longitude}")
                Pair(loc.latitude, loc.longitude)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get location", e)
            null
        }
    }

    // ── Alerts ───────────────────────────────────────────────────────────────

    fun sendAlerts() {
        val state = _state.value
        val location = "https://maps.google.com/?q=${state.lastLatitude},${state.lastLongitude}"
        val message = "${state.alertMessage}\n\nLocation: $location"

        state.trustedContacts.forEach { contact ->
            sendSms(contact, message)
        }
    }

    fun sendSms(phoneNumber: String, message: String): Boolean {
        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            Log.i(TAG, "SMS sent to $phoneNumber")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to $phoneNumber", e)
            false
        }
    }

    // ── SMS Command Handler ──────────────────────────────────────────────────

    /** Timestamp of the last WIPE request, for time-limited confirmation. */
    @Volatile
    private var wipeRequestTime: Long = 0L
    private val wipeConfirmWindowMs = 60_000L // 60 seconds

    fun handleSmsCommand(sender: String, command: String): String {
        if (!isTrustedContact(sender)) {
            Log.w(TAG, "SMS command from untrusted number: $sender")
            return "Unauthorized"
        }

        return when (command.uppercase().trim()) {
            CMD_LOCK -> {
                if (lockDevice()) "Device locked" else "Failed to lock device"
            }
            CMD_LOCATE -> {
                val loc = updateLocation()
                if (loc != null) {
                    "Location: https://maps.google.com/?q=${loc.first},${loc.second}"
                } else {
                    "Location unavailable"
                }
            }
            CMD_WIPE -> {
                // Start the confirmation window — WIPE CONFIRM must arrive within 60s
                wipeRequestTime = System.currentTimeMillis()
                "WIPE requires confirmation. Reply with 'WIPE CONFIRM' within 60 seconds to proceed."
            }
            "WIPE CONFIRM" -> {
                val elapsed = System.currentTimeMillis() - wipeRequestTime
                if (wipeRequestTime == 0L || elapsed > wipeConfirmWindowMs) {
                    wipeRequestTime = 0L
                    "WIPE confirmation expired or no WIPE request pending. Send 'WIPE' first."
                } else {
                    wipeRequestTime = 0L
                    if (wipeDevice()) "Device wipe initiated" else "Failed to wipe device"
                }
            }
            CMD_ALARM -> {
                // TODO: Play loud alarm sound
                "Alarm triggered"
            }
            CMD_STATUS -> {
                val state = _state.value
                "Anti-theft: ${if (state.enabled) "enabled" else "disabled"}, " +
                "triggered: ${state.triggered}, " +
                "admin: ${state.deviceAdminActive}"
            }
            else -> "Unknown command. Available: LOCK, LOCATE, WIPE, ALARM, STATUS"
        }
    }

    // ── SIM Change Detection ─────────────────────────────────────────────────

    fun checkSimChange(currentSimSerial: String?) {
        if (!_state.value.enabled) return
        if (currentSimSerial == null) return

        val savedSerial = prefs.getString(KEY_SIM_SERIAL, null)
        if (savedSerial == null) {
            // First run, save current SIM
            prefs.edit().putString(KEY_SIM_SERIAL, currentSimSerial).apply()
            return
        }

        if (savedSerial != currentSimSerial) {
            Log.w(TAG, "SIM card changed! Old: $savedSerial, New: $currentSimSerial")
            triggerTheft("SIM card changed")
        }
    }

    private fun normalizePhoneNumber(number: String): String {
        return number.replace(Regex("[^0-9+]"), "")
    }
}
