package com.forge.os.domain.agent

import com.forge.os.domain.memory.SkillMemory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase E4 — Skill recorder.
 * Watches successful agent turns and offers to capture re-usable Python /
 * shell snippets into [SkillMemory]. Operates in two modes:
 *  • Auto: when a `python_exec` or `shell_exec` tool call succeeded recently,
 *    auto-record it as a candidate skill keyed off the user's last request.
 *  • Manual: user types `save skill <name>` to commit the latest candidate.
 */
@Singleton
class SkillRecorder @Inject constructor(
    private val skillMemory: SkillMemory
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var lastUserRequest: String = ""
    private var lastCandidate: Candidate? = null

    data class Candidate(
        val toolName: String,
        val code: String,
        val description: String,
        val capturedAt: Long = System.currentTimeMillis()
    )

    fun noteUserRequest(text: String) { lastUserRequest = text.take(280) }

    /** Called from the chat layer after every tool result. */
    fun recordToolUsage(toolName: String, args: String, isError: Boolean) {
        if (isError) return
        val captureKinds = setOf("python_exec", "shell_exec", "code_run")
        if (toolName !in captureKinds) return
        val code = extractCode(args) ?: return
        if (code.length < 8) return
        lastCandidate = Candidate(
            toolName = toolName,
            code = code,
            description = lastUserRequest.ifBlank { "Captured $toolName" }
        )
        Timber.d("SkillRecorder: candidate captured (${code.length}b)")
    }

    /** Commits the latest candidate under [name]. Returns the saved description. */
    fun commit(name: String): String? {
        val c = lastCandidate ?: return null
        val tags = listOf(c.toolName, "auto-recorded")
        skillMemory.store(name = name, description = c.description, code = c.code, tags = tags)
        lastCandidate = null
        return c.description
    }

    fun candidate(): Candidate? = lastCandidate

    private fun extractCode(args: String): String? = try {
        val obj = json.parseToJsonElement(args) as? JsonObject
        obj?.get("code")?.jsonPrimitive?.content
            ?: obj?.get("script")?.jsonPrimitive?.content
            ?: obj?.get("command")?.jsonPrimitive?.content
    } catch (_: Exception) { null }
}
