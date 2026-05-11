package com.forge.os.domain.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.forge.os.domain.config.ConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides tactile feedback for agent activities.
 * Respects the [AppearanceSettings.hapticFeedbackEnabled] setting.
 */
@Singleton
class HapticFeedbackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configRepository: ConfigRepository
) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    enum class Pattern {
        LIGHT_TICK,     // Subtle confirmation
        SUCCESS,        // Quick double pulse
        ERROR,          // Heavy triple pulse
        THINKING_START, // Gradual ramp up
        HEARTBEAT       // Thump-thump
    }

    fun trigger(pattern: Pattern) {
        if (!configRepository.get().appearance.hapticFeedbackEnabled) return
        if (vibrator == null || !vibrator.hasVibrator()) return

        when (pattern) {
            Pattern.LIGHT_TICK -> vibrate(longArrayOf(0, 10), intArrayOf(0, 150))
            Pattern.SUCCESS -> vibrate(longArrayOf(0, 50, 50, 50), intArrayOf(0, 180, 0, 255))
            Pattern.ERROR -> vibrate(longArrayOf(0, 100, 50, 100, 50, 100), intArrayOf(0, 255, 0, 255, 0, 255))
            Pattern.THINKING_START -> vibrate(longArrayOf(0, 200), intArrayOf(0, 100))
            Pattern.HEARTBEAT -> vibrate(longArrayOf(0, 40, 120, 40), intArrayOf(0, 180, 0, 120))
        }
    }

    private fun vibrate(timings: LongArray, amplitudes: IntArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(timings, -1)
            }
        } catch (e: Exception) {
            // Ignore vibration errors
        }
    }
}
