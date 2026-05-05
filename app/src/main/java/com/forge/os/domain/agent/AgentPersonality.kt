package com.forge.os.domain.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages agent personality and configuration.
 * 
 * Allows users to:
 * - Set agent name and identity
 * - Customize system prompt
 * - Configure behavior and preferences
 * - Save and load personality profiles
 */
@Singleton
class AgentPersonality @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val configFile: File get() = context.filesDir.resolve("workspace/system/agent_personality.json")

    init {
        configFile.parentFile?.mkdirs()
    }

    /**
     * Get the current agent personality configuration.
     */
    fun getPersonality(): PersonalityConfig {
        return loadPersonality() ?: createDefaultPersonality()
    }

    /**
     * Update agent personality.
     */
    fun updatePersonality(config: PersonalityConfig) {
        try {
            configFile.writeText(json.encodeToString(config))
            Timber.i("Updated agent personality: ${config.name}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update personality")
        }
    }

    /**
     * Get the system prompt for the agent.
     * This is what tells the agent how to behave.
     */
    fun getSystemPrompt(): String {
        val personality = getPersonality()
        
        return buildString {
            appendLine("You are ${personality.name}.")
            appendLine()
            appendLine(personality.systemPrompt)
            appendLine()
            appendLine("PERSONALITY TRAITS:")
            personality.traits.forEach { trait ->
                appendLine("• $trait")
            }
            appendLine()
            appendLine("COMMUNICATION STYLE:")
            appendLine(personality.communicationStyle)
            appendLine()
            appendLine("PREFERENCES:")
            personality.preferences.forEach { (key, value) ->
                appendLine("• $key: $value")
            }
        }
    }

    /**
     * Create a default personality.
     */
    private fun createDefaultPersonality(): PersonalityConfig {
        val default = PersonalityConfig(
            name = "Forge",
            description = "Your AI development assistant",
            systemPrompt = """
                You are Forge, an AI development assistant designed to help developers build, test, and deploy projects.
                
                Your core responsibilities:
                1. Help users create and manage projects
                2. Execute code and run tests
                3. Provide technical guidance and solutions
                4. Learn from past interactions and remember user preferences
                5. Maintain context across sessions
                
                Always:
                - Be direct and concise in your explanations
                - Show your reasoning when making decisions
                - Ask for clarification when needed
                - Remember what the user has told you
                - Suggest improvements based on best practices
            """.trimIndent(),
            traits = listOf(
                "Helpful and supportive",
                "Technical and knowledgeable",
                "Direct and concise",
                "Proactive in suggesting improvements",
                "Respectful of user preferences"
            ),
            communicationStyle = """
                • Use clear, technical language
                • Provide code examples when relevant
                • Explain your reasoning
                • Be warm but professional
                • Adapt to the user's communication style
            """.trimIndent(),
            preferences = mapOf(
                "language" to "English",
                "verbosity" to "concise",
                "code_style" to "follow_project_conventions",
                "error_handling" to "detailed_explanations",
                "learning_mode" to "enabled"
            ),
            customInstructions = ""
        )
        
        updatePersonality(default)
        return default
    }

    /**
     * Load personality from disk.
     */
    private fun loadPersonality(): PersonalityConfig? {
        return runCatching {
            if (!configFile.exists()) return null
            val content = configFile.readText()
            json.decodeFromString<PersonalityConfig>(content)
        }.getOrNull()
    }

    /**
     * Get a formatted personality summary for display.
     */
    fun getPersonalitySummary(): String {
        val personality = getPersonality()
        
        return buildString {
            appendLine("🤖 Agent Personality Configuration")
            appendLine()
            appendLine("Name: ${personality.name}")
            appendLine("Description: ${personality.description}")
            appendLine()
            appendLine("Traits:")
            personality.traits.forEach { appendLine("  • $trait") }
            appendLine()
            appendLine("Communication Style:")
            appendLine(personality.communicationStyle)
            appendLine()
            appendLine("Preferences:")
            personality.preferences.forEach { (k, v) ->
                appendLine("  • $k: $v")
            }
            if (personality.customInstructions.isNotBlank()) {
                appendLine()
                appendLine("Custom Instructions:")
                appendLine(personality.customInstructions)
            }
        }
    }
}

@Serializable
data class PersonalityConfig(
    val name: String = "Forge",
    val description: String = "Your AI development assistant",
    val systemPrompt: String = "",
    val traits: List<String> = emptyList(),
    val communicationStyle: String = "",
    val preferences: Map<String, String> = emptyMap(),
    val customInstructions: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis()
)
