package com.forge.os.domain.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.forge.os.data.api.AiApiManager
import com.forge.os.data.api.ApiMessage
import com.forge.os.data.api.ContentPart
import com.forge.os.data.api.ImageUrl
import com.forge.os.domain.companion.Mode
import com.forge.os.domain.security.ApiKeyProvider
import com.forge.os.domain.security.ProviderSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VisionTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiManager: AiApiManager
) {
    /**
     * Analyzes an image file from the workspace.
     *
     * Model selection priority:
     *  1. Explicit [model] param — find a matching spec by model ID
     *  2. User-configured vision route (Settings → Model Routing → Vision)
     *  3. Any available spec whose model ID is recognised as vision-capable
     *     by [isVisionCapable] — covers name-based and provider-based detection
     *  4. Any spec from a provider known to support vision (OpenAI, Anthropic, Google)
     *
     * @param path   Relative path inside the workspace (e.g. "uploads/photo.jpg")
     * @param prompt The question or instruction for the vision model
     * @param model  Optional explicit model ID to use
     */
    suspend fun analyze(path: String, prompt: String, model: String? = null): String {
        val workspace = File(context.filesDir, "workspace")
        val file = File(workspace, path)
        if (!file.exists()) return "Error: File not found at workspace/$path"

        val base64 = try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                ?: return "Error: Could not decode image at $path — unsupported format or corrupt file"
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            return "Error: Failed to process image: ${e.message}"
        }

        val mimeType = when (file.extension.lowercase()) {
            "png"  -> "image/png"
            "webp" -> "image/webp"
            "gif"  -> "image/gif"
            else   -> "image/jpeg"
        }
        val dataUrl = "data:$mimeType;base64,$base64"

        // Resolve which spec to use
        val spec: ProviderSpec? = when {
            // 1. Explicit model requested
            model != null -> {
                apiManager.availableSpecsExpanded().firstOrNull { it.effectiveModel == model }
                    ?: return "Error: Model '$model' not found. Check your API keys."
            }
            // 2-4. Let Mode.VISION routing handle it (checks config override, then auto-detects)
            else -> null
        }

        // If no explicit spec, verify that Mode.VISION can find something before calling
        if (spec == null) {
            val available = apiManager.availableSpecsExpanded()
            val hasVision = available.any { isVisionCapable(it) }
            if (!hasVision) {
                return buildString {
                    append("Error: No vision-capable model found. ")
                    append("Add an API key for one of: GPT-4o (OpenAI), Claude 3+ (Anthropic), or Gemini (Google Gemini). ")
                    append("You can also set a dedicated vision model in Settings → Model Routing → Vision.")
                }
            }
        }

        val messages = listOf(
            ApiMessage(
                role = "user",
                contentParts = listOf(
                    ContentPart(type = "text", text = prompt),
                    ContentPart(type = "image_url", imageUrl = ImageUrl(url = dataUrl))
                )
            )
        )

        return try {
            val resp = apiManager.chatWithFallback(
                messages = messages,
                spec = spec,
                mode = Mode.VISION,
            )
            resp.content ?: "Error: Vision model returned no text."
        } catch (e: Exception) {
            Timber.w(e, "VisionTool: analysis failed")
            "Error: Vision analysis failed — ${e.message}"
        }
    }

    /**
     * Returns true if [spec] is likely capable of processing images.
     * Uses multiple signals:
     *  - Model name keywords (gpt-4o, claude-3, gemini, vision, llava, etc.)
     *  - Provider identity (OpenAI, Anthropic, Google all support vision on modern models)
     */
    private fun isVisionCapable(spec: ProviderSpec): Boolean {
        val model = spec.effectiveModel.lowercase()

        // Name-based detection
        if (model.contains("vision") || model.contains("llava") || model.contains("bakllava") ||
            model.contains("pixtral") || model.contains("gpt-4o") ||
            model.contains("claude-3") || model.contains("claude-opus") ||
            model.contains("claude-sonnet") || model.contains("claude-haiku") ||
            model.contains("gemini") || model.contains("gpt-4-turbo")) return true

        // Provider-based detection — modern flagship models from these providers support vision
        if (spec is ProviderSpec.Builtin) {
            return spec.provider in setOf(
                ApiKeyProvider.OPENAI,
                ApiKeyProvider.ANTHROPIC,
                ApiKeyProvider.GEMINI,
            )
        }

        return false
    }
}
