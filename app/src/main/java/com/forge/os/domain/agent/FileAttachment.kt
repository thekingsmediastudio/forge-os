package com.forge.os.domain.agent

/**
 * Represents a file attachment in a chat message.
 * Supports images, videos, audio, and documents.
 */
data class FileAttachment(
    val filePath: String,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val base64Data: String? = null, // Only for images (for vision models)
    /** Text-based context (browser tab URL/content, conversation excerpt).
     *  When non-null, this is a virtual context attachment, not a physical file. */
    val contextText: String? = null,
) {
    fun isImage() = mimeType.startsWith("image/")
    fun isVideo() = mimeType.startsWith("video/")
    fun isAudio() = mimeType.startsWith("audio/")
    fun isDocument() = !isImage() && !isVideo() && !isAudio() && contextText == null
    fun isContext() = contextText != null
    
    /**
     * Convert to a data URL for API transmission (images only).
     */
    fun toDataUrl(): String? = base64Data?.let { "data:$mimeType;base64,$it" }
    
    /**
     * Get a human-readable file size string.
     */
    fun formattedSize(): String {
        return when {
            fileSize < 1024 -> "$fileSize B"
            fileSize < 1024 * 1024 -> "${fileSize / 1024} KB"
            else -> "${fileSize / (1024 * 1024)} MB"
        }
    }
    
    companion object {
        const val MAX_FILE_SIZE = 20 * 1024 * 1024L // 20MB
    }
}

// Type alias for backward compatibility
typealias ImageAttachment = FileAttachment
