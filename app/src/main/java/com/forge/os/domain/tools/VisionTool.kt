package com.forge.os.domain.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.forge.os.data.api.AiApiManager
import com.forge.os.data.api.ApiMessage
import com.forge.os.data.api.ContentPart
import com.forge.os.data.api.ImageUrl
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
     * @param path Relative path to the image in the workspace (e.g. "uploads/photo.jpg")
     * @param prompt The question or instruction for the vision model.
     * @param model Optional specific vision model ID to use.
     */
    suspend fun analyze(path: String, prompt: String, model: String? = null): String {
        val workspace = File(context.filesDir, "workspace")
        val file = File(workspace, path)
        if (!file.exists()) return "Error: File not found at $path"

        val base64 = try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            val bytes = out.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            return "Error: Failed to process image: ${e.message}"
        }

        val mimeType = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }

        val dataUrl = "data:$mimeType;base64,$base64"
        
        // Pick a vision-capable model if none provided
        val spec = if (model != null) {
            apiManager.availableSpecsExpanded().firstOrNull { it.effectiveModel == model }
        } else {
            // Find any builtin vision model (GPT-4o, Claude 3, etc)
            apiManager.availableSpecsExpanded().firstOrNull { apiManager.isVisionModel(it.effectiveModel) }
        } ?: return "Error: No vision-capable models available. Add a key for GPT-4o or Claude 3."

        return try {
            val resp = apiManager.chat(
                messages = listOf(
                    ApiMessage(
                        role = "user",
                        contentParts = listOf(
                            ContentPart(type = "text", text = prompt),
                            ContentPart(type = "image_url", imageUrl = ImageUrl(url = dataUrl))
                        )
                    )
                ),
                spec = spec
            )
            resp.content ?: "Error: Vision model returned no text."
        } catch (e: Exception) {
            "Error: Vision analysis failed: ${e.message}"
        }
    }
}
