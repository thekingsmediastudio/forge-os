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
    // Enhanced Integration: Connect with learning systems
    private val reflectionManager: com.forge.os.domain.agent.ReflectionManager,
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
                
                // Enhanced Integration: Record skill synthesis pattern in ReflectionManager
                try {
                    reflectionManager.recordPattern(
                        pattern = "Skill synthesis from Python execution",
                        description = "Successfully synthesized '${skill.name}' from repeated Python pattern: ${skill.description}",
                        applicableTo = listOf("python", "automation", "skill_creation", skill.tool_name),
                        tags = listOf("skill_synthesis", "python_pattern", "automation_discovery", "reusable_code")
                    )
                    
                    // Record the successful synthesis execution
                    reflectionManager.recordExecution(
                        taskId = "skill_synthesis_${skill.id}",
                        goal = "Synthesize reusable skill from Python execution pattern",
                        steps = listOf(
                            com.forge.os.domain.agent.ExecutionStep(
                                stepNumber = 1,
                                action = "Analyze Python execution pattern",
                                tool = "skill_synthesizer",
                                args = candidate.args.take(200),
                                result = "Identified reusable pattern: ${skill.name}",
                                duration = 0L,
                                success = true
                            ),
                            com.forge.os.domain.agent.ExecutionStep(
                                stepNumber = 2,
                                action = "Generate skill definition",
                                tool = "ai_synthesis",
                                args = skill.description,
                                result = "Created skill: ${skill.tool_name}",
                                duration = 0L,
                                success = true
                            )
                        ),
                        success = true,
                        outcome = "Successfully synthesized skill '${skill.name}' for reuse",
                        tags = listOf("skill_synthesis", "background_learning", "automation_creation")
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to record skill synthesis in ReflectionManager")
                }
                
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
