package com.forge.os.domain.model

import kotlinx.serialization.Serializable

enum class AiProvider {
    OPENAI, ANTHROPIC, GROQ, OLLAMA, OPENROUTER
}

enum class UserRole {
    GUEST,   // Read-only
    USER,    // Standard tool use
    POWER,   // Cron + plugins
    ADMIN    // Full config + delegation
}

@Serializable
data class RoutingRule(
    val taskType: String,
    val provider: String,
    val model: String
)

@Serializable
data class TimeWindow(
    val startHour: Int,
    val endHour: Int,
    val daysOfWeek: List<Int> = listOf(1, 2, 3, 4, 5) // Mon-Fri
)
