package com.forge.os.domain.companion

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Phase N — quiet, additive relationship counters.
 *
 * Deliberately NOT gamified: no streaks, no levels, no XP language. Just a
 * "we've known each other X days, talked Y times" surface plus a 0..1
 * `currentRapport` derived from session frequency × average sentiment.
 *
 * Stored at `workspace/companion/relationship.json`. Recomputed on app start
 * (via [recomputeFromEpisodes]) and on every session end (via [recordSession]).
 */
@Serializable
data class Milestone(
    val ts: Long,
    /** Short tag, e.g. "first conversation", "day 30", "100 conversations". */
    val label: String,
)

@Serializable
data class RelationshipSnapshot(
    /** Epoch ms of the very first conversation we have on file. 0 = unknown. */
    val firstSeenAt: Long = 0L,
    val totalConversations: Int = 0,
    /** EMA-derived 0..1 score; see [RelationshipState.recomputeFromEpisodes]. */
    val currentRapport: Double = 0.0,
    val sharedMilestones: List<Milestone> = emptyList(),
) {
    fun daysKnown(now: Long = System.currentTimeMillis()): Int {
        if (firstSeenAt <= 0L) return 0
        return ((now - firstSeenAt) / 86_400_000L).toInt().coerceAtLeast(0)
    }

    fun rapportLabel(): String = when {
        currentRapport >= 0.66 -> "high"
        currentRapport >= 0.33 -> "warming"
        else                    -> "new"
    }
}

@Singleton
class RelationshipState @Inject constructor(
    @ApplicationContext private val context: Context,
    private val episodicStore: EpisodicMemoryStore,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val file: File by lazy {
        File(context.filesDir, "workspace/companion").apply { mkdirs() }
            .resolve("relationship.json")
    }

    private val _snapshot = MutableStateFlow(load())
    val snapshot: StateFlow<RelationshipSnapshot> = _snapshot

    fun get(): RelationshipSnapshot = _snapshot.value

    private fun load(): RelationshipSnapshot = try {
        if (file.exists())
            json.decodeFromString(RelationshipSnapshot.serializer(), file.readText())
        else RelationshipSnapshot()
    } catch (e: Exception) {
        Timber.w(e, "RelationshipState: corrupt file, resetting")
        RelationshipSnapshot()
    }

    private fun persist() {
        try { file.writeText(json.encodeToString(_snapshot.value)) }
        catch (e: Exception) { Timber.e(e, "RelationshipState: persist failed") }
    }

    /**
     * Re-derive everything from the episodic store. Called on app start.
     * Idempotent — safe to call repeatedly.
     */
    fun recomputeFromEpisodes(now: Long = System.currentTimeMillis()) {
        val episodes = episodicStore.all().sortedBy { it.startedAt }
        if (episodes.isEmpty()) return
        val first = episodes.first().startedAt
        val total = episodes.size

        // EMA across the last ~14 days of activity. Each session contributes
        // a positive bump scaled by its turn count and how recent it is.
        val rapport = computeRapport(episodes, now)

        val milestones = deriveMilestones(first, total, _snapshot.value.sharedMilestones, now)

        _snapshot.value = RelationshipSnapshot(
            firstSeenAt = first,
            totalConversations = total,
            currentRapport = rapport,
            sharedMilestones = milestones,
        )
        persist()
    }

    /** Cheap incremental update for endSession; full recompute is also fine. */
    fun recordSession(now: Long = System.currentTimeMillis()) {
        val cur = _snapshot.value
        val first = if (cur.firstSeenAt == 0L) now else cur.firstSeenAt
        val total = cur.totalConversations + 1
        val rapport = computeRapport(episodicStore.all(), now)
        _snapshot.value = cur.copy(
            firstSeenAt = first,
            totalConversations = total,
            currentRapport = rapport,
            sharedMilestones = deriveMilestones(first, total, cur.sharedMilestones, now),
        )
        persist()
    }

    /**
     * Bounded 0..1 score combining recent activity + an inferred sentiment
     * proxy (long sessions and "share" intents lift; "vent" + crisis hold
     * the line). Keep simple — this is a vibe meter, not a model.
     */
    private fun computeRapport(
        episodes: List<EpisodicMemory>, now: Long,
    ): Double {
        if (episodes.isEmpty()) return 0.0
        var score = 0.0
        for (ep in episodes) {
            val ageDays = (now - ep.endedAt) / 86_400_000.0
            // recency weight: half-life ≈ 7d
            val w = exp(-ageDays / 7.0)
            // session contribution: bigger sessions ≈ more invested
            val turn = min(ep.turnCount.toDouble(), 30.0) / 30.0
            // mood proxy: presence of "calm", "share" lifts; "anxious"/"angry" damps
            val mood = ep.moodTrajectory.lowercase()
            val moodBoost = when {
                "calm"  in mood || "relax" in mood || "happy" in mood ||
                "share" in mood || "curious" in mood || "good"  in mood ->  0.4
                "anx"   in mood || "angry" in mood || "sad"  in mood   -> -0.2
                else -> 0.0
            }
            score += w * (0.5 + 0.5 * turn + moodBoost)
        }
        // squash to 0..1; tuned so ~10 recent decent sessions land near 0.7
        val normalised = 1.0 - exp(-score / 6.0)
        return max(0.0, min(1.0, normalised))
    }

    private fun deriveMilestones(
        firstSeenAt: Long, total: Int, existing: List<Milestone>, now: Long,
    ): List<Milestone> {
        val out = existing.toMutableList()
        val have = existing.map { it.label }.toSet()
        fun add(label: String, ts: Long = now) {
            if (label !in have) out += Milestone(ts, label)
        }
        if (total >= 1)   add("first conversation", firstSeenAt)
        if (total >= 10)  add("10 conversations")
        if (total >= 50)  add("50 conversations")
        if (total >= 100) add("100 conversations")
        val days = ((now - firstSeenAt) / 86_400_000L).toInt()
        if (days >= 30)  add("day 30")
        if (days >= 90)  add("day 90")
        if (days >= 365) add("year one")
        return out
    }
}
