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
 * Manages persistent execution history so the agent can:
 * - See what steps were taken in the current session
 * - Continue from where it left off if it fails
 * - Provide context to the user about what happened
 */
@Singleton
class ExecutionHistoryManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val historyDir: File get() = context.filesDir.resolve("workspace/execution_history").apply { mkdirs() }
    private val currentSessionFile: File get() = historyDir.resolve("current_session.json")

    /**
     * Start a new execution session.
     */
    fun startSession(goal: String): ExecutionSession {
        val session = ExecutionSession(
            sessionId = "session_${System.currentTimeMillis()}",
            goal = goal,
            startTime = System.currentTimeMillis(),
            steps = emptyList(),
            status = "in_progress"
        )
        saveSession(session)
        Timber.i("Started execution session: ${session.sessionId}")
        return session
    }

    /**
     * Add a step to the current session.
     */
    fun addStep(
        sessionId: String,
        stepNumber: Int,
        action: String,
        tool: String,
        args: String,
        result: String,
        success: Boolean
    ) {
        val session = loadSession(sessionId) ?: return
        
        val step = ExecutionHistoryStep(
            stepNumber = stepNumber,
            action = action,
            tool = tool,
            args = args,
            result = result,
            success = success,
            timestamp = System.currentTimeMillis()
        )

        val updatedSession = session.copy(
            steps = session.steps + step,
            lastUpdated = System.currentTimeMillis()
        )

        saveSession(updatedSession)
        Timber.i("Added step $stepNumber to session $sessionId")
    }

    /**
     * Mark session as completed.
     */
    fun completeSession(sessionId: String, finalStatus: String = "completed") {
        val session = loadSession(sessionId) ?: return
        val updated = session.copy(
            status = finalStatus,
            endTime = System.currentTimeMillis()
        )
        saveSession(updated)
        Timber.i("Completed session $sessionId with status: $finalStatus")
    }

    /**
     * Get the current session.
     */
    fun getCurrentSession(): ExecutionSession? {
        return runCatching {
            val content = currentSessionFile.readText()
            json.decodeFromString<ExecutionSession>(content)
        }.getOrNull()
    }

    /**
     * Load a specific session by ID.
     */
    fun loadSession(sessionId: String): ExecutionSession? {
        return runCatching {
            val file = historyDir.resolve("$sessionId.json")
            if (!file.exists()) return null
            val content = file.readText()
            json.decodeFromString<ExecutionSession>(content)
        }.getOrNull()
    }

    /**
     * Get all sessions (for history view).
     */
    fun getAllSessions(): List<ExecutionSession> {
        return historyDir.listFiles { file ->
            file.isFile && file.name.endsWith(".json") && file.name != "current_session.json"
        }?.mapNotNull { file ->
            runCatching {
                json.decodeFromString<ExecutionSession>(file.readText())
            }.getOrNull()
        }?.sortedByDescending { it.startTime } ?: emptyList()
    }

    /**
     * Get a formatted history for display to the user.
     */
    fun getFormattedHistory(sessionId: String): String {
        val session = loadSession(sessionId) ?: return "Session not found"

        return buildString {
            appendLine("📋 Execution History: ${session.sessionId}")
            appendLine("Goal: ${session.goal}")
            appendLine("Status: ${session.status}")
            appendLine("Started: ${formatTime(session.startTime)}")
            if (session.endTime != null) {
                appendLine("Ended: ${formatTime(session.endTime)}")
                appendLine("Duration: ${formatDuration(session.endTime - session.startTime)}")
            }
            appendLine()
            appendLine("Steps (${session.steps.size}):")
            session.steps.forEach { step ->
                val icon = if (step.success) "✅" else "❌"
                appendLine("$icon Step ${step.stepNumber}: ${step.action}")
                appendLine("   Tool: ${step.tool}")
                appendLine("   Result: ${step.result.take(100)}")
            }
        }
    }

    /**
     * Create a context string for the agent to understand what happened.
     */
    fun getSessionContext(sessionId: String): String {
        val session = loadSession(sessionId) ?: return ""

        return buildString {
            appendLine("🔄 PREVIOUS EXECUTION CONTEXT")
            appendLine()
            appendLine("Goal: ${session.goal}")
            appendLine("Status: ${session.status}")
            appendLine()
            appendLine("Steps taken:")
            session.steps.forEach { step ->
                val status = if (step.success) "✅" else "❌"
                appendLine("$status ${step.stepNumber}. ${step.action}")
                if (!step.success) {
                    appendLine("   Error: ${step.result}")
                }
            }
            appendLine()
            if (session.status == "failed") {
                appendLine("⚠️ The previous attempt failed at step ${session.steps.lastOrNull()?.stepNumber}")
                appendLine("Consider a different approach or check the error above.")
            }
        }
    }

    /**
     * Save a session to disk.
     */
    private fun saveSession(session: ExecutionSession) {
        try {
            val file = historyDir.resolve("${session.sessionId}.json")
            file.writeText(json.encodeToString(session))
            
            // Also update current_session.json
            currentSessionFile.writeText(json.encodeToString(session))
        } catch (e: Exception) {
            Timber.e(e, "Failed to save session")
        }
    }

    private fun formatTime(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        return sdf.format(java.util.Date(ms))
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }
}

@Serializable
data class ExecutionSession(
    val sessionId: String,
    val goal: String,
    val startTime: Long,
    val endTime: Long? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
    val steps: List<ExecutionHistoryStep> = emptyList(),
    val status: String = "in_progress" // in_progress, completed, failed
)

@Serializable
data class ExecutionHistoryStep(
    val stepNumber: Int,
    val action: String,
    val tool: String,
    val args: String,
    val result: String,
    val success: Boolean,
    val timestamp: Long
)
