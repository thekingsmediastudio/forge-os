package com.forge.os.domain.companion

import kotlinx.serialization.Serializable

@Serializable
data class EpisodicMemory(
    val id: String,
    val conversationId: String,
    val startedAt: Long,
    val endedAt: Long,
    val turnCount: Int,
    val summary: String,
    val moodTrajectory: String,
    val keyTopics: List<String>,
    val followUps: List<FollowUp> = emptyList(),
    val tags: List<MessageTags> = emptyList(),
) {
    /**
     * Phase O-5 — UI alias. Most-recent activity timestamp for the episode.
     * Computed (not serialised) so existing JSON files load unchanged.
     */
    val timestamp: Long get() = endedAt
}

@Serializable
data class FollowUp(
    val prompt: String,
    val dueAtMs: Long,
) {
    /** Phase O-5 — UI alias for [prompt]. Computed, not serialised. */
    val question: String get() = prompt
}