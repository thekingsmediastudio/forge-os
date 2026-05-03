package com.forge.os.domain.companion

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase I — CRUD for the companion persona, plus the system-prompt preamble
 * builder consumed by ReActAgent in COMPANION mode.
 *
 * Persisted as plain JSON inside the sandbox at `workspace/system/persona.json`
 * so it shows up in the existing memory/inspect surfaces and can be wiped with
 * the rest of companion data later (Phase O-5).
 */
@Singleton
class PersonaManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file: File by lazy {
        File(context.filesDir, "workspace/system").apply { mkdirs() }
            .let { File(it, "persona.json") }
    }

    private val _persona = MutableStateFlow(load())
    val persona: StateFlow<Persona> = _persona

    private fun load(): Persona = try {
        if (file.exists()) json.decodeFromString(Persona.serializer(), file.readText())
        else Persona()
    } catch (e: Exception) {
        Timber.w(e, "PersonaManager: failed to read persona.json, falling back to default")
        Persona()
    }

    fun get(): Persona = _persona.value

    fun update(mutator: (Persona) -> Persona) {
        val next = mutator(_persona.value)
        _persona.value = next
        try {
            file.writeText(json.encodeToString(next))
        } catch (e: Exception) {
            Timber.e(e, "PersonaManager: failed to write persona.json")
        }
    }

    /** Replaces persona with default and rewrites file. Used by onboarding skip. */
    fun reset() = update { Persona() }

    /**
     * Phase I-2 / I-5 — produces the leading paragraph for the COMPANION
     * system prompt. AGENT mode should NOT call this.
     */
    fun buildSystemPreamble(): String {
        val p = _persona.value
        val voice = when (p.voice) {
            PersonaVoice.FORMAL  -> "Speak warmly but with measured, considered language."
            PersonaVoice.CASUAL  -> "Speak warmly and conversationally, like a close friend."
            PersonaVoice.PLAYFUL -> "Speak warmly with light humor and playful asides."
        }
        return buildString {
            appendLine("You are ${p.name} (${p.pronouns}). You are the user's AI companion in Forge OS.")
            appendLine("Core traits: ${p.coreTraits.joinToString(", ")}.")
            if (p.backstory.isNotBlank()) appendLine("Background: ${p.backstory}")
            appendLine(voice)
            appendLine()
            appendLine("Your job is to make the user feel heard before being helpful.")
            appendLine("Acknowledge feelings first; only offer suggestions if invited or asked.")
            appendLine("Use memory of past conversations to follow up naturally — do not pretend to be meeting them for the first time.")
            appendLine()
            appendLine("Boundaries:")
            p.boundaries.forEach { appendLine("- $it") }
        }.trimEnd()
    }
}
