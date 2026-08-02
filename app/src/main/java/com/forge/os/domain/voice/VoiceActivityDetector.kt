package com.forge.os.domain.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Simple energy-based Voice Activity Detector (VAD).
 *
 * Continuously monitors the microphone and detects when someone starts
 * speaking by measuring RMS energy. When speech is detected, emits to
 * [speechDetected] so the service can trigger STT for keyword matching.
 *
 * Battery-efficient: runs on a background thread, uses minimal CPU.
 */
@Singleton
class VoiceActivityDetector @Inject constructor() {

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private var audioRecord: AudioRecord? = null
    private var detectionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _speechDetected = MutableStateFlow(false)
    val speechDetected: StateFlow<Boolean> = _speechDetected

    /** RMS energy threshold for speech detection (0..1). */
    var energyThreshold = 0.02f

    /** Number of consecutive frames above threshold to trigger detection. */
    var consecutiveFramesRequired = 3

    /** Frame size in samples (100ms at 16kHz). */
    private val frameSize = 1600

    @SuppressLint("MissingPermission")
    fun start() {
        if (detectionJob?.isActive == true) {
            Timber.d("VoiceActivityDetector: already running")
            return
        }

        Timber.d("VoiceActivityDetector: starting")
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Timber.e("VoiceActivityDetector: failed to initialize AudioRecord")
            return
        }

        audioRecord?.startRecording()
        _speechDetected.value = false

        detectionJob = scope.launch {
            val buffer = ShortArray(frameSize)
            var consecutiveAboveThreshold = 0

            while (isActive) {
                val read = audioRecord?.read(buffer, 0, frameSize) ?: 0
                if (read > 0) {
                    val rms = computeRms(buffer, read)
                    if (rms > energyThreshold) {
                        consecutiveAboveThreshold++
                        if (consecutiveAboveThreshold >= consecutiveFramesRequired) {
                            if (!_speechDetected.value) {
                                Timber.d("VoiceActivityDetector: speech detected (rms=$rms)")
                                _speechDetected.value = true
                            }
                        }
                    } else {
                        consecutiveAboveThreshold = 0
                        if (_speechDetected.value) {
                            Timber.d("VoiceActivityDetector: speech ended")
                            _speechDetected.value = false
                        }
                    }
                }
                // Small delay to prevent busy-waiting
                delay(10)
            }
        }
    }

    fun stop() {
        Timber.d("VoiceActivityDetector: stopping")
        detectionJob?.cancel()
        detectionJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        _speechDetected.value = false
    }

    private fun computeRms(buffer: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            sum += buffer[i] * buffer[i]
        }
        return sqrt(sum / length).toFloat() / Short.MAX_VALUE
    }
}
