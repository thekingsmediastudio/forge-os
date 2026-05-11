package com.forge.os.domain.companion

import kotlinx.serialization.Serializable

@Serializable
enum class PersonaVoice { FORMAL, CASUAL, PLAYFUL }

/**
 * Phase I — the companion's identity. Stored as JSON at workspace/system/persona.json.
 * Injected as the leading paragraph of every COMPANION-mode system prompt.
 */
@Serializable
data class Persona(
    val name: String = "Forge",
    val pronouns: String = "they/them",
    val voice: PersonaVoice = PersonaVoice.CASUAL,
    val coreTraits: List<String> = listOf("curious", "warm", "candid"),
    val backstory: String = "",
    val boundaries: List<String> = listOf(
        "I am an AI companion, not a substitute for human connection.",
        "I do not roleplay romantic or sexual relationships.",
        "If you are in crisis, I will share crisis-line resources."
    ),
)
