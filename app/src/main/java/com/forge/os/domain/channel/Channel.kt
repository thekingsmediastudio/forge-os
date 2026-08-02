package com.forge.os.domain.channel

import java.util.UUID

/**
 * Represents a memory channel for scoped AI conversations.
 * Channels allow users to separate contexts (e.g., Work, Personal, Projects).
 */
data class Channel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val icon: String = "💬", // Emoji icon
    val color: String = "#FF6B3D", // Hex color
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** Default channel for users who don't use channels */
        val GENERAL = Channel(
            id = "general",
            name = "General",
            icon = "💬",
            color = "#FF6B3D",
            isDefault = true
        )
    }
}

/**
 * Channel types for different contexts
 */
enum class ChannelType(val icon: String, val defaultName: String) {
    GENERAL("💬", "General"),
    WORK("💼", "Work"),
    PERSONAL("🏠", "Personal"),
    PROJECT("🚀", "Project"),
    COMPANION("❤️", "Companion"),
    CUSTOM("✨", "Custom")
}
