package com.forge.os.domain.directives

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class AgentDirective(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val enabled: Boolean = true,
    val category: String = "behavior", // behavior, security, formatting, project
    val scope: String = "global",     // global, or a specific channelId/slug
    val createdAt: Long = System.currentTimeMillis()
)
