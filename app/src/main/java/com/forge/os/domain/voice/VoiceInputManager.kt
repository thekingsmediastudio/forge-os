package com.forge.os.domain.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature 2: Voice Input via Android TTS/STT
 * 
 * Provides hands-free control for Forge OS using Android's built-in
 * Speech-to-Text (STT) and Text-to-Speech (TTS) capabilities.
 * 
 * Perfect for mobile use while cooking, driving, or coding.
 * 
 * Example: "Forge, create a new flashcard deck called 'Spanish Verbs'"
 */
@Singleton
class VoiceInputManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsInitialized = false
    private var speechRecognizerInitialized = false
    
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
    
    private val _lastRecognizedText = MutableStateFlow("")
    val lastRecognizedText: StateFlow<String> = _lastRecognizedText.asStateFlow()
    
    private val _ttsState = MutableStateFlow(TTSState.IDLE)
    val ttsState: StateFlow<TTSState> = _ttsState.asStateFlow()

    /** RMS audio level 0..1 for waveform animation while listening. */
    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()
    
    // Channel for voice commands
    private val voiceCommandChannel = Channel<VoiceCommand>(Channel.BUFFERED)
    
    // Main thread handler for initializing SpeechRecognizer
    private val mainHandler = Handler(Looper.getMainLooper())
    
    init {
        initializeTTS()
        // Don't initialize SpeechRecognizer here - it will be lazily initialized on main thread when needed
    }
    
    /**
     * Initialize Text-to-Speech engine.
     */
    private fun initializeTTS() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Timber.w("TTS: Language not supported")
                } else {
                    ttsInitialized = true
                    Timber.i("TTS initialized successfully")
                }
            } else {
                Timber.e("TTS initialization failed")
            }
        }
        
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _ttsState.value = TTSState.SPEAKING
            }
            
            override fun onDone(utteranceId: String?) {
                _ttsState.value = TTSState.IDLE
            }
            
            override fun onError(utteranceId: String?) {
                _ttsState.value = TTSState.ERROR
                Timber.e("TTS error for utterance: $utteranceId")
            }
        })
    }
    
    /**
     * Initialize Speech Recognizer on main thread.
     * This must be called on the main thread as required by Android.
     */
    private fun initializeSpeechRecognizer() {
        if (speechRecognizerInitialized) {
            return // Already initialized
        }
        
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Timber.w("Speech recognition not available on this device")
            return
        }
        
        // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Timber.w("initializeSpeechRecognizer called from background thread, posting to main thread")
            mainHandler.post { initializeSpeechRecognizer() }
            return
        }
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizerInitialized = true
        
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _isListening.value = true
                Timber.d("Ready for speech")
            }
            
            override fun onBeginningOfSpeech() {
                Timber.d("Speech started")
            }
            
            override fun onRmsChanged(rmsdB: Float) {
                // Normalise dB (-2..10 typical range) to 0..1 for UI
                _rmsLevel.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            
            override fun onBufferReceived(buffer: ByteArray?) {
                // Partial audio buffer received
            }
            
            override fun onEndOfSpeech() {
                _isListening.value = false
                _rmsLevel.value = 0f
                Timber.d("Speech ended")
            }
            
            override fun onError(error: Int) {
                _isListening.value = false
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                    else -> "Unknown error: $error"
                }
                Timber.w("Speech recognition error: $errorMessage")
            }
            
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]
                    _lastRecognizedText.value = recognizedText
                    Timber.i("Recognized: $recognizedText")
                    
                    // Parse and emit voice command
                    val command = parseVoiceCommand(recognizedText)
                    voiceCommandChannel.trySend(command)
                }
            }
            
            override fun onPartialResults(partialResults: Bundle?) {
                // Partial recognition results - could be used for real-time feedback
            }
            
            override fun onEvent(eventType: Int, params: Bundle?) {
                // Reserved for future events
            }
        })
        
        Timber.i("SpeechRecognizer initialized successfully on main thread")
    }
    
    /**
     * Start listening for voice input.
     */
    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Timber.w("Speech recognition not available")
            return
        }
        
        // Ensure SpeechRecognizer is initialized on main thread
        if (!speechRecognizerInitialized) {
            initializeSpeechRecognizer()
        }
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        
        speechRecognizer?.startListening(intent)
        Timber.i("Started listening for voice input")
    }
    
    /**
     * Stop listening for voice input.
     */
    fun stopListening() {
        speechRecognizer?.stopListening()
        _isListening.value = false
        Timber.i("Stopped listening for voice input")
    }
    
    /**
     * Speak text using TTS.
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!ttsInitialized) {
            Timber.w("TTS not initialized")
            return
        }
        
        val utteranceId = UUID.randomUUID().toString()
        textToSpeech?.speak(text, queueMode, null, utteranceId)
        Timber.i("Speaking: $text")
    }
    
    /**
     * Stop speaking.
     */
    fun stopSpeaking() {
        textToSpeech?.stop()
        _ttsState.value = TTSState.IDLE
    }
    
    /**
     * Check if TTS is available and initialized.
     */
    fun isTTSAvailable(): Boolean = ttsInitialized
    
    /**
     * Check if speech recognition is available.
     */
    fun isSTTAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)
    
    /**
     * Parse voice command from recognized text.
     */
    private fun parseVoiceCommand(text: String): VoiceCommand {
        val lowerText = text.lowercase()
        
        return when {
            lowerText.startsWith("forge") || lowerText.startsWith("hey forge") -> {
                // Remove wake word
                val command = lowerText
                    .removePrefix("hey forge")
                    .removePrefix("forge")
                    .trim()
                    .trimStart(',')
                    .trim()
                
                when {
                    command.startsWith("create") -> VoiceCommand.Create(command.removePrefix("create").trim())
                    command.startsWith("open") -> VoiceCommand.Open(command.removePrefix("open").trim())
                    command.startsWith("run") -> VoiceCommand.Run(command.removePrefix("run").trim())
                    command.startsWith("test") -> VoiceCommand.Test(command.removePrefix("test").trim())
                    command.startsWith("backup") -> VoiceCommand.Backup
                    command.startsWith("sync") -> VoiceCommand.Sync
                    command.startsWith("show") -> VoiceCommand.Show(command.removePrefix("show").trim())
                    else -> VoiceCommand.Generic(command)
                }
            }
            else -> VoiceCommand.Generic(text)
        }
    }
    
    /**
     * Get the next voice command (suspending).
     */
    suspend fun receiveVoiceCommand(): VoiceCommand {
        return voiceCommandChannel.receive()
    }
    
    /**
     * Cleanup resources.
     */
    fun cleanup() {
        speechRecognizer?.destroy()
        textToSpeech?.shutdown()
        voiceCommandChannel.close()
    }
}

/**
 * TTS state.
 */
enum class TTSState {
    IDLE,
    SPEAKING,
    ERROR
}

/**
 * Voice command types.
 */
sealed class VoiceCommand {
    data class Create(val what: String) : VoiceCommand()
    data class Open(val what: String) : VoiceCommand()
    data class Run(val what: String) : VoiceCommand()
    data class Test(val what: String) : VoiceCommand()
    data class Show(val what: String) : VoiceCommand()
    object Backup : VoiceCommand()
    object Sync : VoiceCommand()
    data class Generic(val text: String) : VoiceCommand()
}
