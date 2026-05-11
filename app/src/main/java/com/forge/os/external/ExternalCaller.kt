package com.forge.os.external

import kotlinx.serialization.Serializable

/**
 * Persistent record of an external app that has requested (and possibly been granted)
 * access to the Forge OS external API.
 *
 *   workspace/system/external_callers.json   — list<ExternalCaller>
 */
@Serializable
data class ExternalCaller(
    val packageName: String,
    val displayName: String = packageName,
    val signingCertSha256: String = "",
    val firstSeen: Long = System.currentTimeMillis(),
    val lastUsed: Long = 0L,
    val status: GrantStatus = GrantStatus.PENDING,
    val capabilities: Capabilities = Capabilities(),
    val rateLimit: RateLimit = RateLimit(),
)

@Serializable
enum class GrantStatus { PENDING, GRANTED, DENIED, REVOKED }

@Serializable
data class Capabilities(
    val listTools: Boolean = false,
    val invokeTools: Boolean = false,
    val toolAllowlist: List<String> = emptyList(),   // empty = none; ["*"] = all enabled tools
    val askAgent: Boolean = false,
    val readMemory: Boolean = false,
    val memoryTagFilter: String = "",                // "" = all; otherwise tag must match
    val writeMemory: Boolean = false,
    val runSkills: Boolean = false,
    val skillAllowlist: List<String> = emptyList(),
)

@Serializable
data class RateLimit(
    val callsPerMinute: Int = 30,
    val tokensPerDay: Int = 50_000,
)
