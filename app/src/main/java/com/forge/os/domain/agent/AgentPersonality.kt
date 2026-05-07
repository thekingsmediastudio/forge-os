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
 * Manages agent personality profiles.
 *
 * Supports multiple named profiles stored under workspace/system/personalities/.
 * The active profile is workspace/system/agent_personality.json.
 * Switching profiles is instant — just overwrite the active file.
 */
@Singleton
class AgentPersonality @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val configFile: File get() = context.filesDir.resolve("workspace/system/agent_personality.json")
    private val profilesDir: File get() = context.filesDir.resolve("workspace/system/personalities").also { it.mkdirs() }

    init {
        configFile.parentFile?.mkdirs()
    }

    fun getPersonality(): PersonalityConfig = loadPersonality() ?: createDefaultPersonality()

    fun updatePersonality(config: PersonalityConfig) {
        try {
            configFile.writeText(json.encodeToString(config))
            Timber.i("Updated agent personality: ${config.name}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update personality")
        }
    }

    /** Save current personality as a named profile. */
    fun saveProfile(profileName: String, config: PersonalityConfig? = null) {
        val toSave = config ?: getPersonality()
        val safe = profileName.trim().replace(Regex("[^a-zA-Z0-9_\\- ]"), "").take(40).ifBlank { "profile" }
        val file = profilesDir.resolve("$safe.json")
        try {
            file.writeText(json.encodeToString(toSave.copy(name = profileName.trim())))
            Timber.i("Saved personality profile: $safe")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save profile $safe")
        }
    }

    /** List all saved profiles by name. */
    fun listProfiles(): List<String> {
        return profilesDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString<PersonalityConfig>(f.readText()).name }.getOrNull()
                    ?: f.nameWithoutExtension
            }
            ?.sorted() ?: emptyList()
    }

    /** Switch to a named profile. Returns true on success. */
    fun switchToProfile(profileName: String): Boolean {
        val file = profilesDir.listFiles { f -> f.extension == "json" }
            ?.firstOrNull { f ->
                runCatching { json.decodeFromString<PersonalityConfig>(f.readText()).name }
                    .getOrNull()?.equals(profileName, ignoreCase = true) == true
                    || f.nameWithoutExtension.equals(profileName, ignoreCase = true)
            } ?: return false
        return try {
            val profile = json.decodeFromString<PersonalityConfig>(file.readText())
            updatePersonality(profile)
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to switch to profile $profileName")
            false
        }
    }

    /**
     * Returns a personality suffix to append to the base agent system prompt.
     * This keeps all 36 agent rules intact while layering the personality on top.
     */
    fun getPersonalitySuffix(): String {
        val p = getPersonality()
        // Only emit non-empty sections to avoid cluttering the prompt
        if (p.name == "Forge" && p.systemPrompt.isBlank() && p.traits.isEmpty()
            && p.communicationStyle.isBlank() && p.customInstructions.isBlank()) return ""
        return buildString {
            appendLine("── ACTIVE PERSONALITY: ${p.name} ──")
            if (p.systemPrompt.isNotBlank()) {
                appendLine(p.systemPrompt)
                appendLine()
            }
            if (p.traits.isNotEmpty()) {
                appendLine("PERSONALITY TRAITS:")
                p.traits.forEach { appendLine("• $it") }
                appendLine()
            }
            if (p.communicationStyle.isNotBlank()) {
                appendLine("COMMUNICATION STYLE:")
                appendLine(p.communicationStyle)
                appendLine()
            }
            if (p.preferences.isNotEmpty()) {
                appendLine("PREFERENCES:")
                p.preferences.forEach { (k, v) -> appendLine("• $k: $v") }
                appendLine()
            }
            if (p.customInstructions.isNotBlank()) {
                appendLine("CUSTOM INSTRUCTIONS:")
                appendLine(p.customInstructions)
            }
        }.trimEnd()
    }

    /**
     * Full system prompt — base rules replaced by personality.
     * Used when the caller explicitly wants the personality to own the entire prompt
     * (e.g. a dedicated persona mode). For normal agent use, prefer [getPersonalitySuffix].
     */
    fun getSystemPrompt(): String {
        val p = getPersonality()
        return buildString {
            appendLine("You are ${p.name}.")
            appendLine()
            if (p.systemPrompt.isNotBlank()) {
                appendLine(p.systemPrompt)
                appendLine()
            }
            if (p.traits.isNotEmpty()) {
                appendLine("PERSONALITY TRAITS:")
                p.traits.forEach { appendLine("• $it") }
                appendLine()
            }
            if (p.communicationStyle.isNotBlank()) {
                appendLine("COMMUNICATION STYLE:")
                appendLine(p.communicationStyle)
                appendLine()
            }
            if (p.preferences.isNotEmpty()) {
                appendLine("PREFERENCES:")
                p.preferences.forEach { (k, v) -> appendLine("• $k: $v") }
            }
            if (p.customInstructions.isNotBlank()) {
                appendLine()
                appendLine(p.customInstructions)
            }
        }
    }

    fun getPersonalitySummary(): String {
        val p = getPersonality()
        return buildString {
            appendLine("🤖 Active personality: ${p.name}")
            if (p.description.isNotBlank()) appendLine("  ${p.description}")
            if (p.traits.isNotEmpty()) appendLine("  Traits: ${p.traits.joinToString(", ")}")
            val profiles = listProfiles()
            if (profiles.isNotEmpty()) appendLine("  Saved profiles: ${profiles.joinToString(", ")}")
        }.trimEnd()
    }

    /** Reset the active personality to the built-in Forge defaults. */
    fun resetToDefault(): PersonalityConfig {
        val default = buildDefaultPersonality()
        updatePersonality(default)
        // Overwrite the saved default profile so it stays in sync
        saveProfile("Forge (Default)", default)
        return default
    }

    private fun createDefaultPersonality(): PersonalityConfig {
        val default = buildDefaultPersonality()
        updatePersonality(default)
        saveProfile("Forge (Default)", default)
        return default
    }

    private fun buildDefaultPersonality() = PersonalityConfig(
        name = "Forge",
        description = "Your AI development assistant",
        systemPrompt = "",
        traits = listOf("Helpful and supportive", "Technical and knowledgeable", "Direct and concise"),
        communicationStyle = "Clear, technical language. Provide code examples when relevant. Be warm but professional.",
        preferences = mapOf("verbosity" to "concise", "code_style" to "follow_project_conventions"),
        customInstructions = ""
    )

    private fun loadPersonality(): PersonalityConfig? = runCatching {
        if (!configFile.exists()) return null
        json.decodeFromString<PersonalityConfig>(configFile.readText())
    }.getOrNull()
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
