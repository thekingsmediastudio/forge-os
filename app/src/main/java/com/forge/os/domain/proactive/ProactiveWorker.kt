package com.forge.os.domain.proactive

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.forge.os.data.browser.BrowserHistory
import com.forge.os.domain.control.AgentControlPlane
import com.forge.os.domain.notifications.AgentNotificationBuilder
import com.forge.os.domain.agent.ToolRegistry
import com.forge.os.domain.config.ConfigRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.forge.os.domain.doctor.DoctorService
import com.forge.os.domain.doctor.CheckStatus
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Phase Q — gentle proactive suggestion worker.
 *
 * Runs every ~30 minutes (scheduled by [ProactiveScheduler]). When the
 * [AgentControlPlane.PROACTIVE_SUGGEST] capability is OFF this worker is a
 * no-op. When ON it inspects recent in-app activity and may post a single
 * suggestion notification with action buttons that route back to the agent.
 *
 * The current heuristics are intentionally conservative — better to miss a
 * suggestion than to be annoying. Future iterations can plug a real planner
 * into [computeSuggestion].
 */
@HiltWorker
class ProactiveWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val controlPlane: AgentControlPlane,
    private val history: BrowserHistory,
    private val synthesizer: SkillSynthesizer,
    private val doctor: DoctorService,
    private val notifier: AgentNotificationBuilder,
    private val patternAnalyzer: PatternAnalyzer,
    private val prefetchCache: PrefetchCache,
    private val configRepository: ConfigRepository,
    private val toolRegistry: ToolRegistry,
    private val backgroundLog: com.forge.os.domain.debug.BackgroundTaskLogManager
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Predictive Prefetch Tick (Feature 9)
        runPrefetchTick()

        if (!controlPlane.isEnabled(AgentControlPlane.PROACTIVE_SUGGEST)) {
            return Result.success()
        }
        val suggestion = computeSuggestion() ?: return Result.success()
        try {
            notifier.postWithActions(
                title = suggestion.title,
                body = suggestion.body,
                channelId = "forge_companion",
                actions = suggestion.actions,
            )
        } catch (t: Throwable) {
            Timber.w(t, "ProactiveWorker post failed")
            return Result.retry()
        }
        return Result.success()
    }

    private data class Suggestion(
        val title: String,
        val body: String,
        val actions: List<AgentNotificationBuilder.ActionSpec>,
    )

    private suspend fun computeSuggestion(): Suggestion? {
        // 1. Check for synthesizable skills (New Feature)
        val skill = synthesizer.findAndSynthesize()
        if (skill != null) {
            val argsJson = Json.encodeToString(SynthesizedSkill.serializer(), skill)
            return Suggestion(
                title = "New capability discovered!",
                body = "I noticed you were doing ${skill.name}. Want me to turn that into a permanent tool?",
                actions = listOf(
                    AgentNotificationBuilder.ActionSpec(
                        label = "Create Tool",
                        kind = "tool_call",
                        payloadJson = """{"tool":"plugin_create","args":$argsJson}""",
                    ),
                    AgentNotificationBuilder.ActionSpec(
                        label = "Dismiss",
                        kind = "chat_message",
                        payloadJson = """{"text":"Dismissed skill suggestion for ${skill.name}"}""",
                    )
                )
            )
        }

        // 2. Check for system health issues (New Feature)
        val report = doctor.runChecks()
        val failure = report.checks.firstOrNull { it.status == CheckStatus.FAIL && it.fixable }
        if (failure != null) {
            return Suggestion(
                title = "System issue detected",
                body = "I noticed a problem with ${failure.title}: ${failure.detail}. Should I attempt to fix it?",
                actions = listOf(
                    AgentNotificationBuilder.ActionSpec(
                        label = "Fix Now",
                        kind = "tool_call",
                        payloadJson = """{"tool":"doctor_fix","args":{"id":"${failure.id}"}}""",
                    ),
                    AgentNotificationBuilder.ActionSpec(
                        label = "Ignore",
                        kind = "chat_message",
                        payloadJson = """{"text":"Ignored health suggestion for ${failure.id}"}""",
                    )
                )
            )
        }

        // 3. Fallback to browser history
        val recent = history.list(limit = 25)
        if (recent.isEmpty()) return null
        val last = recent.first()
        // Very simple seed suggestion: offer to re-open the last URL the
        // user visited and to serve the workspace folder. Both are wired
        // back through registered notification actions.
        return Suggestion(
            title = "Pick up where you left off?",
            body = "You were last on ${last.url}. Want me to reopen it, or serve your project on this Wi-Fi?",
            actions = listOf(
                AgentNotificationBuilder.ActionSpec(
                    label = "Reopen",
                    kind = "tool_call",
                    payloadJson = """{"tool":"browser_navigate","args":{"url":"${last.url}"}}""",
                ),
                AgentNotificationBuilder.ActionSpec(
                    label = "Serve workspace",
                    kind = "tool_call",
                    payloadJson = """{"tool":"project_serve","args":{"path":"workspace"}}""",
                ),
                AgentNotificationBuilder.ActionSpec(
                    label = "Dismiss",
                    kind = "chat_message",
                    payloadJson = """{"text":"Dismissed proactive suggestion"}""",
                ),
            ),
        )
    }

    private suspend fun runPrefetchTick() {
        val config = configRepository.get()
        if (!config.prefetchSettings.enabled) return

        Timber.d("ProactiveWorker: running prefetch tick...")
        val prediction = patternAnalyzer.predictNextTool() ?: return
        
        Timber.i("ProactiveWorker: predicted next tool → ${prediction.toolName}")

        // Safety check: is it an unsafe tool?
        val isUnsafe = config.behaviorRules.confirmDestructive.contains(prediction.toolName)
        if (isUnsafe && !config.prefetchSettings.allowUnsafeTools) {
            Timber.w("ProactiveWorker: skipping unsafe prefetch '${prediction.toolName}' (disabled in settings)")
            return
        }

        try {
            val result = toolRegistry.dispatch(
                toolName = prediction.toolName,
                argsJson = prediction.argsJson,
                toolCallId = "prefetch_${System.currentTimeMillis()}"
            )
            
            if (!result.isError) {
                prefetchCache.put(prediction.toolName, prediction.argsJson, result)
                Timber.d("ProactiveWorker: prefetched and cached result for ${prediction.toolName}")
            } else {
                Timber.w("ProactiveWorker: prefetch for ${prediction.toolName} failed: ${result.output}")
            }

            // Feature: Unified Background Log
            backgroundLog.addLog(
                com.forge.os.domain.debug.BackgroundTaskLog(
                    id = "prefetch_${System.currentTimeMillis()}",
                    source = com.forge.os.domain.debug.TaskSource.PROACTIVE,
                    label = "Prefetch: ${prediction.toolName}",
                    success = !result.isError,
                    output = result.output,
                    error = if (result.isError) result.output else null,
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "ProactiveWorker: error during prefetch execution")
            backgroundLog.addLog(
                com.forge.os.domain.debug.BackgroundTaskLog(
                    id = "prefetch_err_${System.currentTimeMillis()}",
                    source = com.forge.os.domain.debug.TaskSource.PROACTIVE,
                    label = "Prefetch Error",
                    success = false,
                    output = e.message ?: "Unknown error",
                    error = e.message,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }
}
