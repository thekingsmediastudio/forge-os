package com.forge.os.domain.proactive

import com.forge.os.data.api.AiApiManager
import com.forge.os.data.api.ApiMessage
import com.forge.os.domain.agent.ToolRegistry
import com.forge.os.domain.companion.Mode
import com.forge.os.domain.memory.MemoryManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PatternAnalyzer @Inject constructor(
    private val memoryManager: MemoryManager,
    private val apiManager: AiApiManager,
    private val toolRegistry: ToolRegistry,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val sysPrompt = """
        You are the Predictive Prefetch Analyzer for Forge OS.
        Your goal is to anticipate the user's next tool call based on their recent activity.
        
        You will be provided with the last 15-20 events from the user's interaction history.
        Events include user messages, assistant responses, and tool calls/results.
        
        Analyze the pattern. For example:
        - If the user just listed files, they might want to read one of them.
        - If the user just asked about a project, they might want to see the git status.
        - If the user is navigating a website, they might want to get the HTML or click a button.
        
        Return exactly ONE JSON object on a single line:
        {
          "tool": "tool_name",
          "args": { "arg1": "val1", ... }
        }
        
        If you cannot confidently predict a useful next step, return:
        { "tool": null }
        
        Rules:
        1. Only predict tools that exist in the provided registry.
        2. Do NOT predict destructive tools (delete, uninstall) unless it's extremely obvious.
        3. Keep arguments precise and based ONLY on information present in the history.
        4. Output JSON ONLY. No markdown fences.
    """.trimIndent()

    suspend fun predictNextTool(): Prediction? {
        val recentEvents = memoryManager.daily.readRecent(days = 1).takeLast(20)
        if (recentEvents.isEmpty()) return null

        val history = recentEvents.joinToString("\n") { event ->
            "[${event.role}] ${event.content.take(200)}"
        }

        val tools = toolRegistry.getDefinitions().map { it.function.name }.joinToString(", ")
        
        val prompt = "Available Tools: $tools\n\nRecent Activity:\n$history\n\nPredict the next tool call:"

        return try {
            val response = apiManager.chatWithFallback(
                messages = listOf(ApiMessage(role = "user", content = prompt)),
                tools = emptyList(),
                systemPrompt = sysPrompt,
                spec = null,
                mode = Mode.PLANNER
            )
            
            val content = response.content?.trim() ?: return null
            parsePrediction(content)
        } catch (e: Exception) {
            Timber.w(e, "PatternAnalyzer: prediction failed")
            null
        }
    }

    private fun parsePrediction(raw: String): Prediction? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val body = raw.substring(start, end + 1)
        
        return try {
            val node = json.parseToJsonElement(body) as JsonObject
            val tool = node["tool"]?.jsonPrimitive?.content?.takeIf { it != "null" && it.isNotBlank() } ?: return null
            val args = node["args"]?.toString() ?: "{}"
            Prediction(tool, args)
        } catch (e: Exception) {
            Timber.w(e, "PatternAnalyzer: parse failed on $body")
            null
        }
    }

    data class Prediction(val toolName: String, val argsJson: String)
}
