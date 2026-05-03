package com.forge.os.domain.agent

import com.forge.os.domain.debug.ReplayTrace
import com.forge.os.data.api.AiApiManager
import com.forge.os.domain.companion.Mode
import com.forge.os.domain.memory.MemoryManager
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Reflector analyzes execution traces after a task completes (or fails).
 * It identifies mistakes, loops, or inefficiencies and stores them as
 * "insights" in long-term memory to guide future attempts.
 */
@Singleton
class Reflector @Inject constructor(
    private val aiApiManager: AiApiManager,
    private val memoryManager: MemoryManager,
    private val backgroundLog: com.forge.os.domain.debug.BackgroundTaskLogManager
) {
    /**
     * Analyze the trace and store insights if meaningful lessons are found.
     * Returns the generated insights string for logging/UI purposes.
     */
    suspend fun reflectAndLearn(trace: ReplayTrace): String? {
        if (trace.steps.isEmpty()) return null
        
        // Skip reflection for very short successful tasks to save tokens
        if (trace.success && trace.steps.size < 3) {
            Timber.d("Reflector: skipping short successful task")
            return null
        }
        
        val prompt = buildString {
            appendLine("You are the Forge OS Reflection Engine.")
            appendLine("Analyze the execution trace below for errors, circular reasoning, inefficient tool usage, or missed opportunities.")
            appendLine("Generate a concise set of 'Insights' (2-3 bullet points) to help the agent succeed or be more efficient next time.")
            appendLine()
            appendLine("### AGENT TRACE ###")
            appendLine("Goal: ${trace.prompt}")
            appendLine("Result: ${if (trace.success) "SUCCESS" else "FAILURE"}")
            appendLine("Final Output: ${trace.finalResult?.take(500)}")
            appendLine("Steps taken: ${trace.steps.size}")
            
            // Focus on the last few steps and any errors
            val importantSteps = if (trace.steps.size <= 6) trace.steps 
                                 else trace.steps.filterIndexed { i, s -> i < 2 || i >= trace.steps.size - 4 || s.toolCalls.any { it.isError } }
            
            importantSteps.forEach { step ->
                appendLine("\nStep ${step.iteration}:")
                appendLine("Assistant thought: ${step.rawResponse.take(300)}")
                step.toolCalls.forEach { tool ->
                    appendLine("Tool Call: ${tool.name}(${tool.argsJson}) -> ${if (tool.isError) "ERROR: " else ""}${tool.result?.take(200)}")
                }
            }
            
            appendLine("\n### INSTRUCTIONS ###")
            appendLine("Format your response as a concise list of lessons. Be extremely specific.")
            appendLine("- If a tool failed because of a path error, mention the correct workspace structure.")
            appendLine("- If the agent looped, explain the logic flaw.")
            appendLine("- If it succeeded but took too many steps, suggest a better tool combo.")
            appendLine("Response MUST be under 150 words.")
        }
        
        return try {
            val insights = aiApiManager.chat(
                messages = listOf(com.forge.os.data.api.ApiMessage(role = "user", content = prompt)),
                systemPrompt = "You are a senior debugger and systems architect. Analyze the execution trace and provide actionable feedback.",
                mode = Mode.REFLECTION,
            ).content?.trim() ?: ""
            
            if (insights.isNotBlank() && !insights.contains("no significant errors", ignoreCase = true)) {
                val key = "reflection_${System.currentTimeMillis()}"
                memoryManager.store(
                    key = key,
                    content = "Task Reflection [Goal: ${trace.prompt}]:\n$insights",
                    tags = listOf("reflection", "learning")
                )
                Timber.i("Reflector: Stored new insight $key")
                backgroundLog.addLog(
                    com.forge.os.domain.debug.BackgroundTaskLog(
                        id = key,
                        source = com.forge.os.domain.debug.TaskSource.REFLECTION,
                        label = "Task Reflection",
                        success = true,
                        output = insights,
                        timestamp = System.currentTimeMillis()
                    )
                )
                insights
            } else {
                backgroundLog.addLog(
                    com.forge.os.domain.debug.BackgroundTaskLog(
                        id = "reflection_noop_${System.currentTimeMillis()}",
                        source = com.forge.os.domain.debug.TaskSource.REFLECTION,
                        label = "Reflection Skipped",
                        success = true,
                        output = "No significant insights found.",
                        timestamp = System.currentTimeMillis()
                    )
                )
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Reflector: Failed to analyze trace")
            backgroundLog.addLog(
                com.forge.os.domain.debug.BackgroundTaskLog(
                    id = "reflection_err_${System.currentTimeMillis()}",
                    source = com.forge.os.domain.debug.TaskSource.REFLECTION,
                    label = "Reflection Error",
                    success = false,
                    output = e.message ?: "Unknown error",
                    error = e.message,
                    timestamp = System.currentTimeMillis()
                )
            )
            null
        }
    }
}
