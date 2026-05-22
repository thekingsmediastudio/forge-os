package com.forge.os.domain.security

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import com.forge.os.domain.config.ConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Vigilance Heartbeat: Calculates a Multi-Modal Trust Score (0-100).
 * Determines how much Forge "trusts" its current environment.
 */
@Singleton
class TrustScoreManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configRepository: ConfigRepository,
    private val geofenceManager: GeofenceManager
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _trustScore = MutableStateFlow(50) // Default moderate trust
    val trustScore: StateFlow<Int> = _trustScore

    private val _vigilanceLevel = MutableStateFlow(VigilanceLevel.NORMAL)
    val vigilanceLevel: StateFlow<VigilanceLevel> = _vigilanceLevel

    enum class VigilanceLevel {
        LOW,      // High Trust: Relaxed security
        NORMAL,   // Moderate Trust: Standard security
        HIGH,     // Low Trust: Strict confirmations, increased snatch sensitivity
        PARANOID  // Zero Trust: Partial lockdown, Ghost Mode ready
    }

    /** Re-calculate the global trust score based on all available environment data. */
    fun calculateCurrentTrust(): Int {
        if (!configRepository.get().environmentCalibration.trustEngineEnabled) {
            _trustScore.value = 100 // Default to full trust if disabled
            _vigilanceLevel.value = VigilanceLevel.LOW
            return 100
        }
        var score = 0
        
        // 1. Bluetooth Presence (The "Tether")
        if (isTrustedBluetoothConnected()) {
            score += 40
            Timber.d("Trust: Trusted Bluetooth device detected (+40)")
        }

        // 2. WiFi Fingerprinting (The "Sanctuary")
        if (isTrustedWifiBssid()) {
            score += 30
            Timber.d("Trust: Known BSSID fingerprint match (+30)")
        } else if (isPublicWifi()) {
            score -= 20
            Timber.d("Trust: Public WiFi detected (-20)")
        }

        // 3. Location Wisdom
        // (Placeholder for Geofencing - would add +20 for "Home/Work")

        // 4. Power Integrity
        // (Placeholder for Power - would add +10 for "Charging")

        val finalScore = score.coerceIn(0, 100)
        _trustScore.value = finalScore
        updateVigilance(finalScore)
        
        return finalScore
    }

    private fun updateVigilance(score: Int) {
        if (!configRepository.get().environmentCalibration.trustEngineEnabled) {
            _vigilanceLevel.value = VigilanceLevel.LOW
            return
        }
        val level = when {
            score >= 80 -> VigilanceLevel.LOW
            score >= 50 -> VigilanceLevel.NORMAL
            score >= 30 -> VigilanceLevel.HIGH
            else -> VigilanceLevel.PARANOID
        }
        _vigilanceLevel.value = level
        Timber.i("Vigilance Level shifted to: $level (Score: $score)")
    }

    private fun isTrustedBluetoothConnected(): Boolean {
        return try {
            val connectedDevices = bluetoothManager.adapter?.bondedDevices ?: emptySet()
            // In a real implementation, we'd check against a list of 'Trusted MACs' in Config
            // For now, any bonded device counts as a positive tether for the pilot.
            connectedDevices.any { it.bondState == BluetoothDevice.BOND_BONDED }
        } catch (e: Exception) {
            false
        }
    }

    private fun isTrustedWifiBssid(): Boolean {
        val info = wifiManager.connectionInfo
        val bssid = info?.bssid ?: return false
        // Again, would check against config.trustSettings.trustedBssids
        return bssid.isNotEmpty() && bssid != "00:00:00:00:00:00"
    }

    private fun isPublicWifi(): Boolean {
        val info = wifiManager.connectionInfo
        val ssid = info?.ssid ?: ""
        return ssid.contains("Public", ignoreCase = true) || ssid.contains("Guest", ignoreCase = true)
    }

    fun getVigilanceBriefing(): String {
        val score = _trustScore.value
        val level = _vigilanceLevel.value
        return "Trust Score: $score/100 | Vigilance: $level"
    }

    fun isAcousticEnabled(): Boolean {
        return configRepository.get().environmentCalibration.acousticSensesEnabled
    }
}
