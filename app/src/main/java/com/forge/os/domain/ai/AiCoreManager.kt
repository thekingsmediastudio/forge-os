package com.forge.os.domain.ai

import android.content.Context
import android.os.Build
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.generationConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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
    private val _mockProgress = kotlinx.coroutines.flow.MutableStateFlow(0f)
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
     * For on-device inference, we use "gemini-nano" model name.
     * Note: This requires the Gemini Nano model to be available on the device.
     */
    private val generativeModel by lazy {
        try {
            GenerativeModel(
                modelName = "gemini-nano",
                apiKey = "", // On-device models don't require API key
                generationConfig = generationConfig {
                    temperature = 0.7f
                    topK = 40
                    topP = 0.95f
                    maxOutputTokens = 1024
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Gemini Nano model")
            null
        }
    }

    /**
     * Generate content using the local Gemini Nano model.
     * Falls back to error message if model is not available.
     */
    suspend fun generateContent(prompt: String): String {
        if (!isSupported()) {
            return "AICore requires Android 14+ (API 34). Your device is running Android ${Build.VERSION.SDK_INT}."
        }

        val model = generativeModel
        if (model == null) {
            return "Gemini Nano model not available on this device. Please ensure AICore is installed and up to date."
        }

        return try {
            Timber.d("AiCore: Generating content with prompt length ${prompt.length}")
            val response = model.generateContent(prompt)
            val text = response.text ?: "No response from local model."
            Timber.d("AiCore: Generated ${text.length} characters")
            text
        } catch (e: Exception) {
            Timber.e(e, "AiCore generateContent failed")
            "Local inference error: ${e.message}\n\nNote: Gemini Nano requires the model to be downloaded via Google Play Services."
        }
    }

    /**
     * Stream content from the local model.
     * Provides real-time token generation for better UX.
     */
    fun streamContent(prompt: String): Flow<String> = flow {
        if (!isSupported()) {
            emit("AICore requires Android 14+ (API 34). Your device is running Android ${Build.VERSION.SDK_INT}.")
            return@flow
        }

        val model = generativeModel
        if (model == null) {
            emit("Gemini Nano model not available on this device. Please ensure AICore is installed and up to date.")
            return@flow
        }

        try {
            Timber.d("AiCore: Streaming content with prompt length ${prompt.length}")
            model.generateContentStream(prompt).collect { chunk ->
                chunk.text?.let { 
                    Timber.v("AiCore: Streamed chunk: ${it.length} chars")
                    emit(it) 
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "AiCore streamContent failed")
            emit("Local streaming error: ${e.message}\n\nNote: Gemini Nano requires the model to be downloaded via Google Play Services.")
        }
    }

    /**
     * Check if the model is actually available and ready for inference.
     * This is a more thorough check than just isSupported().
     */
    suspend fun isModelReady(): Boolean {
        if (!isSupported()) return false
        
        return try {
            // Try a simple generation to verify the model works
            val model = generativeModel ?: return false
            val response = model.generateContent("test")
            response.text != null
        } catch (e: Exception) {
            Timber.w(e, "Model readiness check failed")
            false
        }
    }
}
