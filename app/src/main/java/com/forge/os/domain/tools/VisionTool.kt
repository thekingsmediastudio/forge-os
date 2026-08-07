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
     *  4. On empty/failed response: retry once with the next vision-capable spec
     *
     * The result is prefixed with a `[via provider/model]` routing line so it's
     * always visible which model actually answered — a thin/ignored-prompt
     * answer usually means a weak auto-routed model, which the explicit
     * `model` param can then override.
     *
     * @param path   Relative path inside the workspace (e.g. "uploads/photo.jpg")
     * @param prompt The question or instruction for the vision model
     * @param model  Optional explicit model ID to use
     */
    suspend fun analyze(path: String, prompt: String, model: String? = null): String {
        val workspace = File(context.filesDir, "workspace")
        // Resolve the path robustly — agents pass workspace-relative paths,
        // but also commonly absolute file paths or bare filenames that live
        // under uploads/. Try each interpretation in order.
        val candidates = buildList {
            add(File(workspace, path))                                   // workspace-relative
            if (!path.startsWith("uploads/")) add(File(workspace, "uploads/$path"))
            add(File(path))                                              // absolute
        }
        val tried = candidates.map { it.absolutePath }.distinct()
        val file = candidates.firstOrNull { it.exists() && it.isFile }
        if (file == null) {
            Timber.w("VisionTool: image not found for path='$path'; tried: $tried")
            return "Error: Image not found for path '$path'. Tried: ${tried.joinToString()}"
        }
        Timber.i("VisionTool: analyzing ${file.absolutePath} (${file.length()} bytes)")

        // Keep raw bytes when the format is already provider-supported so the
        // MIME label always matches the payload. Only re-encode (to JPEG) when
        // the source is something vision APIs typically reject (bmp, heic…).
        val ext = file.extension.lowercase()
        val rawOk = ext in setOf("jpg", "jpeg", "png", "webp", "gif") && file.length() <= 20L * 1024 * 1024
        val (base64, mimeType) = try {
            if (rawOk) {
                val mime = when (ext) {
                    "png"  -> "image/png"
                    "webp" -> "image/webp"
                    "gif"  -> "image/gif"
                    else   -> "image/jpeg"
                }
                Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) to mime
            } else {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    ?: return "Error: Could not decode image at $path — unsupported format or corrupt file"
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) to "image/jpeg"
            }
        } catch (e: Exception) {
            return "Error: Failed to process image: ${e.message}"
        }
        val dataUrl = "data:$mimeType;base64,$base64"

        val available = apiManager.availableSpecsExpanded()

        // Resolve which spec to use
        val spec: ProviderSpec? = when {
            // 1. Explicit model requested
            model != null -> {
                available.firstOrNull { it.effectiveModel == model }
                    ?: return "Error: Model '$model' not found. Check your API keys."
            }
            // 2-3. Mode.VISION routing (config override, then auto-detect)
            else -> apiManager.pickProviderForMode(Mode.VISION)
                ?: available.firstOrNull { isVisionCapable(it) }
        }

        if (spec == null) {
            return buildString {
                append("Error: No vision-capable model found. ")
                append("Add an API key for one of: GPT-4o (OpenAI), Claude 3+ (Anthropic), or Gemini (Google Gemini). ")
                append("You can also set a dedicated vision model in Settings → Model Routing → Vision.")
            }
        }

        // Guard: if the routed spec isn't vision-capable (e.g. a text-only model
        // pinned in Model Routing → Vision), the provider silently drops the
        // image part and answers from the text prompt alone — which looks like
        // "the image was never sent". Prefer a recognised vision spec instead.
        val effectiveSpec = if (isVisionCapable(spec)) {
            spec
        } else {
            Timber.w("VisionTool: routed spec ${specLabel(spec)} is not vision-capable; looking for alternative")
            available.firstOrNull { it != spec && isVisionCapable(it) }
                ?: return "Error: '${spec.effectiveModel}' is not a vision model and no vision-capable alternative was found. " +
                        "Set a vision model in Settings → Model Routing → Vision (e.g. GPT-4o, Claude 3+, Gemini)."
        }

        Timber.i("VisionTool: sending image (${base64.length / 1024} KB base64) to ${specLabel(effectiveSpec)}")
        val messages = listOf(
            ApiMessage(
                role = "user",
                contentParts = listOf(
                    ContentPart(type = "text", text = prompt),
                    ContentPart(type = "image_url", imageUrl = ImageUrl(url = dataUrl))
                )
            )
        )
        // The instruction lives only in the user turn next to the image; a
        // short system prompt keeps weak chat models from defaulting to a
        // generic caption instead of following the requested analysis.
        val systemPrompt = "You are a precise image-analysis engine. " +
            "Follow the user's instruction about the image exactly; do not default to a generic description."

        val first = callVision(effectiveSpec, messages, systemPrompt)
        if (first != null) return first

        // 4. Retry once with the next vision-capable spec before giving up
        val fallback = available.firstOrNull { it != effectiveSpec && isVisionCapable(it) }
        if (fallback != null) {
            Timber.i("VisionTool: ${specLabel(effectiveSpec)} gave no usable answer; retrying with ${specLabel(fallback)}")
            callVision(fallback, messages, systemPrompt)?.let { return it }
        }
        return "Error: Vision model(s) returned no text. Try passing an explicit model (e.g. model=gpt-4o)."
    }

    private suspend fun callVision(
        spec: ProviderSpec,
        messages: List<ApiMessage>,
        systemPrompt: String,
    ): String? = try {
        val resp = apiManager.chatWithFallback(
            messages = messages,
            systemPrompt = systemPrompt,
            spec = spec,
            mode = Mode.VISION,
        )
        val text = resp.content?.takeIf { it.isNotBlank() }
        if (text == null) null else "[via ${specLabel(spec)}]\n$text"
    } catch (e: Exception) {
        Timber.w(e, "VisionTool: ${specLabel(spec)} failed")
        null
    }

    private fun specLabel(spec: ProviderSpec): String = when (spec) {
        is ProviderSpec.Builtin -> "${spec.provider.displayName}/${spec.effectiveModel}"
        is ProviderSpec.Custom  -> "custom:${spec.endpoint.name}/${spec.effectiveModel}"
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
            model.contains("pixtral") || model.contains("gpt-4o") || model.contains("gpt-4.1") ||
            model.contains("gpt-5") || model.contains("claude-3") || model.contains("claude-4") ||
            model.contains("claude-opus") || model.contains("claude-sonnet") || 
            model.contains("claude-haiku") || model.contains("gemini") || 
            model.contains("gpt-4-turbo") || model.contains("qwen2.5vl") || 
            model.contains("qwen3-vl") || model.contains("-vl") || 
            model.contains("minicpm") || model.contains("moondream") || 
            model.contains("gemma3") || model.contains("mistral-small3") ||
            model.contains("llama3.2-vision") || model.contains("granite") ||
            model.contains("deepseek-ocr") || model.contains("grok-vision") ||
            model.contains("grok-2-vision")) return true

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
