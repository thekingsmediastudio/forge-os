package com.forge.os.domain.proactive

import android.content.Context
import com.forge.os.data.api.AiApiManager
import com.forge.os.data.api.ApiMessage
import com.forge.os.domain.security.ToolAuditLog
import com.forge.os.domain.security.ToolAuditEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SynthesizedSkill(
    val id: String,
    val name: String,
    val description: String,
    val tool_name: String,
    val tool_description: String,
    val python_code: String,
    val params: Map<String, String> = emptyMap()
)

@Singleton
class SkillSynthesizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auditLog: ToolAuditLog,
    private val aiApiManager: AiApiManager,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val synthesizedFile = File(context.filesDir, "workspace/system/synthesized_skills.txt")

    suspend fun findAndSynthesize(): SynthesizedSkill? {
        val candidates = auditLog.entries.value
            .filter { it.toolName == "python_run" && it.success && it.args.length > 100 }
            .take(5)

        for (candidate in candidates) {
            if (isAlreadySynthesized(candidate.id)) continue

            val skill = synthesizeFromAudit(candidate)
            if (skill != null) {
                markAsSynthesized(candidate.id)
                return skill
            }
        }
        return null
    }

    private suspend fun synthesizeFromAudit(entry: ToolAuditEntry): SynthesizedSkill? {
        Timber.d("Synthesizing skill from audit entry: ${entry.id}")
        
        val prompt = """
            I am a Forge OS Skill Synthesizer. I noticed the following Python execution was successful:
            
            CODE:
            ${entry.args}
            
            RESULT PREVIEW:
            ${entry.outputPreview}
            
            Task: Generalize this one-off code into a reusable Forge Plugin.
            The plugin should be a general-purpose tool that takes arguments.
            
            Output ONLY a JSON object with this structure:
            {
              "id": "lowercase_id",
              "name": "Human Name",
              "description": "What the plugin does",
              "tool_name": "callable_tool_name",
              "tool_description": "Description for the agent",
              "python_code": "The generalized code. Use 'args' dict for inputs.",
              "params": { "arg_name": "type:description" }
            }
        """.trimIndent()

        return try {
            val response = aiApiManager.chatWithFallback(
                messages = listOf(ApiMessage(role = "user", content = prompt)),
                systemPrompt = "You are a professional software architect. Output ONLY valid JSON.",
                mode = com.forge.os.domain.companion.Mode.SYSTEM
            )
            
            val content = response.content ?: return null
            val jsonStart = content.indexOf("{")
            val jsonEnd = content.lastIndexOf("}") + 1
            if (jsonStart < 0 || jsonEnd <= jsonStart) return null
            
            val skillJson = content.substring(jsonStart, jsonEnd)
            json.decodeFromString<SynthesizedSkill>(skillJson)
        } catch (e: Exception) {
            Timber.e(e, "Failed to synthesize skill")
            null
        }
    }

    private fun isAlreadySynthesized(id: String): Boolean {
        if (!synthesizedFile.exists()) return false
        return synthesizedFile.readLines().contains(id)
    }

    private fun markAsSynthesized(id: String) {
        synthesizedFile.appendText("$id\n")
    }
}
