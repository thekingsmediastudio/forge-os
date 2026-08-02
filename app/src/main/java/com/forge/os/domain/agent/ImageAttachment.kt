package com.forge.os.domain.agent

/**
 * Represents an image attachment in a chat message.
 */
data class ImageAttachment(
    val filePath: String,
    val mimeType: String,
    val base64Data: String,
) {
    /**
     * Convert to a data URL for API transmission.
     */
    fun toDataUrl(): String = "data:$mimeType;base64,$base64Data"
}
