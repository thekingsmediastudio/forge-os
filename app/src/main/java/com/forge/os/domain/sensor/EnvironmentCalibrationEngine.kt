package com.forge.os.domain.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.haptic.HapticFeedbackManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * The Wisdom Engine: Cross-validates reality using physical sensors.
 * Implements "Reality Calibration" to ensure Forge knows what is REALLY going on.
 */
@Singleton
class EnvironmentCalibrationEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configRepository: ConfigRepository,
    private val hapticManager: com.forge.os.domain.haptic.HapticFeedbackManager,
    private val sentinelManager: dagger.Lazy<com.forge.os.domain.sentinel.SentinelManager>,
    private val trustManager: com.forge.os.domain.security.TrustScoreManager
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isCalibrating = MutableStateFlow(false)
    val isCalibrating: StateFlow<Boolean> = _isCalibrating

    private var lastAccelValues = FloatArray(3)
    private var isPocketed = false
    private var ambientLight = 0f

    init {
        sentinelManager.get().setCalibrationEngine(this)
        startMonitoring()
    }

    private fun startMonitoring() {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val prox = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL)
        
        Timber.i("EnvironmentCalibrationEngine: Neural sensor mesh active.")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val settings = configRepository.get().environmentCalibration
        if (!settings.enabled) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccelValues = event.values.clone()
                checkSnatchDetection(event.values)
            }
            Sensor.TYPE_PROXIMITY -> {
                isPocketed = event.values[0] < event.sensor.maximumRange
                if (settings.pocketDetectionEnabled) {
                    handlePocketStateChange(isPocketed)
                }
            }
            Sensor.TYPE_LIGHT -> {
                ambientLight = event.values[0]
            }
        }
    }

    private fun checkSnatchDetection(values: FloatArray) {
        val settings = configRepository.get().environmentCalibration
        if (!settings.snatchDetectionEnabled) return

        val gForce = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2]) / 9.81f
        if (gForce > 3.0f) { // Jerk threshold for snatch
            Timber.w("Calibration: Snatch Detected! G-Force: $gForce")
            sentinelManager.get().fire(com.forge.os.domain.sentinel.SentinelEventType.SNATCH_DETECTED, "FORCE: $gForce")
            
            if (settings.testMode) {
                hapticManager.trigger(com.forge.os.domain.haptic.HapticFeedbackManager.Pattern.HEAVY_PULSE)
            }
        }
    }

    private fun handlePocketStateChange(pocketed: Boolean) {
        if (pocketed) {
            Timber.i("Calibration: Device entered POCKET mode.")
        } else {
            Timber.i("Calibration: Device entered DESK/SURFACE mode.")
        }
    }

    fun getSummary(): String {
        return buildString {
            append("• State: ${if (isPocketed) "POCKET" else "OPEN/SURFACE"}\n")
            append("• Ambient Light: ${String.format("%.1f lx", ambientLight)}\n")
            val g = sqrt(lastAccelValues[0] * lastAccelValues[0] + lastAccelValues[1] * lastAccelValues[1] + lastAccelValues[2] * lastAccelValues[2]) / 9.81f
            append("• G-Force: ${String.format("%.2f G", g)}\n")
            append("• Trust: ${trustManager.getVigilanceBriefing()}\n")
            append("• Orientation: ${inferOrientation()}\n")
        }
    }

    private fun inferOrientation(): String {
        val x = lastAccelValues[0]
        val y = lastAccelValues[1]
        val z = lastAccelValues[2]
        return when {
            z > 8 -> "Face Up"
            z < -8 -> "Face Down"
            y > 8 -> "Portrait"
            y < -8 -> "Portrait Inverted"
            x > 8 -> "Landscape Left"
            x < -8 -> "Landscape Right"
            else -> "Inclined"
        }
    }

    /** Manually trigger a high-fidelity "Reality Check". */
    fun runRealityCheck() {
        scope.launch {
            _isCalibrating.value = true
            trustManager.calculateCurrentTrust()
            Timber.i("Calibration: Starting Reality Check Probing...")
            
            // Cross-validate light vs proximity
            val isDark = ambientLight < 5f
            if (isDark && isPocketed) {
                Timber.i("Calibration: High Confidence [POCKETED] - Light 0, Proximity Closed.")
            } else if (!isDark && !isPocketed) {
                Timber.i("Calibration: High Confidence [OPEN ENVIRONMENT] - Photons detected.")
            } else {
                Timber.w("Calibration: Ambiguous Reality - Light and Proximity mismatch.")
            }

            delay(2000)
            _isCalibrating.value = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
