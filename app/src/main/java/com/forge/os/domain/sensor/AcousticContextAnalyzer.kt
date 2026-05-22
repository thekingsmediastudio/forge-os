package com.forge.os.domain.sensor

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.forge.os.domain.security.TrustScoreManager
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
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The Ears of Forge: Analyzes environmental sound levels to determine social context.
 * Performs periodic offline decibel checks to distinguish between Quiet/Office and Public/Street.
 */
@Singleton
class AcousticContextAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trustManager: TrustScoreManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _contextState = MutableStateFlow(AcousticState.UNKNOWN)
    val contextState: StateFlow<AcousticState> = _contextState

    enum class AcousticState {
        UNKNOWN,
        QUIET,   // < 40dB - Private/Safe
        OFFICE,  // 40-60dB - Work/Professional
        PUBLIC,  // 60-80dB - Social/Mall
        EXTREME  // > 80dB - Street/Concert/Danger
    }

    private var isMonitoring = false

    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true
        scope.launch {
            while (isMonitoring) {
                if (trustManager.isAcousticEnabled()) {
                    analyzeSnapshot()
                } else {
                    _contextState.value = AcousticState.UNKNOWN
                }
                delay(30000)
            }
        }
    }

    private fun analyzeSnapshot() {
        try {
            val bufferSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) return

            val buffer = ShortArray(bufferSize)
            audioRecord.startRecording()
            audioRecord.read(buffer, 0, bufferSize)
            audioRecord.stop()
            audioRecord.release()

            val db = calculateDecibels(buffer)
            updateState(db)
        } catch (e: SecurityException) {
            Timber.e("Acoustic: Missing RECORD_AUDIO permission.")
        } catch (e: Exception) {
            Timber.e(e, "Acoustic: Snapshot analysis failed.")
        }
    }

    private fun calculateDecibels(buffer: ShortArray): Double {
        var sum = 0.0
        for (sample in buffer) {
            sum += sample * sample
        }
        val rms = sqrt(sum / buffer.size)
        return if (rms > 0) 20 * log10(rms / 32768.0) + 90 else 0.0
    }

    private fun updateState(db: Double) {
        val newState = when {
            db < 40 -> AcousticState.QUIET
            db < 60 -> AcousticState.OFFICE
            db < 80 -> AcousticState.PUBLIC
            else -> AcousticState.EXTREME
        }
        
        if (_contextState.value != newState) {
            _contextState.value = newState
            Timber.i("Acoustic context shifted to: $newState (${String.format("%.1f dB", db)})")
            // Acoustic state influences trust score calculation in the next loop
            trustManager.calculateCurrentTrust()
        }
    }
}
