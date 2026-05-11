package com.forge.os.domain.companion

import android.content.Context
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.notifications.NotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase O-2 — Dependency monitor.
 *
 * Runs nightly (via [com.forge.os.data.workers.DependencyMonitorWorker]).
 * If usage exceeds the threshold the user has configured (default: >3 h/day
 * for 14 consecutive days, OR >50 sessions/week), it schedules ONE gentle
 * nudge — at most once every 30 days. The nudge is a notification that opens
 * the companion screen with a pre-filled caring message.
 *
 * Anti-dark-pattern design decisions (Phase O-6 checklist):
 *  - The nudge text never says "I'll miss you" or equivalent.
 *  - The nudge fires AT MOST once every 30 days; there is no escalation.
 *  - The user can turn the feature off entirely in Settings → Companion.
 *  - Session duration is self-reported (start/end timestamps written by
 *    CompanionViewModel). No background microphone or sensor usage.
 *  - All data is local-only; nothing is transmitted.
 */
@Singleton
class DependencyMonitor @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val configRepository: ConfigRepository,
    private val notificationHelper: NotificationHelper,
    private val personaManager: PersonaManager,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val stateFile: File
        get() = ctx.filesDir.resolve("workspace/companion/dependency_state.json")

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Called by [CompanionViewModel] when a session starts, to record start time.
     * Returns a session token the caller passes to [recordSessionEnd].
     */
    fun recordSessionStart(): Long = System.currentTimeMillis()

    /**
     * Called by [CompanionViewModel.endSession] to record the session's
     * wall-clock duration in milliseconds.
     */
    fun recordSessionEnd(startMs: Long) {
        val durationMs = System.currentTimeMillis() - startMs
        if (durationMs < 5_000) return // ignore accidental opens
        val state = load()
        val today = todayKey()
        val entry = state.dailyUsage[today] ?: DailyUsage()
        val updated = entry.copy(
            sessionCount = entry.sessionCount + 1,
            totalDurationMs = entry.totalDurationMs + durationMs,
        )
        val newState = state.copy(
            dailyUsage = state.dailyUsage + (today to updated),
        )
        save(newState)
        Timber.d("DependencyMonitor: recorded session %.1f min on $today", durationMs / 60_000.0)
    }

    /**
     * Called by [com.forge.os.data.workers.DependencyMonitorWorker] nightly.
     * Evaluates thresholds and fires a nudge notification if warranted.
     */
    fun runNightlyCheck() {
        val cfg = configRepository.get().friendMode
        if (!cfg.enabled) return
        if (!cfg.dependencyMonitorEnabled) return

        val state = load()

        // Prune old data (keep 60 days)
        val pruned = pruneOldData(state)

        val thresholdHoursPerDay = cfg.dependencyThresholdHoursPerDay
        val thresholdDays = cfg.dependencyThresholdConsecutiveDays
        val thresholdSessionsPerWeek = cfg.dependencyThresholdSessionsPerWeek
        val cooldownDays = 30L

        val nowMs = System.currentTimeMillis()
        val lastNudgeMs = pruned.lastNudgeMs

        if (lastNudgeMs > 0 &&
            nowMs - lastNudgeMs < cooldownDays * 24 * 3600 * 1000L) {
            Timber.d("DependencyMonitor: in cooldown, skipping")
            save(pruned)
            return
        }

        val triggered = exceedsHoursThreshold(pruned, thresholdHoursPerDay, thresholdDays) ||
                exceedsSessionsThreshold(pruned, thresholdSessionsPerWeek)

        if (triggered) {
            Timber.i("DependencyMonitor: usage threshold exceeded — scheduling nudge")
            val persona = personaManager.get()
            notificationHelper.notifyDependencyNudge(
                personaName = persona.name,
                title = "A thought from ${persona.name}",
                body = "We've been talking a lot lately — that means a lot to me. " +
                        "Is there someone in your life you could reach out to today?",
            )
            save(pruned.copy(lastNudgeMs = nowMs))
        } else {
            save(pruned)
        }
    }

    // ── Threshold evaluation ─────────────────────────────────────────────────

    private fun exceedsHoursThreshold(
        state: DependencyState,
        thresholdHours: Float,
        consecutiveDays: Int,
    ): Boolean {
        val thresholdMs = (thresholdHours * 3600 * 1000).toLong()
        val today = todayKey()
        var streak = 0
        for (i in 0 until consecutiveDays + 5) {
            val key = dateKey(daysAgo = i)
            if (key == today) continue // current day may be incomplete
            val usage = state.dailyUsage[key] ?: break
            if (usage.totalDurationMs >= thresholdMs) streak++
            else break
        }
        return streak >= consecutiveDays
    }

    private fun exceedsSessionsThreshold(state: DependencyState, threshold: Int): Boolean {
        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        val sessions = state.dailyUsage.entries
            .filter { (key, _) -> parseDateKey(key) >= sevenDaysAgo }
            .sumOf { it.value.sessionCount }
        return sessions >= threshold
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private fun load(): DependencyState {
        return try {
            if (!stateFile.exists()) DependencyState()
            else json.decodeFromString(stateFile.readText())
        } catch (e: Exception) {
            Timber.w(e, "DependencyMonitor: failed to read state, using default")
            DependencyState()
        }
    }

    private fun save(state: DependencyState) {
        try {
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(json.encodeToString(state))
        } catch (e: Exception) {
            Timber.e(e, "DependencyMonitor: failed to save state")
        }
    }

    private fun pruneOldData(state: DependencyState): DependencyState {
        val cutoff = System.currentTimeMillis() - 60L * 24 * 3600 * 1000
        val pruned = state.dailyUsage.filter { (key, _) -> parseDateKey(key) >= cutoff }
        return state.copy(dailyUsage = pruned)
    }

    // ── Date helpers ─────────────────────────────────────────────────────────

    private fun todayKey(): String = dateKey(daysAgo = 0)

    private fun dateKey(daysAgo: Int): String {
        val d = LocalDate.now().minusDays(daysAgo.toLong())
        return "${d.year}-${d.monthValue.toString().padStart(2,'0')}-${d.dayOfMonth.toString().padStart(2,'0')}"
    }

    private fun parseDateKey(key: String): Long = try {
        val (y, m, d) = key.split("-").map { it.toInt() }
        LocalDate.of(y, m, d).atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
    } catch (e: Exception) { 0L }
}

// ── Data classes ─────────────────────────────────────────────────────────────

@Serializable
data class DependencyState(
    val dailyUsage: Map<String, DailyUsage> = emptyMap(),
    val lastNudgeMs: Long = 0L,
)

@Serializable
data class DailyUsage(
    val sessionCount: Int = 0,
    val totalDurationMs: Long = 0L,
)
