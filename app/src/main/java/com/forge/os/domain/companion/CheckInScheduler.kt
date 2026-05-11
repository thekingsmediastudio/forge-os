package com.forge.os.domain.companion

import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.notifications.NotificationHelper
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

/**
 * Phase L — decides what proactive check-ins are due and dispatches a single
 * notification per opportunity.
 *
 * Three trigger families:
 *  1. Morning check-in (opt-in, [FriendModeSettings.morningCheckInTime]).
 *  2. Follow-ups       (from [EpisodicMemory.followUps] whose `dueAtMs` has passed).
 *  3. Anniversaries    (episodes whose `endedAt` is ≈ now − 365d ± 1d).
 *
 * All three obey:
 *  - friendMode.enabled + friendMode.proactiveCheckInsEnabled (master switches);
 *  - quiet hours;
 *  - friendMode.maxProactivePerDay daily cap;
 *  - de-dupe via [CheckInState] so we never fire the same episode twice.
 */
@Singleton
class CheckInScheduler @Inject constructor(
    private val configRepository: ConfigRepository,
    private val episodicStore: EpisodicMemoryStore,
    private val personaManager: PersonaManager,
    private val notifier: NotificationHelper,
    private val state: CheckInState,
) {

    /** Result type useful for tests + worker logs. */
    data class RunReport(
        val attempted: Int = 0,
        val fired: Int = 0,
        val skippedReason: String? = null,
    )

    fun runOnce(now: Long = System.currentTimeMillis()): RunReport {
        val cfg = configRepository.get().friendMode
        if (!cfg.enabled || !cfg.proactiveCheckInsEnabled) {
            return RunReport(skippedReason = "friend-mode disabled")
        }
        if (insideQuietHours(now, cfg.quietHoursStart, cfg.quietHoursEnd)) {
            return RunReport(skippedReason = "quiet hours")
        }
        val budget = (cfg.maxProactivePerDay - state.firedToday(now)).coerceAtLeast(0)
        if (budget <= 0) return RunReport(skippedReason = "daily cap reached")

        val opportunities = collectOpportunities(now, cfg).take(budget)
        if (opportunities.isEmpty()) return RunReport()

        var fired = 0
        for (op in opportunities) {
            try {
                notifier.notifyCompanionCheckIn(op.title, op.body, op.seedPrompt, op.tag)
                state.recordFired(now)
                op.markFired()
                fired++
            } catch (e: Exception) {
                Timber.e(e, "CheckInScheduler: notification failed (${op.tag})")
            }
        }
        return RunReport(attempted = opportunities.size, fired = fired)
    }

    // ─── Triggers ────────────────────────────────────────────────────────────

    private data class Opportunity(
        val tag: String,
        val title: String,
        val body: String,
        val seedPrompt: String,
        val markFired: () -> Unit,
    )

    private fun collectOpportunities(
        now: Long,
        cfg: com.forge.os.domain.config.FriendModeSettings,
    ): List<Opportunity> {
        val name = personaManager.get().name
        val out = mutableListOf<Opportunity>()

        // 1) morning check-in — once per local day, within ±15 min of configured time
        if (cfg.morningCheckInEnabled) {
            val today = CheckInState.dayKey(now)
            val ledger = state.snapshot()
            if (ledger.lastMorningDay != today &&
                isWithinWindowOf(now, cfg.morningCheckInTime, windowMinutes = 30)
            ) {
                out += Opportunity(
                    tag = "morning-$today",
                    title = "$name — morning",
                    body = "Just thinking of you. Want to share how today's shaping up?",
                    seedPrompt = "Good morning. ",
                    markFired = { state.markMorning(now) },
                )
            }
        }

        // 2) follow-ups whose dueAtMs has passed
        if (cfg.followUpsEnabled) {
            val ledger = state.snapshot()
            episodicStore.all().forEach { ep ->
                if (ep.id in ledger.firedFollowUps) return@forEach
                val due = ep.followUps.firstOrNull { it.dueAtMs in 1..now }
                if (due != null) {
                    out += Opportunity(
                        tag = "fu-${ep.id}",
                        title = "$name — checking in",
                        body = due.prompt.take(180),
                        seedPrompt = due.prompt,
                        markFired = { state.markFollowUp(ep.id) },
                    )
                }
            }
        }

        // 3) anniversaries — endedAt within ±1d of now-365d
        if (cfg.anniversariesEnabled) {
            val ledger = state.snapshot()
            val target = now - 365L * 86_400_000L
            val tolerance = 86_400_000L     // ±1 day
            episodicStore.all().forEach { ep ->
                if (ep.id in ledger.firedAnniversaries) return@forEach
                if ((ep.endedAt - target).absoluteValue <= tolerance) {
                    val summary = ep.summary.lineSequence().firstOrNull().orEmpty()
                        .ifBlank { ep.keyTopics.joinToString(", ") }
                    out += Opportunity(
                        tag = "anniv-${ep.id}",
                        title = "$name — a year ago today",
                        body = "A year ago we talked about: ${summary.take(160)}",
                        seedPrompt = "A year ago today we talked about ${summary.take(120)}. " +
                            "Has anything changed since then?",
                        markFired = { state.markAnniversary(ep.id) },
                    )
                }
            }
        }

        return out
    }

    // ─── Time helpers ────────────────────────────────────────────────────────

    /** True if `now` falls inside the `start..end` window (HH:mm, 24h, may wrap midnight). */
    internal fun insideQuietHours(now: Long, start: String, end: String): Boolean {
        val s = parseHm(start) ?: return false
        val e = parseHm(end) ?: return false
        if (s == e) return false
        val mins = nowMinutes(now)
        return if (s < e) mins in s until e
               else mins >= s || mins < e   // window wraps midnight
    }

    /** True if `now` is within ±[windowMinutes] of [hm]. */
    internal fun isWithinWindowOf(now: Long, hm: String, windowMinutes: Int): Boolean {
        val target = parseHm(hm) ?: return false
        val mins = nowMinutes(now)
        val delta = (mins - target).absoluteValue
        return delta <= windowMinutes || (24 * 60 - delta) <= windowMinutes
    }

    private fun nowMinutes(ms: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private fun parseHm(s: String): Int? {
        val parts = s.split(':')
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }
}