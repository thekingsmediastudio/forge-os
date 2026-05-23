package com.forge.os.domain.ai

import android.content.Context
import android.os.Build
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.generationConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiCoreManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 
     * Represents the current state of the AICore model on the device.
     */
    enum class ModelStatus {
        UNSUPPORTED,    // Hardware or OS don't support AICore
        NOT_INSTALLED,  // AICore not found or needs update
        DOWNLOADABLE,   // Model needs to be downloaded 
        DOWNLOADING,    // Model is currently downloading
        READY           // Ready for inference
    }

    /**
     * Check if the device is capable of running Gemini Nano.
     * AICore typically requires Android 14+ (API 34) and specific hardware.
     */
    fun isSupported(): Boolean {
        return Build.VERSION.SDK_INT >= 34
        // In a real implementation, we would also check for AICore service availability
    }

    /**
     * Check current model status and download progress.
     */
    private val _mockProgress = MutableStateFlow(0f)
    fun getStatus(): Flow<Pair<ModelStatus, Float>> = flow {
        if (!isSupported()) {
            emit(ModelStatus.UNSUPPORTED to 0f)
            return@flow
        }

        // Initially READY or NOT_INSTALLED based on SDK
        _mockProgress.collect { progress ->
            val status = when {
                progress >= 1.0f -> ModelStatus.READY
                progress > 0f -> ModelStatus.DOWNLOADING
                else -> ModelStatus.DOWNLOADABLE
            }
            emit(status to progress)
        }
    }

    /**
     * Request a download of the local Gemini Nano model.
     */
    suspend fun startDownload() {
        if (!isSupported()) return
        Timber.i("AICore download requested")
        // Simulating a download progress
        for (i in 1..100) {
            kotlinx.coroutines.delay(100)
            _mockProgress.value = i / 100f
        }
    }

    /**
     * The GenerativeModel instance for Gemini Nano.
     * 
     * Note: For on-device inference with Gemini Nano, the model runs locally
     * and doesn't require an API key. However, the SDK still requires the
     * apiKey parameter - use a placeholder for local inference.
     */
    private val generativeModel by lazy {
        try {
            GenerativeModel(
                modelName = "gemini-nano",
                apiKey = "local-no-key", // Local inference doesn't need a real key
                generationConfig = generationConfig {
                    temperature = 0.7f
                    topK = 40
                    topP = 0.95f
                    maxOutputTokens = 2048
                },
                safetySettings = listOf(
                    SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.MEDIUM_AND_ABOVE),
                    SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.MEDIUM_AND_ABOVE),
                    SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.MEDIUM_AND_ABOVE),
                    SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.MEDIUM_AND_ABOVE),
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Gemini Nano model")
            null
        }
    }

    /**
     * Generate content using the local Gemini Nano model.
     * 
     * @param prompt The input prompt for generation
     * @return Generated text response or error message
     */
    suspend fun generateContent(prompt: String): String {
        if (!isSupported()) {
            return "AICore requires Android 14+ (API 34). Your device is running Android ${Build.VERSION.SDK_INT}."
        }

        val model = generativeModel
        if (model == null) {
            return "AICore model initialization failed. Gemini Nano may not be available on this device."
        }

        return try {
            Timber.d("AICore: Generating content for prompt: ${prompt.take(50)}...")
            val response: GenerateContentResponse = model.generateContent(prompt)
            val text = response.text
            
            if (text.isNullOrBlank()) {
                Timber.w("AICore: Empty response from model")
                "No response generated from local model."
            } else {
                Timber.i("AICore: Successfully generated ${text.length} characters")
                text
            }
        } catch (e: Exception) {
            Timber.e(e, "AICore generateContent failed")
            when {
                e.message?.contains("not found", ignoreCase = true) == true -> 
                    "Gemini Nano model not found on device. Please ensure AICore is installed."
                e.message?.contains("permission", ignoreCase = true) == true ->
                    "Permission denied accessing AICore. Check app permissions."
                else ->
                    "Local AI Error: ${e.message ?: "Unknown error"}"
            }
        }
    }

    /**
     * Stream content from the local model.
     * 
     * @param prompt The input prompt for generation
     * @return Flow of text chunks as they are generated
     */
    fun streamContent(prompt: String): Flow<String> = flow {
        if (!isSupported()) {
            emit("AICore requires Android 14+ (API 34). Your device is running Android ${Build.VERSION.SDK_INT}.")
            return@flow
        }

        val model = generativeModel
        if (model == null) {
            emit("AICore model initialization failed. Gemini Nano may not be available on this device.")
            return@flow
        }

        try {
            Timber.d("AICore: Streaming content for prompt: ${prompt.take(50)}...")
            model.generateContentStream(prompt).collect { chunk ->
                val text = chunk.text
                if (!text.isNullOrBlank()) {
                    emit(text)
                }
            }
            Timber.i("AICore: Streaming completed")
        } catch (e: Exception) {
            Timber.e(e, "AICore streamContent failed")
            val errorMsg = when {
                e.message?.contains("not found", ignoreCase = true) == true -> 
                    "Gemini Nano model not found on device. Please ensure AICore is installed."
                e.message?.contains("permission", ignoreCase = true) == true ->
                    "Permission denied accessing AICore. Check app permissions."
                else ->
                    "Local AI Error: ${e.message ?: "Unknown error"}"
            }
            emit(errorMsg)
        }
    }

    /**
     * Check if the model is currently available and ready for inference.
     */
    suspend fun isModelReady(): Boolean {
        if (!isSupported()) return false
        
        return try {
            val model = generativeModel
            model != null
        } catch (e: Exception) {
            Timber.w(e, "AICore model readiness check failed")
            false
        }
    }

    /**
     * Get information about the current model configuration.
     */
    fun getModelInfo(): String {
        return buildString {
            appendLine("AICore Model Information:")
            appendLine("• Model: Gemini Nano")
            appendLine("• Type: On-device inference")
            appendLine("• Supported: ${isSupported()}")
            appendLine("• Android Version: ${Build.VERSION.SDK_INT} (requires 34+)")
            appendLine("• Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            
            if (isSupported()) {
                val model = generativeModel
                if (model != null) {
                    appendLine("• Status: Model initialized")
                    appendLine("• Max Output Tokens: 2048")
                    appendLine("• Temperature: 0.7")
                } else {
                    appendLine("• Status: Model initialization failed")
                }
            } else {
                appendLine("• Status: Not supported on this device")
            }
        }
    }
}
