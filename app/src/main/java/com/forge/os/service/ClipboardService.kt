package com.forge.os.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import com.forge.os.data.api.ClipboardUpdateRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages clipboard operations for cross-device clipboard synchronization.
 * Handles:
 * - Updating Android clipboard from desktop
 * - Text, image, and file clipboard content types
 * - Thread-safe clipboard access
 */
@Singleton
class ClipboardService @Inject constructor(
    private val context: Context,
    private val broadcaster: EventBroadcaster
) {
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mutex = Mutex()

    /** Suppress self-echo: our own setPrimaryClip fires the listener. */
    @Volatile private var suppressUntil = 0L

    private val changeListener = ClipboardManager.OnPrimaryClipChangedListener {
        val now = System.currentTimeMillis()
        if (now < suppressUntil) return@OnPrimaryClipChangedListener
        val item = clipboardManager.primaryClip?.getItemAt(0)
            ?: return@OnPrimaryClipChangedListener
        val text = item.text?.toString()
        if (!text.isNullOrBlank()) {
            broadcaster.emitClipboard("text", text)
        }
    }

    init {
        // Monitor Android clipboard changes and forward them to the desktop (Task 10.3).
        clipboardManager.addPrimaryClipChangedListener(changeListener)
    }
    
    /**
     * Updates the Android clipboard with content from desktop.
     * 
     * @param request Clipboard update request with type and content
     * @return true if clipboard was successfully updated
     */
    suspend fun updateClipboard(request: ClipboardUpdateRequest): Boolean {
        return mutex.withLock {
            try {
                when (request.type) {
                    "text" -> {
                        val content = request.content
                        if (content.isNullOrBlank()) {
                            Timber.w("ClipboardService: Text content is null or blank")
                            return@withLock false
                        }
                        
                        suppressUntil = System.currentTimeMillis() + 2000
                        val clip = ClipData.newPlainText("Desktop Clipboard", content)
                        clipboardManager.setPrimaryClip(clip)
                        
                        Timber.d("ClipboardService: Updated clipboard with text (${content.length} chars)")
                        true
                    }
                    
                    "image" -> {
                        val imageData = request.imageData
                        if (imageData.isNullOrBlank()) {
                            Timber.w("ClipboardService: Image data is null or blank")
                            return@withLock false
                        }
                        
                        try {
                            // Decode Base64 image
                            val imageBytes = Base64.decode(imageData, Base64.DEFAULT)
                            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                            
                            if (bitmap == null) {
                                Timber.w("ClipboardService: Failed to decode image")
                                return@withLock false
                            }
                            
                                                        // Real image clipboard: write the PNG to cache and expose it
                            // through the FileProvider (Task 10.2), so any app can paste it.
                            suppressUntil = System.currentTimeMillis() + 2000
                            val dir = File(context.cacheDir, "clipboard_images").apply { mkdirs() }
                            val imageFile = File(dir, "forged_clipboard_${System.currentTimeMillis()}.png")
                            FileOutputStream(imageFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                imageFile
                            )
                            val imageClip = ClipData.newUri(context.contentResolver, "image/png", uri)
                            clipboardManager.setPrimaryClip(imageClip)

                            Timber.d("ClipboardService: Updated clipboard with image (${imageBytes.size} bytes)")
                            true
                        } catch (e: Exception) {
                            Timber.e(e, "ClipboardService: Failed to process image")
                            false
                        }
                    }
                    
                    "file" -> {
                        val fileName = request.fileName
                        if (fileName.isNullOrBlank()) {
                            Timber.w("ClipboardService: File name is null or blank")
                            return@withLock false
                        }
                        
                        // For file clipboard, we'll store the file name as text
                        // Full implementation would need file URI handling
                            suppressUntil = System.currentTimeMillis() + 2000
                        val clip = ClipData.newPlainText(
                            "File Clipboard",
                            "File: $fileName"
                        )
                        clipboardManager.setPrimaryClip(clip)
                        
                        Timber.d("ClipboardService: Updated clipboard with file reference: $fileName")
                        true
                    }
                    
                    else -> {
                        Timber.w("ClipboardService: Unsupported clipboard type: ${request.type}")
                        false
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "ClipboardService: Failed to update clipboard")
                false
            }
        }
    }
    
    /**
     * Gets the current clipboard content as text.
     * Returns null if clipboard is empty or doesn't contain text.
     */
    suspend fun getClipboardText(): String? {
        return mutex.withLock {
            try {
                val clipData = clipboardManager.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val item = clipData.getItemAt(0)
                    item?.text?.toString()
                } else {
                    null
                }
            } catch (e: Exception) {
                Timber.e(e, "ClipboardService: Failed to read clipboard")
                null
            }
        }
    }
    
    /**
     * Checks if clipboard contains text content.
     */
    fun hasText(): Boolean {
        return try {
            clipboardManager.hasPrimaryClip() &&
                clipboardManager.primaryClipDescription?.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN) == true
        } catch (e: Exception) {
            Timber.e(e, "ClipboardService: Failed to check clipboard")
            false
        }
    }
}
