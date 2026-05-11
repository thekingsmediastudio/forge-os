package com.forge.os.domain.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Simple, non-encrypted export bundle for memory. The Phase D plan called for
 * an encrypted `.forge-memory` archive; we ship plaintext JSON in this drop
 * (clearly marked) and leave biometric-gated encryption as a follow-up so
 * users can already back up and round-trip their memory today.
 */
@Serializable
data class MemoryArchive(
    val version: String = "1",
    val exportedAt: Long = System.currentTimeMillis(),
    val facts: Map<String, FactEntry> = emptyMap(),
    val skills: List<SkillEntry> = emptyList(),
    val daily: List<DailyEvent> = emptyList(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
        fun toJson(archive: MemoryArchive): String = json.encodeToString(archive)
        fun fromJson(text: String): MemoryArchive = json.decodeFromString(text)
    }
}
