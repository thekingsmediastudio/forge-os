package com.forge.os.domain.voice
 
import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.forge.os.domain.security.ApiKeyProvider
import com.forge.os.domain.security.SecureKeyStore
import com.forge.os.domain.user.UserPreferencesManager
import com.forge.os.domain.user.VoicePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferencesManager: UserPreferencesManager,
    private val secureKeyStore: SecureKeyStore,
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isInitialized = false
    
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    // Map of utterance IDs to completion deferreds for synthesizeToFile
    private val ttsCompletions = Collections.synchronizedMap(mutableMapOf<String, Pair<File, CompletableDeferred<String?>>>())
    
    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Timber.e("TTS: Language not supported")
            } else {
                isInitialized = true
                Timber.i("TTS Initialized")
            }
        } else {
            Timber.e("TTS Initialization failed")
        }
        
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Timber.d("TTS started: $utteranceId")
            }
            
            override fun onDone(utteranceId: String?) {
                if (utteranceId != null && utteranceId.startsWith("gen_")) {
                    ttsCompletions.remove(utteranceId)?.let { (file, deferred) ->
                        deferred.complete(file.absolutePath)
                    }
                }
            }
            
            override fun onError(utteranceId: String?) {
                Timber.e("TTS error: $utteranceId")
                if (utteranceId != null && utteranceId.startsWith("gen_")) {
                    ttsCompletions.remove(utteranceId)?.second?.complete(null)
                }
            }
        })
    }

    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        val prefs = userPreferencesManager.getPreferences().voicePreferences
        if (!prefs.enabled) return

        if (prefs.provider == "system") {
            if (isInitialized) {
                tts?.setSpeechRate(prefs.speed)
                tts?.speak(text, queueMode, null, "forge_tts_${System.currentTimeMillis()}")
            }
        } else if (prefs.provider == "openai") {
            // Integration for high-fidelity neural voices via OpenAI
            // This would normally call an external TTS API
            Timber.i("Voice Feedback: Scaling to neural voice via OpenAI (${prefs.voiceId})")
            // Placeholder: for now fallback to system but log the intent
            if (isInitialized) {
                tts?.speak(text, queueMode, null, "forge_tts_neural")
            }
        }
    }

    /**
     * Automated high-fidelity speech: attempt to generate a file and play it, 
     * falling back to system TTS if file generation fails.
     */
    fun speakAuto(text: String, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                val path = synthesizeToFile(text)
                if (path != null) {
                    playLocalAudio(path)
                } else {
                    Timber.w("High-fidelity synthesis failed, falling back to system TTS")
                    speak(text)
                }
            } catch (e: Exception) {
                Timber.e(e, "speakAuto failed, falling back to system TTS")
                speak(text)
            }
        }
    }

    private fun playLocalAudio(path: String) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(path)
                    prepare()
                    start()
                    setOnCompletionListener { it.release() }
                }
                Timber.i("Playing synthesized audio (Manager): $path")
            } catch (e: Exception) {
                Timber.e(e, "Failed to play local audio: $path")
                speak("Playback error")
            }
        }
    }

    /**
     * Synthesize text to a file and return the absolute path.
     * This is a suspending call that waits for TTS engine completion.
     */
    suspend fun synthesizeToFile(text: String): String? {
        val prefs = userPreferencesManager.getPreferences().voicePreferences
        
        // Step 1: Try Neural Synthesis if configured
        if (prefs.enabled && prefs.provider != "system") {
            val neuralPath = synthesizeNeural(text, prefs)
            if (neuralPath != null) return neuralPath
        }

        // Step 2: Fallback to System TTS synthesis
        if (!isInitialized) {
            Timber.w("synthesizeToFile: System TTS not initialized")
            return null
        }
        
        val folder = File(context.filesDir, "workspace/media/tts").apply { mkdirs() }
        val filename = "tts_${System.currentTimeMillis()}.wav"
        val file = File(folder, filename)
        val utteranceId = "gen_${UUID.randomUUID()}"
        val deferred = CompletableDeferred<String?>()
        
        ttsCompletions[utteranceId] = file to deferred
        
        val result = tts?.synthesizeToFile(text, null, file, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Timber.e("synthesizeToFile failed to start (result=$result)")
            ttsCompletions.remove(utteranceId)
            return null
        }
        
        return withTimeoutOrNull(10000) { deferred.await() }
    }

    private suspend fun synthesizeNeural(text: String, prefs: VoicePreferences): String? = withContext(Dispatchers.IO) {
        // Resolve API Key from store
        val apiKey = when (prefs.provider) {
            "openai" -> secureKeyStore.getKey(ApiKeyProvider.OPENAI)
            "elevenlabs" -> secureKeyStore.getKey(ApiKeyProvider.ELEVENLABS)
            else -> null
        } ?: prefs.apiKey
        
        if (apiKey.isNullOrBlank()) {
            Timber.w("Neural synthesis skipped: No API key for ${prefs.provider}")
            return@withContext null
        }

        val folder = File(context.filesDir, "workspace/media/tts").apply { mkdirs() }
        val ext = if (prefs.provider == "openai") "mp3" else "mp3"
        val file = File(folder, "neural_${System.currentTimeMillis()}.$ext")

        try {
            val (url, body) = when (prefs.provider) {
                "openai" -> {
                    val bodyJson = JsonObject(mapOf(
                        "model" to JsonPrimitive("tts-1"),
                        "input" to JsonPrimitive(text),
                        "voice" to JsonPrimitive(prefs.voiceId),
                        "speed" to JsonPrimitive(prefs.speed)
                    )).toString()
                    "https://api.openai.com/v1/audio/speech" to bodyJson
                }
                "elevenlabs" -> {
                    val bodyJson = JsonObject(mapOf(
                        "text" to JsonPrimitive(text),
                        "model_id" to JsonPrimitive("eleven_monolingual_v1"),
                        "voice_settings" to JsonObject(mapOf(
                            "stability" to JsonPrimitive(0.5),
                            "similarity_boost" to JsonPrimitive(0.5)
                        ))
                    )).toString()
                    "https://api.elevenlabs.io/v1/text-to-speech/${prefs.voiceId}" to bodyJson
                }
                else -> return@withContext null
            }

            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .apply { 
                    if (prefs.provider == "elevenlabs") {
                        addHeader("xi-api-key", apiKey)
                    }
                }
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("Neural TTS Error [${response.code}]: ${response.body?.string()}")
                    return@withContext null
                }
                response.body?.byteStream()?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            Timber.i("Neural synthesis success: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "Neural synthesis exception")
            null
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
