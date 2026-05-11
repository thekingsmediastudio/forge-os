package com.forge.os.domain.memory

import kotlinx.serialization.Serializable

// ─── Tier 1: Daily JSONL event ───────────────────────────────────────────────

@Serializable
data class DailyEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val role: String,          // user | assistant | tool_call | tool_result
    val content: String,
    val tags: List<String> = emptyList()
)

// ─── Tier 2: Long-term fact ──────────────────────────────────────────────────

@Serializable
data class FactEntry(
    val key: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,
    val tags: List<String> = emptyList(),
    val source: String = "agent"   // agent | user | compression
)

// ─── Tier 3: Skill snippet ───────────────────────────────────────────────────

@Serializable
data class SkillEntry(
    val name: String,
    val description: String,
    val code: String,
    val useCount: Int = 0,
    val lastUsed: Long = System.currentTimeMillis(),
    val tags: List<String> = emptyList()
)

// ─── Recall result (unified across tiers) ────────────────────────────────────

data class MemoryHit(
    val source: MemoryTier,
    val key: String,
    val content: String,
    val score: Float,
    val timestamp: Long
)

enum class MemoryTier { DAILY, LONGTERM, SKILL }
